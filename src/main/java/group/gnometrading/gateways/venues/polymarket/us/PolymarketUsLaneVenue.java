package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.lane.RequestShape;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.strings.MutableString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Polymarket US for an account's lane: one key's orders on any of its markets. The venue has no client order id and
 * doesn't deduplicate, so a submit is never resent, and a resting order goes good-till-date, its expiry a
 * {@link PolymarketUsStamps stamp} naming the session's slot and counter. An IOC can't carry one: until its id is
 * known the reader matches it by listing, side and price, so while one IOC is held (sent, or with its outcome
 * unknown), another on the same listing, side and price is refused, from any session: the two would be
 * indistinguishable. A FOK is refused: the venue runs it as an IOC.
 */
public final class PolymarketUsLaneVenue implements LaneVenue<PolymarketUsMarket> {

    // IOCs held at once; more than this and every IOC is refused until some end. They end within a round trip, unless
    // a lost answer left their outcome unknown.
    private static final int MAX_HELD_IMMEDIATE = 64;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final byte[] HEADER_ACCESS_KEY = ascii(PolymarketUsAuthSigner.ACCESS_KEY_HEADER);
    private static final byte[] HEADER_TIMESTAMP = ascii(PolymarketUsAuthSigner.TIMESTAMP_HEADER);
    private static final byte[] HEADER_SIGNATURE = ascii(PolymarketUsAuthSigner.SIGNATURE_HEADER);
    private static final byte[] HEADER_CONTENT_TYPE = ascii("Content-Type");
    private static final byte[] CONTENT_TYPE_JSON = ascii("application/json");
    private static final String OPEN_ORDERS_QUERY = "?slugs=";
    private static final byte[] BATCH_START = ascii("{\"orders\":[");
    private static final byte[] BATCH_END = ascii("]}");
    private static final byte[] CANCEL_ORDER_ID = ascii("{\"orderId\":\"");
    private static final byte[] CANCEL_MARKET_SLUG = ascii("\",\"marketSlug\":\"");
    private static final byte[] CANCEL_END = ascii("\"}");

    private final Logger logger;
    private final PolymarketUsAuthSigner signer;
    private final PolymarketUsOrderCodec codec = new PolymarketUsOrderCodec();
    private final PublishedMap<PolymarketUsMarket> markets = new PublishedMap<>();
    private final ByteBuffer scratch = ByteBuffer.allocate(2048);
    // A batch's body: its entries' bodies, each at most a single submit's.
    private final byte[] batchBody = new byte[PolymarketUsRequests.MAX_BATCH * 2048 + 64];

    // The held IOCs, by session slot and counter, and their listing, side and venue price.
    private final long[] heldSlot = new long[MAX_HELD_IMMEDIATE];
    private final long[] heldCounter = new long[MAX_HELD_IMMEDIATE];
    private final long[] heldListing = new long[MAX_HELD_IMMEDIATE];
    private final Side[] heldSide = new Side[MAX_HELD_IMMEDIATE];
    private final long[] heldPrice = new long[MAX_HELD_IMMEDIATE];
    private int heldCount;

    public PolymarketUsLaneVenue(final Logger logger, final PolymarketUsAuthSigner signer) {
        this.logger = logger;
        this.signer = signer;
    }

    /** Off the hot path: a market the account may trade. */
    public void addMarket(final int exchangeId, final long securityId, final PolymarketUsMarket market) {
        this.markets.put(PublishedMap.listingKey(exchangeId, securityId), market);
    }

    @Override
    public PolymarketUsMarket market(final int exchangeId, final long securityId) {
        return this.markets.get(PublishedMap.listingKey(exchangeId, securityId));
    }

