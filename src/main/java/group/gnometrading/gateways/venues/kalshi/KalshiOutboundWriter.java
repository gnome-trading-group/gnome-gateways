package group.gnometrading.gateways.venues.kalshi;

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
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.MutableString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundWriter extends OutboundSocketWriter {

    // Kalshi closes a connection after about a minute idle.
    private static final long MAX_IDLE_CONNECTION_NANOS = TimeUnit.SECONDS.toNanos(45);

    private static final String ORDER_PATH = KalshiOrderCodec.ORDER_PATH;
    private static final long LOOKUP_WINDOW_SECONDS = 60L;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Logger logger;
    private final HTTPClient httpClient;
    private final IdleConnections idleConnections;
    private final String apiHost;
    private final KalshiAuthSigner authSigner;
    private final EpochNanoClock clock;
    private final KalshiMarket market;
    private final KalshiOrderCodec codec = new KalshiOrderCodec(KalshiVenue.SELF_TRADE_PREVENTION);

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private int jsonBodyLength;

    private final byte[] clientOrderIdPrefix;
    private final MutableString cancelPath;
    private final KalshiOrderPages orderPages;
    // Kept to sign requests that may be retried; a method reference made per call would allocate.
    private final LongSupplier epochMillisSupplier = this::epochMillis;

    public KalshiOutboundWriter(
            final Logger logger,
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochNanoClock clock,
            final Listing listing,
            final String sessionTag) {
        super(orderOutboundBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        this.logger = logger;
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.idleConnections = new IdleConnections(logger, "Kalshi", httpClient, apiHost, MAX_IDLE_CONNECTION_NANOS);
        this.authSigner = authSigner;
        this.clock = clock;
        this.clientOrderIdPrefix = (sessionTag + "-").getBytes(StandardCharsets.US_ASCII);
        this.market = KalshiMarket.of(listing);
        this.orderPages = new KalshiOrderPages(httpClient, apiHost, authSigner, this.epochMillisSupplier);
        this.cancelPath = new MutableString(KalshiOrderCodec.pathCapacity(this.market));
    }

    /** Reads the market's exchange shard, so every request goes straight to it. */
    @Override
    public void onStart() {
        int exchangeIndex;
        try {
            exchangeIndex = KalshiApiUtil.fetchExchangeIndex(
                    this.httpClient, this.apiHost, this.authSigner, this.epochMillisSupplier, this.market.ticker());
        } catch (final IOException | RuntimeException e) {
            exchangeIndex = KalshiApiUtil.SHARD_UNKNOWN;
        }
        if (exchangeIndex < 0) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Kalshi market %s: exchange shard unknown, orders will route by ticker",
                    this.market.ticker());
        }
        this.market.exchangeIndex(exchangeIndex);
    }

    @Override
    public String roleName() {
        return "kalshi-outbound-writer";
    }

    @Override
    protected boolean prepareOrder(final OrderContext ctx) {
        if (this.order.decoder.orderType() == OrderType.MARKET) {
            // Kalshi needs a price on every order (it dropped market orders); the OMS sends a priced IOC instead.
            return false;
        }
        this.codec.writeClientOrderId(this.clientOrderIdPrefix, ctx);
        ctx.submittedAtMillis = epochMillis();
        this.jsonBodyLength = this.codec.buildOrder(
                this.market,
                this.order.decoder.price(),
                this.order.decoder.size(),
                this.order.decoder.side(),
                this.order.decoder.timeInForce(),
                this.order.decoder.flags().postOnly(),
                ctx,
                this.jsonBodyBuffer);
        return true;
    }

    @Override
    protected SubmitResult submitOrder(final OrderContext ctx) throws Exception {
        this.authSigner.sign(epochMillis(), "POST", ORDER_PATH);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                KalshiOrderCodec.ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                KalshiOrderCodec.HEADER_KEY,
                this.authSigner.apiKey(),
                KalshiOrderCodec.HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                KalshiOrderCodec.HEADER_SIGNATURE,
                this.authSigner.signature(),
                KalshiOrderCodec.HEADER_CONTENT_TYPE,
                KalshiOrderCodec.CONTENT_TYPE_JSON);

        if (!response.isSuccess()) {
            final int status = response.getStatusCode();
            // Kalshi's answer to a client_order_id it already has: expected on a resend, so not a refusal to log.
            if (status == KalshiOrderCodec.HTTP_CONFLICT) {
                if (ctx.submitAttempts == 0) {
                    logRejection("submit", ctx, status, response);
                }
                return SubmitResult.DUPLICATE;
            }
            logRejection("submit", ctx, status, response);
            // A 503 while the shard isn't trading is Kalshi refusing every order, not a lost answer.
            if (status == KalshiOrderCodec.HTTP_SERVICE_UNAVAILABLE && !shardIsTrading()) {
                ctx.rejectReason = RejectReason.MARKET_CLOSED;
                return SubmitResult.REJECTED;
            }
            final SubmitResult result = SubmitPolicy.classifyFailure(status);
            if (result == SubmitResult.REJECTED) {
                ctx.rejectReason = VenueRejectText.KALSHI.classify(response.getBody());
            }
            return result;
        }
        return KalshiOrderCodec.readOrderId(response, ctx) ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
    }

    /** Whether the market's shard is taking orders; true when that can't be told, so an order isn't given up on. */
    private boolean shardIsTrading() {
        try {
            this.authSigner.sign(epochMillis(), "GET", KalshiOrderCodec.EXCHANGE_STATUS_PATH);
            final HTTPResponse response = this.httpClient.get(
                    HTTPProtocol.HTTPS,
                    this.apiHost,
                    KalshiOrderCodec.EXCHANGE_STATUS_PATH,
                    KalshiOrderCodec.HEADER_KEY,
                    this.authSigner.apiKey(),
                    KalshiOrderCodec.HEADER_TIMESTAMP,
                    this.authSigner.timestamp(),
                    KalshiOrderCodec.HEADER_SIGNATURE,
                    this.authSigner.signature());
            // Read whatever the status code: in an outage the status endpoint is itself a 503, saying trading is off.
            if (response.getBody() == null) {
                return true;
            }
            return this.codec.readTradingActive(response.getBody(), this.market.exchangeIndex());
        } catch (final IOException | RuntimeException e) {
            return true;
        }
    }

    /** Logs a refusal with Kalshi's own reason; only on the error path, so building the text is fine. */
    private void logRejection(
            final String action, final OrderContext ctx, final int status, final HTTPResponse response) {
        // The rate limiter logs its own refusals, once, rather than one line for each.
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

    /**
     * Looks for the order among this market's recent orders, every page of them. Kalshi cannot filter by client
     * order id, so the listing is narrowed to the ticker and to orders created since shortly before the order's
     * own submit.
     */
    @Override
    protected LookupResult findOrder(final OrderContext ctx) throws Exception {
        final long minTimestampSeconds = ctx.submittedAtMillis / 1000L - LOOKUP_WINDOW_SECONDS;
        final List<ByteBuffer> pages;
        try {
            pages = this.orderPages.fetch(this.market.ticker(), "&min_ts=" + minTimestampSeconds);
        } catch (final IOException e) {
            return LookupResult.UNKNOWN;
        }
        for (final ByteBuffer page : pages) {
            if (this.codec.findInOrderList(page, ctx)) {
                return LookupResult.FOUND;
            }
        }
        return LookupResult.NOT_FOUND;
    }

    /** Whether the order's Kalshi id is known, looking the order up if it isn't yet. */
    private boolean venueIdKnown(final OrderContext ctx) throws Exception {
        return ctx.exchangeOrderIdLength > 0 || findOrder(ctx) == LookupResult.FOUND;
    }

    @Override
    protected CancelResult cancelOrder(final OrderContext ctx) throws Exception {
        if (!venueIdKnown(ctx)) {
            // The submit's outcome is still unknown and Kalshi lists no such order: nothing can be sent, and the
            // OMS gets the same answer as for a cancel that never reached the venue.
            throw new IOException("Kalshi has no order "
                    + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII)
                    + " to cancel");
        }
        KalshiOrderCodec.orderPath(ctx, this.cancelPath);
        // Kalshi signs the path without its query string.
        this.authSigner.sign(epochMillis(), "DELETE", this.cancelPath);
        KalshiOrderCodec.appendRoutingQuery(this.market, this.cancelPath);

        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                KalshiOrderCodec.HEADER_KEY,
                this.authSigner.apiKey(),
                KalshiOrderCodec.HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                KalshiOrderCodec.HEADER_SIGNATURE,
                this.authSigner.signature());

        if (!response.isSuccess()) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return SubmitPolicy.classifyCancelFailure(response);
        }
        return CancelResult.ACCEPTED;
    }

    @Override
    protected void handleModifyOrder() throws Exception {
        final long clientOidCounter = this.modifyOrder.getClientOidCounter();
        final OrderContext ctx = getActiveOrder(clientOidCounter);
        if (ctx == null) {
            rejectUnknownModify();
            return;
        }
        if (!venueIdKnown(ctx)) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            return;
        }

        KalshiOrderCodec.amendPath(ctx, this.cancelPath);
        this.jsonBodyLength = this.codec.buildAmend(
                this.market,
                this.modifyOrder.decoder.price(),
                this.modifyOrder.decoder.size(),
                ctx.side,
                this.jsonBodyBuffer);
        this.authSigner.sign(epochMillis(), "POST", this.cancelPath);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                KalshiOrderCodec.HEADER_KEY,
                this.authSigner.apiKey(),
                KalshiOrderCodec.HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                KalshiOrderCodec.HEADER_SIGNATURE,
                this.authSigner.signature(),
                KalshiOrderCodec.HEADER_CONTENT_TYPE,
                KalshiOrderCodec.CONTENT_TYPE_JSON);

        if (response.isSuccess()) {
            // Kalshi acknowledges an amend only in this response body, and only the reader knows the
            // order's fills, so hand it the venue's numbers to build the acknowledgement from.
            ctx.originalQty = this.modifyOrder.decoder.size();
            // The amended count is what has filled plus what rests, and remaining_count is what rests after the
            // amend, so the order's fills so far are their difference. The response's own fill_count is only what
            // the amend filled by crossing the book.
            final long remaining = KalshiOrderCodec.readRemainingCount(response);
            enqueueAmendAccepted(
                    ctx,
                    ctx.originalQty,
                    remaining == OrderContext.QTY_ABSENT ? OrderContext.QTY_ABSENT : ctx.originalQty - remaining,
                    remaining);
        } else {
            logRejection("amend", ctx, response.getStatusCode(), response);
            enqueueCancelReject(ctx, SubmitPolicy.rejectReasonFor(response, VenueRejectText.KALSHI));
        }
    }

    private long epochMillis() {
        return this.clock.nanoTime() / NANOS_PER_MILLI;
    }

    @Override
    protected void keepConnectionsWarm() {
        this.idleConnections.keepWarm();
    }
}
