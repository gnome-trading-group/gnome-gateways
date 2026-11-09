package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.gateways.outbound.IdleConnections;
import group.gnometrading.gateways.outbound.OutboundSocketWriter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.MutableString;
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

    private final Logger logger;
    private final HTTPClient httpClient;
    private final IdleConnections idleConnections;
    private final String apiHost;
    private final PolymarketUsAuthSigner signer;
    // Kept to sign requests that may be retried; a method reference made per call would allocate.
    private final LongSupplier epochMillisSupplier = this::epochMillis;
    private final PolymarketUsMarket market;
    private final PolymarketUsOrderCodec codec = new PolymarketUsOrderCodec();
    private final EpochNanoClock clock;
    private final int sessionSlot;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private int jsonBodyLength;
    private final MutableString orderPath = new MutableString(PolymarketUsOrderCodec.MAX_PATH_LENGTH);

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
        this.market = new PolymarketUsMarket(listing, marketInfo);
        this.clock = clock;
        this.sessionSlot = PolymarketUsStamps.slot(sessionTag);
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
        if (!this.market.validTerms(price, this.order.decoder.size())) {
            return false;
        }
        final long venuePrice = this.market.listing().convertPrice(price);
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
            PolymarketUsOrderCodec.writeImmediateId(this.sessionSlot, ctx);
        } else {
            PolymarketUsOrderCodec.writeStampId(this.sessionSlot, ctx);
        }
        this.jsonBodyLength = this.codec.buildOrder(
                this.market,
                ctx,
                venuePrice,
                ctx.side,
                this.order.decoder.flags().postOnly(),
                this.jsonBodyBuffer);
        return true;
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
        final int status = response.getStatusCode();
        if (!response.isSuccess()) {
            logRejection("submit", ctx, status, response);
        }
        final SubmitResult result = this.codec.readSubmitAnswer(response, status, ctx);
        if (result == SubmitResult.ACCEPTED) {
            enqueueVenueOrderId(ctx);
        }
        return result;
    }

    /**
     * Looks for a resting order among the market's open ones by its stamp. One that isn't there may still be on its
     * way, or may have filled at once, so a miss settles nothing. An IOC can't be looked up.
     */
    @Override
    protected LookupResult findOrder(final OrderContext ctx) throws Exception {
        if (PolymarketUsOrderCodec.isImmediate(ctx)) {
            // Asked only after a submit with no clear answer, however it failed.
            recordUnresolvedImmediate(ctx);
            return LookupResult.UNKNOWN;
        }
        final HTTPResponse response = PolymarketUsRequests.get(
                this.httpClient,
                this.apiHost,
                this.signer,
                this.epochMillisSupplier,
                PolymarketUsRequests.OPEN_ORDERS_PATH + "?slugs=" + this.market.marketSlug());
        if (!response.isSuccess() || response.getBody() == null) {
            return LookupResult.UNKNOWN;
        }
        final long stamp = PolymarketUsStamps.stampMillis(this.sessionSlot, ctx.clientOidCounter);
        if (!this.codec.findOpenOrder(response.getBody(), stamp, ctx)) {
            return LookupResult.UNKNOWN;
        }
        enqueueVenueOrderId(ctx);
        return LookupResult.FOUND;
    }

    @Override
    protected CancelResult cancelOrder(final OrderContext ctx) throws Exception {
        if (ctx.exchangeOrderIdLength == 0 && findOrder(ctx) != LookupResult.FOUND) {
            // The OMS gets the same answer as for a cancel that never reached the venue.
            throw new IOException("Polymarket US has no order "
                    + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII)
                    + " to cancel");
        }
        PolymarketUsOrderCodec.orderPath(ctx, PolymarketUsRequests.CANCEL_SUFFIX, this.orderPath);
        final HTTPResponse response = PolymarketUsRequests.post(
                this.httpClient,
                this.apiHost,
                this.signer,
                epochMillis(),
                this.orderPath,
                this.market.cancelBody(),
                this.market.cancelBody().length);
        if (!response.isSuccess()) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return SubmitPolicy.classifyCancelFailure(response);
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
            rejectUnknownModify();
            return;
        }
        final long price = this.modifyOrder.decoder.price();
        final long size = this.modifyOrder.decoder.size();
        if (PolymarketUsOrderCodec.isImmediate(ctx) || !this.market.validTerms(price, size)) {
            enqueueCancelReject(ctx, RejectReason.GATEWAY_REJECTED);
            return;
        }
        if (ctx.exchangeOrderIdLength == 0 && findOrder(ctx) != LookupResult.FOUND) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            return;
        }
        final long venuePrice = this.market.listing().convertPrice(price);
        this.jsonBodyLength = this.codec.buildModify(
                this.market,
                ctx,
                venuePrice,
                size,
                this.modifyOrder.decoder.flags().postOnly(),
                this.jsonBodyBuffer);
        PolymarketUsOrderCodec.orderPath(ctx, PolymarketUsRequests.MODIFY_SUFFIX, this.orderPath);
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
            enqueueCancelReject(ctx, SubmitPolicy.rejectReasonFor(response, VenueRejectText.POLYMARKET_US));
            return;
        }
        ctx.originalQty = size;
        ctx.limitPrice = venuePrice;
        enqueueAmendAccepted(ctx, size, OrderContext.QTY_ABSENT, OrderContext.QTY_ABSENT);
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
