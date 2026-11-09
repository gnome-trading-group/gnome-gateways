package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.lane.RequestShape;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.strings.MutableString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Polymarket International for an account's lane: one wallet's orders on any of its markets, each signed by the
 * wallet's key when prepared and sent with L2 auth signed per request. Orders are immutable, so there is no amend; the
 * order hash is known before sending and is both the correlation id and the cancel handle.
 */
public final class PolymarketIntlLaneVenue implements LaneVenue<PolymarketIntlMarket> {

    private static final byte[] HEADER_API_KEY = ascii(PolymarketIntlAuthHeaders.API_KEY_HEADER);
    private static final byte[] HEADER_SIGNATURE = ascii(PolymarketIntlAuthHeaders.SIGNATURE_HEADER);
    private static final byte[] HEADER_TIMESTAMP = ascii(PolymarketIntlAuthHeaders.TIMESTAMP_HEADER);
    private static final byte[] HEADER_PASSPHRASE = ascii(PolymarketIntlAuthHeaders.PASSPHRASE_HEADER);
    private static final byte[] HEADER_ADDRESS = ascii(PolymarketIntlAuthHeaders.ADDRESS_HEADER);
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final Logger logger;
    private final PolymarketIntlAuthHeaders auth;
    private final PolymarketIntlOrderCodec codec;
    private final PublishedMap<PolymarketIntlMarket> markets = new PublishedMap<>();
    private final ByteBuffer scratch = ByteBuffer.allocate(2048);

    public PolymarketIntlLaneVenue(
            final Logger logger, final PolymarketIntlOrderSigner orderSigner, final PolymarketIntlAuthHeaders auth) {
        this.logger = logger;
        this.auth = auth;
        this.codec = new PolymarketIntlOrderCodec(orderSigner, auth.apiKey());
    }

    /** Off the hot path: a market the account may trade. */
    public void addMarket(final int exchangeId, final long securityId, final PolymarketIntlMarket market) {
        this.markets.put(PublishedMap.listingKey(exchangeId, securityId), market);
    }

    @Override
    public PolymarketIntlMarket market(final int exchangeId, final long securityId) {
        return this.markets.get(PublishedMap.listingKey(exchangeId, securityId));
    }

    @Override
    public int prepare(
            final LaneCommand command,
            final PolymarketIntlMarket market,
            final OrderContext ctx,
            final byte[] body,
            final long nowMillis) {
        if (command.orderType == OrderType.MARKET) {
            // A Polymarket order always carries a limit price; a market order is a FAK at a worst price, the OMS's.
            return -1;
        }
        final int length = this.codec.buildOrder(
                market,
                command.price,
                command.size,
                command.side,
                command.timeInForce,
                command.postOnly,
                nowMillis,
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
        return true;
    }

    @Override
    public boolean canAmend(
            final OrderContext ctx, final PolymarketIntlMarket market, final long price, final long size) {
        return false;
    }

    @Override
    public void submitShape(final OrderContext ctx, final PolymarketIntlMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.POST).appendString(PolymarketIntlRequests.ORDER_PATH_GS);
    }

    @Override
    public boolean lookupShape(final OrderContext ctx, final PolymarketIntlMarket market, final RequestShape shape) {
        final MutableString path = shape.start(HTTPMethod.GET);
        for (int i = 0; i < PolymarketIntlRequests.ORDER_LOOKUP_PATH.length(); i++) {
            path.append((byte) PolymarketIntlRequests.ORDER_LOOKUP_PATH.charAt(i));
        }
        for (int i = 0; i < ctx.correlationIdLength; i++) {
            path.append(ctx.correlationIdBytes[i]);
        }
        return true;
    }

    @Override
    public void cancelShape(final OrderContext ctx, final PolymarketIntlMarket market, final RequestShape shape) {
        shape.start(HTTPMethod.DELETE).appendString(PolymarketIntlRequests.ORDER_PATH_GS);
    }