    @Override
    public int prepare(
            final LaneCommand command,
            final PolymarketUsMarket market,
            final OrderContext ctx,
            final byte[] body,
            final long nowMillis) {
        if (command.orderType == OrderType.MARKET
                // The venue takes a FOK as an IOC (verified live), which can fill in part.
                || command.timeInForce == TimeInForce.FILL_OR_KILL
                || !market.validTerms(command.price, command.size)) {
            return -1;
        }
        if (!PolymarketUsStamps.counterFits(command.clientOidCounter)) {
            // Past the range a stamp would repeat the session's first; the session must restart under a new slot.
            ctx.rejectReason = RejectReason.SESSION_CAP;
            return -1;
        }
        final int slot = PolymarketUsStamps.slot(command.sessionSlot);
        final long venuePrice = market.listing().convertPrice(command.price);
        ctx.limitPrice = venuePrice;
        ctx.submittedAtMillis = nowMillis;
        final boolean immediate = command.timeInForce == TimeInForce.IMMEDIATE_OR_CANCELED;
        if (immediate) {
            if (twinOfHeldImmediate(ctx)) {
                return -1;
            }
            PolymarketUsOrderCodec.writeImmediateId(slot, ctx);
        } else {
            PolymarketUsOrderCodec.writeStampId(slot, ctx);
        }
        final int length = this.codec.buildOrder(market, ctx, venuePrice, ctx.side, command.postOnly, this.scratch);
        if (length > body.length) {
            return -1;
        }
        System.arraycopy(this.scratch.array(), 0, body, 0, length);
        if (immediate) {
            holdImmediate(ctx);
        }
        return length;
    }

    @Override
    public boolean resendsAreSafe() {
        return false;
    }

    @Override
    public boolean venueIdKnown(final OrderContext ctx) {
        return ctx.exchangeOrderIdLength > 0;
    }

    /** A resting order is amended in place, keeping its stamp; an IOC can't be. */
    @Override
    public boolean canAmend(
            final OrderContext ctx, final PolymarketUsMarket market, final long price, final long size) {
        return !PolymarketUsOrderCodec.isImmediate(ctx) && market.validTerms(price, size);
    }

    /** An IOC carries no stamp, so nothing names it in the market's open orders. */
    @Override
    public boolean canLookUp(final OrderContext ctx) {
        return !PolymarketUsOrderCodec.isImmediate(ctx);
    }

    @Override
    public boolean readerNeedsVenueId() {
        return true;
    }

