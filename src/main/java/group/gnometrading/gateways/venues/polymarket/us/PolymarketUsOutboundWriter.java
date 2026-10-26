package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.IdleConnections;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OutboundSocketWriter;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.MutableString;
import group.gnometrading.utils.ByteBufferUtils;
import group.gnometrading.utils.FixedPointAscii;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Order entry for Polymarket US's retail API.
 *
 * <p>The API has no client order id and doesn't deduplicate, so a submit is never resent: one with no clear answer
 * is looked up, and otherwise left for the venue's events to settle. Events are matched to orders without one too. A
 * resting order goes as good-till-date, its expiry a {@link PolymarketUsStamps stamp} that names it. An IOC
 * can't carry one, so the reader matches it by side and price, and while one IOC's outcome is unknown another on the
 * same side at the same price is refused: the two would be indistinguishable. A FOK is refused: the venue runs it as an IOC.
 *
 * <p>A resting order is amended in place, keeping its stamp. An IOC can't be.
 */
public final class PolymarketUsOutboundWriter extends OutboundSocketWriter {

    // Through Cloudflare a connection still answered after two minutes idle and was silently dead by five.
    private static final long MAX_IDLE_CONNECTION_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    // IOCs whose outcome a lost answer left unknown; more than this and every IOC is refused until they settle.
    private static final int MAX_UNRESOLVED_IMMEDIATE = 16;
    private static final byte[] IMMEDIATE_ID_PREFIX = "ioc-".getBytes(StandardCharsets.US_ASCII);

    private final Logger logger;
    private final HTTPClient httpClient;
    private final IdleConnections idleConnections;
    private final String apiHost;
    private final PolymarketUsAuthSigner signer;
    // Kept to sign requests that may be retried; a method reference made per call would allocate.
    private final LongSupplier epochMillisSupplier = this::epochMillis;
    private final PolymarketUsMarketInfo marketInfo;
    private final PolymarketUsListing venueListing;
    private final EpochNanoClock clock;
    private final int sessionSlot;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private int jsonBodyLength;
    private final byte[] cancelBody;
    private final MutableString orderPath = new MutableString(PolymarketUsRequests.ORDER_PATH.length()
            + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH
            + Math.max(PolymarketUsRequests.CANCEL_SUFFIX.length(), PolymarketUsRequests.MODIFY_SUFFIX.length()));
    private final PolymarketUsOrderFields lookupFields = new PolymarketUsOrderFields();

    private final long[] unresolvedImmediate = new long[MAX_UNRESOLVED_IMMEDIATE];
    private int unresolvedImmediateCount;