    @Override
    public void amendShape(final OrderContext ctx, final PolymarketIntlMarket market, final RequestShape shape) {
        throw new UnsupportedOperationException("Polymarket International orders are immutable");
    }

    @Override
    public void writeSubmit(
            final OrderContext ctx,
            final RequestShape shape,
            final byte[] body,
            final int bodyLength,
            final HTTPRequestWriter request,
            final long nowMillis) {
        // Signed per send for a fresh timestamp; the signed order in the body is unchanged, so a resend carries the
        // same hash and the venue refuses it as a duplicate.
        sign(shape, body, bodyLength, nowMillis, request);
        request.body(body, 0, bodyLength);
    }

    @Override
    public void writeLookup(
            final OrderContext ctx,
            final PolymarketIntlMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, null, 0, nowMillis, request);
        request.noBody();
    }

    @Override
    public void writeCancel(
            final OrderContext ctx,
            final PolymarketIntlMarket market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final int length = this.codec.buildCancel(ctx, this.scratch);
        sign(shape, this.scratch.array(), length, nowMillis, request);
        request.body(this.scratch.array(), 0, length);
    }

    @Override
    public void writeAmend(
            final OrderContext ctx,
            final PolymarketIntlMarket market,
            final long price,
            final long size,
            final boolean postOnly,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        throw new UnsupportedOperationException("Polymarket International orders are immutable");
    }

    private void sign(
            final RequestShape shape,
            final byte[] body,
            final int bodyLength,
            final long nowMillis,
            final HTTPRequestWriter request) {
        this.auth.sign(
                nowMillis / MILLIS_PER_SECOND, shape.method.name(), shape.path, shape.signed(), body, 0, bodyLength);
        request.header(HEADER_API_KEY, this.auth.apiKeyBytes())
                .header(HEADER_SIGNATURE, this.auth.signatureBytes(), 0, this.auth.signatureLength())
                .header(HEADER_TIMESTAMP, this.auth.timestampBytes(), 0, this.auth.timestampLength())
                .header(HEADER_PASSPHRASE, this.auth.passphraseBytes())
                .header(HEADER_ADDRESS, this.auth.addressBytes());
    }

    @Override
    public SubmitResult readSubmit(final OrderContext ctx, final HTTPResponse response) {
        final int status = response.getStatusCode();
        final SubmitResult result = this.codec.readSubmitAnswer(response, status, ctx);
        // A duplicate is the venue's answer to a resend whose first send landed: expected, so not a refusal to log.
        final boolean expected = result == SubmitResult.DUPLICATE && ctx.submitAttempts > 0;
        if (!expected && (!response.isSuccess() || result == SubmitResult.REJECTED)) {
            logRejection("submit", ctx, status, response);
        }
        return result;
    }

    @Override
    public long finalFillQty(final OrderContext ctx) {
        return this.codec.finalFillQty(ctx);
    }

    @Override
    public LookupResult readLookup(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            return LookupResult.FOUND;
        }
        return response.getStatusCode() == PolymarketIntlOrderCodec.HTTP_NOT_FOUND
                ? LookupResult.NOT_FOUND
                : LookupResult.UNKNOWN;
    }

    @Override
    public CancelResult readCancel(final OrderContext ctx, final HTTPResponse response) {
        if (this.codec.readCanceled(response, ctx)) {
            return CancelResult.ACCEPTED;
        }
        logRejection("cancel", ctx, response.getStatusCode(), response);
        return SubmitPolicy.classifyCancelFailure(response);
    }

    @Override
    public RejectReason readAmend(
            final OrderContext ctx, final long newSize, final HTTPResponse response, final AmendAnswer out) {
        throw new UnsupportedOperationException("Polymarket International orders are immutable");
    }

    /** Off the hot path: a refusal allocates its log line. */
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
                "Polymarket refused %s of %s: %d %s",
                action,
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                status,
                reason);
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
