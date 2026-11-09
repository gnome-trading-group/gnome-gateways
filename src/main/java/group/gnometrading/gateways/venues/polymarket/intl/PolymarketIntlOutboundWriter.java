package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.outbound.IdleConnections;
import group.gnometrading.gateways.outbound.OutboundSocketWriter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Order entry for Polymarket's international CLOB (V2).
 *
 * <p>Polymarket orders are signed and immutable, so there is no modify: the OMS cancels and resubmits
 * on this venue, and the base class refuses any modify that arrives.
 */
public final class PolymarketIntlOutboundWriter extends OutboundSocketWriter {

    // Through Cloudflare a connection still answered after two minutes idle and was silently dead by five.
    private static final long MAX_IDLE_CONNECTION_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Logger logger;
    private final HTTPClient httpClient;
    private final IdleConnections idleConnections;
    private final String clobHost;
    private final PolymarketIntlAuthHeaders authHeaders;
    private final PolymarketIntlMarket market;
    private final PolymarketIntlOrderCodec codec;
    private final EpochNanoClock clock;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private int jsonBodyLength;

    public PolymarketIntlOutboundWriter(
            Logger logger,
            SequencedRingBuffer<?> orderOutboundBuffer,
            ManyToOneRingBuffer<OrderContext> newOrderQueue,
            ManyToOneRingBuffer<OrderContext> writerReportQueue,
            ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            HTTPClient httpClient,
            String clobHost,
            PolymarketIntlOrderSigner orderSigner,
            PolymarketIntlAuthHeaders authHeaders,
            PolymarketIntlMarketInfo marketInfo,
            EpochNanoClock clock,
            Listing listing) {
        super(orderOutboundBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        this.logger = logger;
        this.httpClient = httpClient;
        this.clobHost = clobHost;
        this.idleConnections = new IdleConnections(
                logger, "Polymarket International", httpClient, clobHost, MAX_IDLE_CONNECTION_NANOS);
        this.authHeaders = authHeaders;
        this.market = PolymarketIntlMarket.of(listing, marketInfo);
        this.codec = new PolymarketIntlOrderCodec(orderSigner, authHeaders.apiKey());
        this.clock = clock;
    }

    @Override
    public String roleName() {
        return "polymarket-outbound-writer";
    }

    @Override
    protected boolean prepareOrder(final OrderContext ctx) {
        if (this.order.decoder.orderType() == OrderType.MARKET) {
            // A Polymarket order always carries a limit price; a market order is a FAK at a worst price,
            // which the OMS has to choose. Lot, tick and minimum size are left to the OMS and the venue.
            return false;
        }
        this.jsonBodyLength = this.codec.buildOrder(
                this.market,
                this.order.decoder.price(),
                this.order.decoder.size(),
                this.order.decoder.side(),
                this.order.decoder.timeInForce(),
                this.order.decoder.flags().postOnly(),
                this.clock.nanoTime() / NANOS_PER_MILLI,
                ctx,
                this.jsonBodyBuffer);
        return true;
    }

    @Override
    protected SubmitResult submitOrder(final OrderContext ctx) throws Exception {
        // Re-signed per send for a fresh timestamp; the signed order in the body is unchanged, so a
        // resend carries the same hash and the venue refuses it as a duplicate.
        this.authHeaders.sign("POST", PolymarketIntlRequests.ORDER_PATH, this.jsonBodyBuf, 0, this.jsonBodyLength);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.clobHost,
                PolymarketIntlRequests.ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                PolymarketIntlAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketIntlAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketIntlAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketIntlAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketIntlAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());

        final int status = response.getStatusCode();
        final SubmitResult result = this.codec.readSubmitAnswer(response, status, ctx);
        // A duplicate is the venue's answer to a resend whose first send landed: expected, so not a refusal to log.
        final boolean expected = result == SubmitResult.DUPLICATE && ctx.submitAttempts > 0;
        if (!expected && (!response.isSuccess() || result == SubmitResult.REJECTED)) {
            logRejection("submit", ctx, status, response);
        }
        if (result == SubmitResult.ACCEPTED && ctx.awaitsFinalFill) {
            // What an IOC or FOK matched; absent (a delayed order), the reader ends it at its size or on the venue's
            // cancellation instead.
            enqueueFinalFill(ctx, this.codec.finalFillQty(ctx));
        }
        return result;
    }

    @Override
    protected LookupResult findOrder(final OrderContext ctx) throws Exception {
        final String path = PolymarketIntlRequests.ORDER_LOOKUP_PATH
                + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
        final HTTPResponse response =
                PolymarketIntlRequests.get(this.httpClient, this.clobHost, this.authHeaders, path, path);
        if (response.isSuccess()) {
            return LookupResult.FOUND;
        }
        return response.getStatusCode() == PolymarketIntlOrderCodec.HTTP_NOT_FOUND
                ? LookupResult.NOT_FOUND
                : LookupResult.UNKNOWN;
    }

    @Override
    protected CancelResult cancelOrder(final OrderContext ctx) throws Exception {
        this.jsonBodyLength = this.codec.buildCancel(ctx, this.jsonBodyBuffer);

        final HTTPResponse response = PolymarketIntlRequests.cancel(
                this.httpClient, this.clobHost, this.authHeaders, this.jsonBodyBuf, this.jsonBodyLength);
        final boolean canceled = this.codec.readCanceled(response, ctx);
        if (!canceled) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return SubmitPolicy.classifyCancelFailure(response);
        }
        return CancelResult.ACCEPTED;
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
                "Polymarket refused %s of %s: %d %s",
                action,
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                status,
                reason);
    }

    @Override
    protected void keepConnectionsWarm() {
        this.idleConnections.keepWarm();
    }
}