    @Override
    public void submitShape(final OrderContext ctx, final PolymarketUsMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST).appendString(PolymarketUsRequests.ORDERS_PATH_GS);
    }

    /** The market's open orders, searched by stamp. An IOC can't be looked up: it is left to the venue's events. */
    @Override
    public boolean lookupShape(final OrderContext ctx, final PolymarketUsMarket market, final RequestShape shape) {
        if (PolymarketUsOrderCodec.isImmediate(ctx)) {
            return false;
        }
        final MutableString path = shape.start(HTTPMethod.GET);
        appendAscii(path, PolymarketUsRequests.OPEN_ORDERS_PATH);
        // The venue verifies the path without its query.
        shape.signedLength = path.length();
        appendAscii(path, OPEN_ORDERS_QUERY);
        appendAscii(path, market.marketSlug());
        return true;
    }

    @Override
    public void cancelShape(final OrderContext ctx, final PolymarketUsMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST);
        PolymarketUsOrderCodec.orderPath(ctx, PolymarketUsRequests.CANCEL_SUFFIX, shape.path);
    }

    @Override
    public void amendShape(final OrderContext ctx, final PolymarketUsMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST);
        PolymarketUsOrderCodec.orderPath(ctx, PolymarketUsRequests.MODIFY_SUFFIX, shape.path);
    }

    @Override
    public void writeSubmit(
            final OrderContext ctx,
            final RequestShape shape,
            final byte[] body,
            final int bodyLength,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(body, 0, bodyLength);
    }

    @Override
    public void writeLookup(
            final OrderContext ctx,
            final PolymarketUsMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        request.noBody();
    }

    @Override
    public void writeCancel(
            final OrderContext ctx,
            final PolymarketUsMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        final byte[] body = market.cancelBody();
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(body, 0, body.length);
    }

    /**
     * The size is the order's total, fills included, as the OMS sends it (verified live: 3 with 2 filled, amended to
     * 4, leaves 2). Post-only is sent again, or a repriced order could cross.
     */
    @Override
    public void writeAmend(
            final OrderContext ctx,
            final PolymarketUsMarket market,
            final long price,
            final long size,
            final boolean postOnly,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final long venuePrice = market.listing().convertPrice(price);
        final int length = this.codec.buildModify(market, ctx, venuePrice, size, postOnly, this.scratch);
        sign(shape, nowMillis, request);
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(this.scratch.array(), 0, length);
    }

    private void sign(final RequestShape shape, final long nowMillis, final HTTPRequestWriter request) {
        try {
            this.signer.sign(nowMillis, shape.method.name(), shape.path, shape.signed());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        request.header(HEADER_ACCESS_KEY, this.signer.apiKeyBytes())
                .header(HEADER_TIMESTAMP, this.signer.timestampBytes(), 0, this.signer.timestampLength())
                .header(HEADER_SIGNATURE, this.signer.signatureBytes(), 0, this.signer.signatureLength());
    }

    @Override
    public SubmitResult readSubmit(final OrderContext ctx, final HTTPResponse response) {
        final int status = response.getStatusCode();
        if (!response.isSuccess()) {
            logRejection("submit", ctx, status, response);
        }
        return this.codec.readSubmitAnswer(response, status, ctx);
    }

    /** A miss settles nothing: the order may still be on its way, or may have filled at once. */
    @Override
    public LookupResult readLookup(final OrderContext ctx, final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        if (!response.isSuccess() || body == null) {
            return LookupResult.UNKNOWN;
        }
        final long stamp =
                PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(ctx.sessionSlot), ctx.clientOidCounter);
        return this.codec.findOpenOrder(body, stamp, ctx) ? LookupResult.FOUND : LookupResult.UNKNOWN;
    }

    @Override
    public CancelResult readCancel(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            return CancelResult.ACCEPTED;
        }
        logRejection("cancel", ctx, response.getStatusCode(), response);
        return SubmitPolicy.classifyCancelFailure(response);
    }

    /** The venue answers {@code {}}, so the reader works out what's left from the fills it has seen. */
    @Override
    public RejectReason readAmend(
            final OrderContext ctx, final long newSize, final HTTPResponse response, final AmendAnswer out) {
        if (!response.isSuccess()) {
            logRejection("modify", ctx, response.getStatusCode(), response);
            return SubmitPolicy.rejectReasonFor(response, VenueRejectText.POLYMARKET_US);
        }
        return null;
    }

    // ----- batches -----

    @Override
    public int maxBatch() {
        return PolymarketUsRequests.MAX_BATCH;
    }

    @Override
    public void batchSubmitShape(final RequestShape shape) {
        shape.start(HTTPMethod.POST).appendString(PolymarketUsRequests.BATCH_ORDERS_PATH_GS);
    }

    /** {@code {"orders":[...]}}, each entry the body a single create would send. */
    @Override
    public void writeBatchSubmit(
            final OrderContext[] ctxs,
            final byte[][] bodies,
            final int[] bodyLengths,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        int length = put(BATCH_START, 0);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                this.batchBody[length++] = ',';
            }
            System.arraycopy(bodies[i], 0, this.batchBody, length, bodyLengths[i]);
            length += bodyLengths[i];
        }
        length = put(BATCH_END, length);
        sign(shape, nowMillis, request);
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(this.batchBody, 0, length);
    }

    /**
     * Ids only, in request order: whether the exchange took each entry comes on the order stream, as for a single
     * create, so an entry with an id counts as accepted until then.
     */
    @Override
    public int readBatchSubmit(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final SubmitResult[] results) {
        final int status = response.getStatusCode();
        if (response.isSuccess()) {
            this.codec.readCreatedOrderIds(response.getBody(), ctxs, count);
            for (int i = 0; i < count; i++) {
                results[i] = ctxs[i].exchangeOrderIdLength > 0 ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
            }
            return NO_FAILED_ENTRY;
        }
        logRejection("a batch of " + count + " creates led by", ctxs[0], status, response);
        final int failed = failedEntry(response, count);
        if (failed != NO_FAILED_ENTRY) {
            ctxs[failed].rejectReason = VenueRejectText.POLYMARKET_US.classify(response.getBody());
            return failed;
        }
        final SubmitResult result = SubmitPolicy.classifyFailure(status);
        for (int i = 0; i < count; i++) {
            results[i] = result;
            if (result == SubmitResult.REJECTED) {
                ctxs[i].rejectReason = VenueRejectText.POLYMARKET_US.classify(response.getBody());
            }
        }
        return NO_FAILED_ENTRY;
    }

    @Override
    public void batchCancelShape(final RequestShape shape) {
        shape.start(HTTPMethod.POST).appendString(PolymarketUsRequests.BATCH_CANCEL_PATH_GS);
    }

    /** {@code {"orders":[{"orderId":"...","marketSlug":"..."},...]}}. */
    @Override
    public void writeBatchCancel(
            final OrderContext[] ctxs,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        int length = put(BATCH_START, 0);
        for (int i = 0; i < count; i++) {
            final OrderContext ctx = ctxs[i];
            if (i > 0) {
                this.batchBody[length++] = ',';
            }
            length = put(CANCEL_ORDER_ID, length);
            System.arraycopy(ctx.exchangeOrderIdBytes, 0, this.batchBody, length, ctx.exchangeOrderIdLength);
            length += ctx.exchangeOrderIdLength;
            length = put(CANCEL_MARKET_SLUG, length);
            final String slug = market(ctx.exchangeId, ctx.securityId).marketSlug();
            for (int c = 0; c < slug.length(); c++) {
                this.batchBody[length++] = (byte) slug.charAt(c);
            }
            length = put(CANCEL_END, length);
        }
        length = put(BATCH_END, length);
        sign(shape, nowMillis, request);
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(this.batchBody, 0, length);
    }

    /**
     * The answer only echoes the ids sent, and the exchange ignores one it doesn't know: whether each order ended
     * comes on the order stream, as for a single cancel.
     */
    @Override
    public int readBatchCancel(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final CancelResult[] results) {
        if (response.isSuccess()) {
            for (int i = 0; i < count; i++) {
                results[i] = CancelResult.ACCEPTED;
            }
            return NO_FAILED_ENTRY;
        }
        logRejection("a batch of " + count + " cancels led by", ctxs[0], response.getStatusCode(), response);
        final int failed = failedEntry(response, count);
        if (failed != NO_FAILED_ENTRY) {
            return failed;
        }
        final CancelResult result = SubmitPolicy.classifyCancelFailure(response);
        for (int i = 0; i < count; i++) {
            results[i] = result;
        }
        return NO_FAILED_ENTRY;
    }

    /** The entry a 400 names as the one the whole batch was refused for, if it names one in the batch. */
    private static int failedEntry(final HTTPResponse response, final int count) {
        if (response.getStatusCode() != 400) {
            return NO_FAILED_ENTRY;
        }
        final int failed = PolymarketUsOrderCodec.failedEntry(response.getBody());
        return failed >= 0 && failed < count ? failed : NO_FAILED_ENTRY;
    }

    private int put(final byte[] text, final int at) {
        System.arraycopy(text, 0, this.batchBody, at, text.length);
        return at + text.length;
    }

    @Override
    public void onReleased(final OrderContext ctx) {
        int kept = 0;
        for (int i = 0; i < this.heldCount; i++) {
            if (this.heldSlot[i] == ctx.sessionSlot && this.heldCounter[i] == ctx.clientOidCounter) {
                continue;
            }
            this.heldSlot[kept] = this.heldSlot[i];
            this.heldCounter[kept] = this.heldCounter[i];
            this.heldListing[kept] = this.heldListing[i];
            this.heldSide[kept] = this.heldSide[i];
            this.heldPrice[kept] = this.heldPrice[i];
            kept++;
        }
        this.heldCount = kept;
    }

    private boolean twinOfHeldImmediate(final OrderContext ctx) {
        final long listing = PublishedMap.listingKey(ctx.exchangeId, ctx.securityId);
        boolean twin = false;
        for (int i = 0; i < this.heldCount; i++) {
            twin |= this.heldListing[i] == listing
                    && this.heldSide[i] == ctx.side
                    && this.heldPrice[i] == ctx.limitPrice;
        }
        if (twin || this.heldCount == MAX_HELD_IMMEDIATE) {
            this.logger.logf(
                    LogMessage.ORDER_REJECTED_BY_VENUE,
                    "Polymarket US: refused an IOC while %d are held, one with the same terms: %b",
                    this.heldCount,
                    twin);
            return true;
        }
        return false;
    }

    private void holdImmediate(final OrderContext ctx) {
        this.heldSlot[this.heldCount] = ctx.sessionSlot;
        this.heldCounter[this.heldCount] = ctx.clientOidCounter;
        this.heldListing[this.heldCount] = PublishedMap.listingKey(ctx.exchangeId, ctx.securityId);
        this.heldSide[this.heldCount] = ctx.side;
        this.heldPrice[this.heldCount] = ctx.limitPrice;
        this.heldCount++;
    }

    /** Off the hot path: a refusal allocates its log line. No status and no body is no answer at all. */
    private void logRejection(
            final String action, final OrderContext ctx, final int status, final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
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

    private static void appendAscii(final MutableString path, final String text) {
        for (int i = 0; i < text.length(); i++) {
            path.append((byte) text.charAt(i));
        }
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
