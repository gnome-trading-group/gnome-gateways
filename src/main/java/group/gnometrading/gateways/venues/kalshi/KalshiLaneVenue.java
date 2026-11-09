package group.gnometrading.gateways.venues.kalshi;

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
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.MutableString;
import group.gnometrading.utils.AsciiParsing;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Kalshi for an account's lane: one account's orders on any of its markets, built by {@link KalshiOrderCodec} and
 * signed per request. Client order ids carry the session's tag, which the lane learns when a session registers.
 */
public final class KalshiLaneVenue implements LaneVenue<KalshiMarket> {

    private static final String LOOKUP_PATH = "/trade-api/v2/portfolio/orders";
    private static final long LOOKUP_WINDOW_SECONDS = 60;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final byte[] HEADER_KEY = ascii(KalshiOrderCodec.HEADER_KEY);
    private static final byte[] HEADER_TIMESTAMP = ascii(KalshiOrderCodec.HEADER_TIMESTAMP);
    private static final byte[] HEADER_SIGNATURE = ascii(KalshiOrderCodec.HEADER_SIGNATURE);
    private static final byte[] HEADER_CONTENT_TYPE = ascii(KalshiOrderCodec.HEADER_CONTENT_TYPE);
    private static final byte[] CONTENT_TYPE_JSON = ascii(KalshiOrderCodec.CONTENT_TYPE_JSON);
    private static final byte[] EMPTY_CURSOR = ascii("\"cursor\":\"\"");

    private final Logger logger;
    private final KalshiAuthSigner signer;
    private final KalshiOrderCodec codec;
    private final PublishedMap<KalshiMarket> markets = new PublishedMap<>();
    private final PublishedMap<byte[]> clientIdPrefixes = new PublishedMap<>();
    private final ByteBuffer scratch = ByteBuffer.allocate(2048);
    private final byte[] digits = new byte[20];
    private final ByteBuffer digitsBuffer = ByteBuffer.wrap(this.digits);

    /** @param selfTradePrevention Kalshi's {@code self_trade_prevention_type} for every order */
    public KalshiLaneVenue(final Logger logger, final KalshiAuthSigner signer, final String selfTradePrevention) {
        this.logger = logger;
        this.signer = signer;
        this.codec = new KalshiOrderCodec(selfTradePrevention);
    }

    /** Off the hot path: a market the account may trade, its exchange shard already read. */
    public void addMarket(final int exchangeId, final long securityId, final KalshiMarket market) {
        this.markets.put(PublishedMap.listingKey(exchangeId, securityId), market);
    }

    /**
     * Off the hot path: a listing the account may trade, its exchange shard fetched first, as orders and cancels are
     * routed by it. A shard Kalshi doesn't name leaves the routing to Kalshi.
     */
    public KalshiMarket loadMarket(
            final Listing listing,
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final LongSupplier epochMillis)
            throws IOException {
        final KalshiMarket market = KalshiMarket.of(listing);
        market.exchangeIndex(
                KalshiApiUtil.fetchExchangeIndex(httpClient, apiHost, authSigner, epochMillis, market.ticker()));
        addMarket(listing.exchange().exchangeId(), listing.security().securityId(), market);
        return market;
    }

    /** Off the hot path: a session the account serves; its tag goes into each of its client order ids. */
    public void addSession(final long sessionSlot, final String sessionTag) {
        this.clientIdPrefixes.put(sessionSlot, (sessionTag + "-").getBytes(StandardCharsets.US_ASCII));
    }

    public void removeSession(final long sessionSlot) {
        this.clientIdPrefixes.remove(sessionSlot);
    }

    @Override
    public KalshiMarket market(final int exchangeId, final long securityId) {
        return this.markets.get(PublishedMap.listingKey(exchangeId, securityId));
    }

    @Override
    public int prepare(
            final LaneCommand command,
            final KalshiMarket market,
            final OrderContext ctx,
            final byte[] body,
            final long nowMillis) {
        if (command.orderType == OrderType.MARKET) {
            // Kalshi needs a price on every order (it dropped market orders); the OMS sends a priced IOC instead.
            return -1;
        }
        final byte[] prefix = this.clientIdPrefixes.get(command.sessionSlot);
        if (prefix == null) {
            ctx.rejectReason = RejectReason.SESSION_NOT_REGISTERED;
            return -1;
        }
        this.codec.writeClientOrderId(prefix, ctx);
        ctx.submittedAtMillis = nowMillis;
        final int length = this.codec.buildOrder(
                market,
                command.price,
                command.size,
                command.side,
                command.timeInForce,
                command.postOnly,
                ctx,
                this.scratch);
        if (length > body.length) {
            return -1;
        }
        System.arraycopy(this.scratch.array(), 0, body, 0, length);
        return length;
    }

    @Override
    public boolean resendsAreSafe() {
        return true;
    }

    @Override
    public boolean venueIdKnown(final OrderContext ctx) {
        return ctx.exchangeOrderIdLength > 0;
    }

    @Override
    public boolean canAmend(final OrderContext ctx, final KalshiMarket market, final long price, final long size) {
        return true;
    }