    public PolymarketUsOutboundWriter(
            final Logger logger,
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final HTTPClient httpClient,
            final String apiHost,
            final PolymarketUsAuthSigner signer,
            final PolymarketUsMarketInfo marketInfo,
            final EpochNanoClock clock,
            final Listing listing,
            final String sessionTag) {
        super(orderOutboundBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        this.logger = logger;
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.idleConnections =
                new IdleConnections(logger, "Polymarket US", httpClient, apiHost, MAX_IDLE_CONNECTION_NANOS);
        this.signer = signer;
        this.marketInfo = marketInfo;
        this.venueListing = new PolymarketUsListing(listing);
        this.clock = clock;
        this.sessionSlot = PolymarketUsStamps.slot(sessionTag);
        this.jsonEncoder.wrap(this.jsonBodyBuffer);
        this.cancelBody =
                ("{\"marketSlug\":\"" + this.venueListing.marketSlug() + "\"}").getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public String roleName() {
        return "polymarket-us-outbound-writer";
    }

    @Override
    protected boolean resendsAreSafe() {
        return false;
    }

    @Override
    protected boolean prepareOrder(final OrderContext ctx) {
        if (this.order.decoder.orderType() == OrderType.MARKET) {
            // A market order needs a worst price, which is the OMS's to choose; it sends a priced IOC instead.
            return false;
        }
        final long price = this.order.decoder.price();
        if (!validTerms(price, this.order.decoder.size())) {
            return false;
        }
        final long venuePrice = this.venueListing.convertPrice(price);
        final TimeInForce tif = this.order.decoder.timeInForce();
        if (tif == TimeInForce.FILL_OR_KILL) {
            // The venue takes a FOK as an IOC (verified live), which can fill in part.
            return false;
        }
        final boolean immediate = tif == TimeInForce.IMMEDIATE_OR_CANCELED;
        ctx.limitPrice = venuePrice;
        ctx.submittedAtMillis = epochMillis();
        if (immediate) {
            if (twinOfUnresolvedImmediate(ctx)) {
                return false;
            }
            writeImmediateId(ctx);
        } else {
            final long stamp = PolymarketUsStamps.stampMillis(this.sessionSlot, ctx.clientOidCounter);
            ctx.correlationIdLength = PolymarketUsStamps.write(stamp, ctx.correlationIdBytes, 0);
        }
        buildOrderJson(ctx, venuePrice, immediate);
        return true;
    }

    /**
     * Whether the venue would take the price and size as they are: it cuts an off-tick price down to the tick, and a
     * quantity down to the market's lot, rather than refusing them.
     */
    private boolean validTerms(final long price, final long size) {
        return price > 0
                && price < Statics.PRICE_SCALING_FACTOR
                && price % this.marketInfo.tickSize() == 0
                && size > 0
                && size % this.marketInfo.lotSize() == 0;
    }

    @Override
    protected SubmitResult submitOrder(final OrderContext ctx) throws Exception {
        final HTTPResponse response = PolymarketUsRequests.post(
                this.httpClient,
                this.apiHost,
                this.signer,
                epochMillis(),
                PolymarketUsRequests.ORDERS_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength);
        return readSubmitAnswer(response, ctx);
    }

    private SubmitResult readSubmitAnswer(final HTTPResponse response, final OrderContext ctx) {
        if (!response.isSuccess()) {
            final int status = response.getStatusCode();
            logRejection("submit", ctx, status, response);
            final SubmitResult result = classifyFailure(status);
            if (result == SubmitResult.REJECTED) {
                ctx.rejectReason = VenueRejectText.POLYMARKET_US.classify(response.getBody());
            }
            return result;
        }
        // An order the exchange refuses still gets an id, and its rejection comes on the order stream: the reader
        // reports it, as it does everything else that happens to the order.
        if (!readOrderId(response, ctx)) {
            return SubmitResult.UNKNOWN;
        }
        enqueueVenueOrderId(ctx);
        return SubmitResult.ACCEPTED;
    }

    /** Takes the venue's id from {@code {"id":"...","executions":[...]}}. */
    private boolean readOrderId(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        ctx.exchangeOrderIdLength = 0;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("id") && !entry.isNull()) {
                        final var id = entry.asString();
                        ctx.exchangeOrderIdLength = Math.min(id.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
                        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
                            ctx.exchangeOrderIdBytes[i] = id.byteAt(i);
                        }
                    }
                }
            }
        }
        return ctx.exchangeOrderIdLength > 0;
    }

    /**
     * Looks for a resting order among the market's open ones by its stamp. One that isn't there may still be on its
     * way, or may have filled at once, so a miss settles nothing. An IOC can't be looked up.
     */
    @Override
    protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
        if (isImmediate(ctx)) {
            // Asked only after a submit with no clear answer, however it failed.
            recordUnresolvedImmediate(ctx);
            return SubmitResult.UNKNOWN;
        }
        final HTTPResponse response = PolymarketUsRequests.get(
                this.httpClient,
                this.apiHost,
                this.signer,
                this.epochMillisSupplier,
                PolymarketUsRequests.OPEN_ORDERS_PATH + "?slugs=" + this.venueListing.marketSlug());
        if (!response.isSuccess() || response.getBody() == null) {
            return SubmitResult.UNKNOWN;
        }
        final long stamp = PolymarketUsStamps.stampMillis(this.sessionSlot, ctx.clientOidCounter);
        if (!findOpenOrder(response.getBody(), stamp, ctx)) {
            return SubmitResult.UNKNOWN;
        }
        enqueueVenueOrderId(ctx);
        return SubmitResult.ACCEPTED;
    }

    private boolean findOpenOrder(final ByteBuffer body, final long stamp, final OrderContext ctx) {
        boolean found = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("orders") && !entry.isNull()) {
                        found = scanOrders(entry, stamp, ctx);
                    }
                }
            }
        }
        return found;
    }

    private boolean scanOrders(final JsonDecoder.JsonNode orders, final long stamp, final OrderContext ctx) {
        boolean found = false;
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    this.lookupFields.reset();
                    this.lookupFields.readOrder(order);
                    if (!found && this.lookupFields.goodTillMillis == stamp) {
                        ctx.exchangeOrderIdLength = this.lookupFields.orderIdLength;
                        System.arraycopy(
                                this.lookupFields.orderId, 0, ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    @Override
    protected CancelResult cancelOrder(final OrderContext ctx) throws Exception {
        if (ctx.exchangeOrderIdLength == 0 && findOrder(ctx) != SubmitResult.ACCEPTED) {
            // The OMS gets the same answer as for a cancel that never reached the venue.
            throw new IOException("Polymarket US has no order "
                    + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII)
                    + " to cancel");
        }
        buildOrderPath(ctx, PolymarketUsRequests.CANCEL_SUFFIX);
        final HTTPResponse response = PolymarketUsRequests.post(
                this.httpClient,
                this.apiHost,
                this.signer,
                epochMillis(),
                this.orderPath,
                this.cancelBody,
                this.cancelBody.length);
        if (!response.isSuccess()) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return classifyCancelFailure(response);
        }
        return CancelResult.ACCEPTED;
    }

    /**
     * Amends a resting order in place: price, size or both. The size is the order's total, fills included, as the OMS
     * sends it (verified live: 3 with 2 filled, amended to 4, leaves 2). The stamp is sent again, so the order keeps the
     * id it's known by; post-only is sent again too, or a repriced order could cross. The venue answers {@code {}}, so
     * the reader works out what's left from the fills it has seen.
     */
    @Override
    protected void handleModifyOrder() throws Exception {
        final OrderContext ctx = getActiveOrder(this.modifyOrder.getClientOidCounter());
        if (ctx == null) {
            return;
        }
        final long price = this.modifyOrder.decoder.price();
        final long size = this.modifyOrder.decoder.size();
        if (isImmediate(ctx) || !validTerms(price, size)) {
            enqueueCancelReject(ctx, RejectReason.GATEWAY_REJECTED);
            return;
        }
        if (ctx.exchangeOrderIdLength == 0 && findOrder(ctx) != SubmitResult.ACCEPTED) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            return;
        }
        final long venuePrice = this.venueListing.convertPrice(price);
        buildModifyJson(ctx, venuePrice, size, this.modifyOrder.decoder.flags().postOnly());
        buildOrderPath(ctx, PolymarketUsRequests.MODIFY_SUFFIX);
        final HTTPResponse response = PolymarketUsRequests.post(
                this.httpClient,
                this.apiHost,
                this.signer,
                epochMillis(),
                this.orderPath,
                this.jsonBodyBuf,
                this.jsonBodyLength);
        if (!response.isSuccess()) {
            logRejection("modify", ctx, response.getStatusCode(), response);
            enqueueCancelReject(ctx, rejectReasonFor(response, VenueRejectText.POLYMARKET_US));
            return;
        }
        ctx.originalQty = size;
        ctx.limitPrice = venuePrice;
        enqueueAmendAccepted(ctx, size, OrderContext.QTY_ABSENT, OrderContext.QTY_ABSENT);
    }

    /** {@code /v1/order/{venue id}{suffix}}, into the reused path. */
    private void buildOrderPath(final OrderContext ctx, final String suffix) {
        this.orderPath.reset();
        for (int i = 0; i < PolymarketUsRequests.ORDER_PATH.length(); i++) {
            this.orderPath.append((byte) PolymarketUsRequests.ORDER_PATH.charAt(i));
        }
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            this.orderPath.append(ctx.exchangeOrderIdBytes[i]);
        }
        for (int i = 0; i < suffix.length(); i++) {
            this.orderPath.append((byte) suffix.charAt(i));
        }
    }

    private void buildModifyJson(
            final OrderContext ctx, final long venuePrice, final long size, final boolean postOnly) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder
                .writeObjectEntry("marketSlug", this.venueListing.marketSlug())
                .writeComma();
        writePriceAndQuantity(venuePrice, size);
        this.jsonEncoder.writeObjectEntry("tif", "TIME_IN_FORCE_GOOD_TILL_DATE").writeComma();
        writeGoodTillTime(ctx);
        this.jsonEncoder.writeObjectEntry("participateDontInitiate", postOnly);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void writePriceAndQuantity(final long venuePrice, final long size) {
        this.jsonEncoder.writeString("price").writeColon().writeObjectStart();
        this.jsonEncoder.writeString("value").writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        FixedPointAscii.put(this.jsonBodyBuffer, venuePrice, Statics.PRICE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("currency", "USD");
        this.jsonEncoder.writeObjectEnd().writeComma();
        this.jsonEncoder.writeString("quantity").writeColon();
        FixedPointAscii.put(this.jsonBodyBuffer, size, Statics.SIZE_SCALING_FACTOR);
        this.jsonEncoder.writeComma();
    }

    private void writeGoodTillTime(final OrderContext ctx) {
        this.jsonEncoder.writeString("goodTillTime").writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonBodyBuffer.put(ctx.correlationIdBytes, 0, ctx.correlationIdLength);
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonEncoder.writeComma();
    }

    private void buildOrderJson(final OrderContext ctx, final long venuePrice, final boolean immediate) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder
                .writeObjectEntry("marketSlug", this.venueListing.marketSlug())
                .writeComma();
        this.jsonEncoder.writeObjectEntry("type", "ORDER_TYPE_LIMIT").writeComma();
        writePriceAndQuantity(venuePrice, ctx.originalQty);
        if (immediate) {
            this.jsonEncoder
                    .writeObjectEntry("tif", "TIME_IN_FORCE_IMMEDIATE_OR_CANCEL")
                    .writeComma();
        } else {
            this.jsonEncoder
                    .writeObjectEntry("tif", "TIME_IN_FORCE_GOOD_TILL_DATE")
                    .writeComma();
            writeGoodTillTime(ctx);
        }
        this.jsonEncoder
                .writeObjectEntry("intent", PolymarketUsListing.intentName(this.venueListing.intent(ctx.side)))
                .writeComma();
        this.jsonEncoder
                .writeObjectEntry("manualOrderIndicator", "MANUAL_ORDER_INDICATOR_AUTOMATIC")
                .writeComma();
        this.jsonEncoder
                .writeObjectEntry(
                        "participateDontInitiate", this.order.decoder.flags().postOnly())
                .writeComma();
        // Waits for an IOC's outcome, so its answer is final. A resting order would wait up to ten seconds.
        this.jsonEncoder.writeObjectEntry("synchronousExecution", immediate);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    /** {@code ioc-{slot}-{counter}}: never sent, so only unique among this reader's orders. */
    private void writeImmediateId(final OrderContext ctx) {
        final ByteBuffer id = ByteBuffer.wrap(ctx.correlationIdBytes);
        id.put(IMMEDIATE_ID_PREFIX);
        ByteBufferUtils.putLongAscii(id, this.sessionSlot);
        id.put((byte) '-');
        ByteBufferUtils.putLongAscii(id, ctx.clientOidCounter);
        ctx.correlationIdLength = id.position();
    }

    static boolean isImmediate(final OrderContext ctx) {
        if (ctx.correlationIdLength < IMMEDIATE_ID_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < IMMEDIATE_ID_PREFIX.length; i++) {
            if (ctx.correlationIdBytes[i] != IMMEDIATE_ID_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an IOC on this side at this price is still out with its outcome unknown; drops those that have since
     * settled. The reader matches IOCs by side and price, whatever their size (the venue may cut it), so it would have
     * no way to tell the two apart.
     */
    private boolean twinOfUnresolvedImmediate(final OrderContext ctx) {
        boolean twin = false;
        int kept = 0;
        for (int i = 0; i < this.unresolvedImmediateCount; i++) {
            final OrderContext other = getActiveOrder(this.unresolvedImmediate[i]);
            if (other == null) {
                continue;
            }
            this.unresolvedImmediate[kept++] = this.unresolvedImmediate[i];
            twin |= other.side == ctx.side && other.limitPrice == ctx.limitPrice;
        }
        this.unresolvedImmediateCount = kept;
        if (twin || kept == MAX_UNRESOLVED_IMMEDIATE) {
            this.logger.logf(
                    LogMessage.ORDER_REJECTED_BY_VENUE,
                    "Polymarket US: refused an IOC while %d with unknown outcomes are out, one of them identical: %b",
                    kept,
                    twin);
            return true;
        }
        return false;
    }

    private void recordUnresolvedImmediate(final OrderContext ctx) {
        if (this.unresolvedImmediateCount < MAX_UNRESOLVED_IMMEDIATE) {
            this.unresolvedImmediate[this.unresolvedImmediateCount++] = ctx.clientOidCounter;
        }
    }

    /**
     * Logs what the venue said when it refused, which the OMS learns only as a coarse reason. Off the hot path:
     * a refusal allocates. No status and no body is no answer at all, so there is nothing to log.
     */
    private void logRejection(
            final String action, final OrderContext ctx, final int status, final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        // The rate limiter logs its own refusals, once, rather than one line for each.
        if ((status <= 0 && (body == null || !body.hasRemaining())) || response.isRefusedLocally()) {
            return;
        }
        String reason = body == null
                ? ""
                : StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        if (reason.length() > MAX_LOGGED_REASON_CHARS) {
            reason = reason.substring(0, MAX_LOGGED_REASON_CHARS);
        }
        this.logger.logf(
                LogMessage.ORDER_REJECTED_BY_VENUE,
                "Polymarket US refused %s of %s: %d %s",
                action,
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                status,
                reason);
    }

    private long epochMillis() {
        return this.clock.nanoTime() / NANOS_PER_MILLI;
    }

    @Override
    protected void keepConnectionsWarm() {
        this.idleConnections.keepWarm();
    }
}