    @Override
    public void submitShape(final OrderContext ctx, final KalshiMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST).appendString(KalshiOrderCodec.ORDER_PATH_GS);
    }

    /** The market's orders since shortly before the submit, first page: Kalshi can't filter by client order id. */
    @Override
    public boolean lookupShape(final OrderContext ctx, final KalshiMarket market, final RequestShape shape) {
        final MutableString path = shape.start(HTTPMethod.GET);
        appendAscii(path, LOOKUP_PATH);
        shape.signedLength = path.length();
        appendAscii(path, "?ticker=");
        appendAscii(path, market.ticker());
        appendAscii(path, "&min_ts=");
        appendNumber(path, ctx.submittedAtMillis / 1000L - LOOKUP_WINDOW_SECONDS);
        appendAscii(path, "&limit=200");
        return true;
    }

    @Override
    public void cancelShape(final OrderContext ctx, final KalshiMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.DELETE);
        KalshiOrderCodec.orderPath(ctx, shape.path);
        // Kalshi signs the path without its routing query.
        shape.signedLength = shape.path.length();
        KalshiOrderCodec.appendRoutingQuery(market, shape.path);
    }

    @Override
    public void amendShape(final OrderContext ctx, final KalshiMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST);
        KalshiOrderCodec.amendPath(ctx, shape.path);
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
            final KalshiMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        request.noBody();
    }

    @Override
    public void writeCancel(
            final OrderContext ctx,
            final KalshiMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        request.noBody();
    }

    @Override
    public void writeAmend(
            final OrderContext ctx,
            final KalshiMarket market,
            final long price,
            final long size,
            final boolean postOnly,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final int length = this.codec.buildAmend(market, price, size, ctx.side, this.scratch);
        sign(shape, nowMillis, request);
        request.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).body(this.scratch.array(), 0, length);
    }

    private void sign(final RequestShape shape, final long nowMillis, final HTTPRequestWriter request) {
        try {
            this.signer.sign(nowMillis, shape.method.name(), shape.path, shape.signed());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        request.header(HEADER_KEY, this.signer.apiKeyBytes())
                .header(HEADER_TIMESTAMP, this.signer.timestampBytes(), 0, this.signer.timestampLength())
                .header(HEADER_SIGNATURE, this.signer.signatureBytes(), 0, this.signer.signatureLength());
    }

    /**
     * A 503 is Kalshi refusing every order, the shard or exchange not trading: nothing was placed, so it is a refusal,
     * not a lost answer. A 409 is its answer to a client order id it already has.
     */
    @Override
    public SubmitResult readSubmit(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            return KalshiOrderCodec.readOrderId(response, ctx) ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
        }
        final int status = response.getStatusCode();
        if (status == KalshiOrderCodec.HTTP_CONFLICT) {
            if (ctx.submitAttempts == 0) {
                logRejection("submit", ctx, status, response);
            }
            return SubmitResult.DUPLICATE;
        }
        logRejection("submit", ctx, status, response);
        if (status == KalshiOrderCodec.HTTP_SERVICE_UNAVAILABLE) {
            ctx.rejectReason = RejectReason.MARKET_CLOSED;
            return SubmitResult.REJECTED;
        }
        final SubmitResult result = SubmitPolicy.classifyFailure(status);
        if (result == SubmitResult.REJECTED) {
            ctx.rejectReason = VenueRejectText.KALSHI.classify(response.getBody());
        }
        return result;
    }

    /** A miss is believed only if the page is the market's last: an order on a later one isn't missing. */
    @Override
    public LookupResult readLookup(final OrderContext ctx, final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        if (!response.isSuccess() || body == null || !body.hasRemaining()) {
            return LookupResult.UNKNOWN;
        }
        final int start = body.position();
        final boolean lastPage = AsciiParsing.indexOf(body, start, body.limit(), EMPTY_CURSOR) >= 0;
        final boolean found = this.codec.findInOrderList(body, ctx);
        body.position(start);
        if (found) {
            return LookupResult.FOUND;
        }
        return lastPage ? LookupResult.NOT_FOUND : LookupResult.UNKNOWN;
    }

    @Override
    public CancelResult readCancel(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            return CancelResult.ACCEPTED;
        }
        logRejection("cancel", ctx, response.getStatusCode(), response);
        return SubmitPolicy.classifyCancelFailure(response);
    }

    /**
     * The amended count is what has filled plus what rests, and {@code remaining_count} is what rests after the
     * amend, so the order's fills so far are their difference.
     */
    @Override
    public RejectReason readAmend(
            final OrderContext ctx, final long newSize, final HTTPResponse response, final AmendAnswer out) {
        if (!response.isSuccess()) {
            logRejection("amend", ctx, response.getStatusCode(), response);
            return SubmitPolicy.rejectReasonFor(response, VenueRejectText.KALSHI);
        }
        final long remaining = KalshiOrderCodec.readRemainingCount(response);
        out.remaining = remaining;
        out.fillCount = remaining == OrderContext.QTY_ABSENT ? OrderContext.QTY_ABSENT : newSize - remaining;
        return null;
    }

    /** Off the hot path: a refusal allocates its log line. */
    private void logRejection(
            final String action, final OrderContext ctx, final int status, final HTTPResponse response) {
        if (status <= 0 || response.isRefusedLocally()) {
            return;
        }
        final ByteBuffer body = response.getBody();
        String reason = body == null
                ? ""
                : StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        if (reason.length() > MAX_LOGGED_REASON_CHARS) {
            reason = reason.substring(0, MAX_LOGGED_REASON_CHARS);
        }
        this.logger.logf(
                LogMessage.ORDER_REJECTED_BY_VENUE,
                "Kalshi refused %s of %s: %d %s",
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

    private void appendNumber(final MutableString path, final long value) {
        this.digitsBuffer.clear();
        ByteBufferUtils.putLongAscii(this.digitsBuffer, value);
        for (int i = 0; i < this.digitsBuffer.position(); i++) {
            path.append(this.digits[i]);
        }
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
