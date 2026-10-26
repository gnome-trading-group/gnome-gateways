package group.gnometrading.gateways.venues.polymarket.intl;

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
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;
import group.gnometrading.utils.ScaledMath;
import java.math.BigInteger;
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

    private static final int HTTP_NOT_FOUND = 404;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final String ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000";

    private static final int BUY_SIDE = 0;
    private static final int SELL_SIDE = 1;

    // Polymarket sizes are in 6-decimal units, matching SIZE_SCALING_FACTOR, and in whole hundredths of a share.
    private static final long SHARE_INCREMENT = Statics.SIZE_SCALING_FACTOR / 100;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    // Dollars are in 6-decimal units too.
    private static final long CENT = Statics.SIZE_SCALING_FACTOR / 100;

    private final Logger logger;
    private final HTTPClient httpClient;
    private final IdleConnections idleConnections;
    private final String clobHost;
    private final PolymarketIntlOrderSigner orderSigner;
    private final PolymarketIntlAuthHeaders authHeaders;
    private final PolymarketIntlMarketInfo marketInfo;
    private final long marketAmountIncrement;
    private final EpochNanoClock clock;
    private final String tokenId;
    private final BigInteger tokenIdBigInt;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private int jsonBodyLength;
    private final SubmitAnswer submitAnswer = new SubmitAnswer();

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
        this.orderSigner = orderSigner;
        this.authHeaders = authHeaders;
        this.marketInfo = marketInfo;
        this.marketAmountIncrement = amountIncrement(marketInfo.tickSize());
        this.clock = clock;
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIndex = exchangeSecurityId.indexOf(':');
        this.tokenId = colonIndex >= 0 ? exchangeSecurityId.substring(colonIndex + 1) : exchangeSecurityId;
        this.tokenIdBigInt = new BigInteger(this.tokenId);
        this.jsonEncoder.wrap(this.jsonBodyBuffer);
    }

    @Override
    public String roleName() {
        return "polymarket-outbound-writer";
    }

    @Override
    protected boolean prepareOrder(final OrderContext ctx) {
        final long price = this.order.decoder.price();
        final long size = this.order.decoder.size();
        final OrderType orderType = this.order.decoder.orderType();
        if (orderType == OrderType.MARKET) {
            // A Polymarket order always carries a limit price; a market order is a FAK at a worst price,
            // which the OMS has to choose. Lot, tick and minimum size are left to the OMS and the venue.
            return false;
        }

        final int pmSide = this.order.decoder.side() == Side.Bid ? BUY_SIDE : SELL_SIDE;
        // Exact: the OMS keeps size to whole lots (hundredths of a share), and every tick is a multiple of 1e-4.
        final long notional = size / SHARE_INCREMENT * price / (Statics.PRICE_SCALING_FACTOR / SHARE_INCREMENT);
        final boolean immediate = isImmediate(this.order.decoder.timeInForce());
        final long makerAmount;
        final long takerAmount;
        if (pmSide == BUY_SIDE && immediate) {
            // The venue reads an IOC or FOK BUY as dollars to spend: whole cents of them, and the shares those buy
            // at the limit to the tick's precision. At a better price it spends them all, buying more shares.
            makerAmount = notional - notional % CENT;
            final long shares = ScaledMath.multiplyDivide(makerAmount, Statics.PRICE_SCALING_FACTOR, price);
            takerAmount = shares - shares % this.marketAmountIncrement;
        } else {
            makerAmount = pmSide == BUY_SIDE ? notional : size;
            takerAmount = pmSide == BUY_SIDE ? size : notional;
        }
        ctx.awaitsFinalFill = immediate;

        final PolymarketIntlOrderSigner.SignedOrder signed = this.orderSigner.signOrder(
                this.tokenIdBigInt,
                makerAmount,
                takerAmount,
                pmSide,
                this.clock.nanoTime() / NANOS_PER_MILLI,
                this.marketInfo.negRisk());

        ctx.submittedAtMillis = this.clock.nanoTime() / NANOS_PER_MILLI;
        // The order hash is the venue's orderID, so it is both the correlation id and the cancel handle.
        ctx.correlationIdLength = signed.writeOrderHashHex(ctx.correlationIdBytes, 0);
        ctx.exchangeOrderIdLength = signed.writeOrderHashHex(ctx.exchangeOrderIdBytes, 0);

        buildOrderJson(
                signed,
                resolveOrderType(this.order.decoder.timeInForce()),
                this.order.decoder.flags().postOnly());
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

        if (!response.isSuccess()) {
            // Polymarket's 503 is trading disabled, cancel-only or post-only mode: a refusal, not a lost answer.
            final int status = response.getStatusCode();
            logRejection("submit", ctx, status, response);
            final SubmitResult result =
                    status == HTTP_SERVICE_UNAVAILABLE ? SubmitResult.REJECTED : classifyFailure(status);
            if (result == SubmitResult.REJECTED) {
                ctx.rejectReason = VenueRejectText.POLYMARKET_INTL.classify(response.getBody());
            }
            return result;
        }
        return readSubmitResponse(response, ctx);
    }

    @Override
    protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
        final String path = PolymarketIntlRequests.ORDER_LOOKUP_PATH
                + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
        final HTTPResponse response =
                PolymarketIntlRequests.get(this.httpClient, this.clobHost, this.authHeaders, path, path);
        if (response.isSuccess()) {
            return SubmitResult.ACCEPTED;
        }
        return response.getStatusCode() == HTTP_NOT_FOUND ? SubmitResult.REJECTED : SubmitResult.UNKNOWN;
    }

    @Override
    protected CancelResult cancelOrder(final OrderContext ctx) throws Exception {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("orderID").writeColon();
        writeQuotedBytes(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();

        final HTTPResponse response = PolymarketIntlRequests.cancel(
                this.httpClient, this.clobHost, this.authHeaders, this.jsonBodyBuf, this.jsonBodyLength);
        final boolean canceled = PolymarketIntlRequests.listsAsCanceled(
                this.jsonDecoder, response, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
        if (!canceled) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return classifyCancelFailure(response);
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

    private void buildOrderJson(
            final PolymarketIntlOrderSigner.SignedOrder signed, final String orderType, final boolean postOnly) {
        this.jsonBodyBuffer.clear();

        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("order").writeColon().writeObjectStart();
        this.jsonEncoder.writeObjectEntry("salt", signed.salt()).writeComma();
        this.jsonEncoder.writeObjectEntry("maker", signed.maker()).writeComma();
        this.jsonEncoder.writeObjectEntry("signer", signed.signer()).writeComma();
        this.jsonEncoder.writeObjectEntry("tokenId", this.tokenId).writeComma();
        writeQuotedLong("makerAmount", signed.makerAmount());
        writeQuotedLong("takerAmount", signed.takerAmount());
        this.jsonEncoder
                .writeObjectEntry("side", signed.side() == BUY_SIDE ? "BUY" : "SELL")
                .writeComma();
        // Kept in the body for GTD handling but no longer part of the signed order.
        this.jsonEncoder.writeObjectEntry("expiration", "0").writeComma();
        this.jsonEncoder
                .writeObjectEntry("signatureType", signed.signatureType())
                .writeComma();
        writeQuotedLong("timestamp", signed.timestampMillis());
        this.jsonEncoder.writeObjectEntry("metadata", ZERO_BYTES32).writeComma();
        this.jsonEncoder.writeObjectEntry("builder", ZERO_BYTES32).writeComma();
        this.jsonEncoder.writeObjectEntry("signature", signed.signatureHex());
        this.jsonEncoder.writeObjectEnd().writeComma();
        this.jsonEncoder.writeObjectEntry("owner", this.authHeaders.apiKey()).writeComma();
        this.jsonEncoder.writeObjectEntry("orderType", orderType).writeComma();
        this.jsonEncoder.writeObjectEntry("deferExec", false).writeComma();
        this.jsonEncoder.writeObjectEntry("postOnly", postOnly);
        this.jsonEncoder.writeObjectEnd();

        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void writeQuotedLong(final String key, final long value) {
        this.jsonEncoder.writeString(key).writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, value);
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonEncoder.writeComma();
    }

    private void writeQuotedBytes(final byte[] bytes, final int length) {
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonBodyBuffer.put(bytes, 0, length);
        this.jsonBodyBuffer.put((byte) '"');
    }

    /**
     * Reads a 2xx submit response. The venue's orderID must be the hash we signed: the reader matches
     * events on that hash, so a different one would leave a live order nothing tracks.
     */
    private SubmitResult readSubmitResponse(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return SubmitResult.UNKNOWN;
        }
        final SubmitAnswer answer = this.submitAnswer;
        answer.reset();
        // Read in place and rewound after, so a refusal can still be logged; a duplicate would allocate.
        final int start = body.position();
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    answer.read(entry, ctx);
                }
            }
        }
        body.position(start);
        if (!answer.sawSuccess) {
            return SubmitResult.UNKNOWN;
        }
        if (!answer.success) {
            logRejection("submit", ctx, response.getStatusCode(), response);
            ctx.rejectReason = VenueRejectText.POLYMARKET_INTL.classify(body);
            return SubmitResult.REJECTED;
        }
        if (answer.sawId && !answer.idMatches) {
            throw new IllegalStateException("Polymarket assigned an orderID that differs from the signed order's hash; "
                    + "the order signer no longer matches the venue");
        }
        if (ctx.awaitsFinalFill) {
            handOverFinalFill(answer, ctx);
        }
        return SubmitResult.ACCEPTED;
    }

    /**
     * Tells the reader what an IOC or FOK matched, in shares: a BUY takes them, a SELL gives them. Absent (a delayed
     * order), the reader is told so, and ends the order at its size or on the venue's cancellation instead.
     */
    private void handOverFinalFill(final SubmitAnswer answer, final OrderContext ctx) {
        enqueueFinalFill(ctx, ctx.side == Side.Bid ? answer.takingAmount : answer.makingAmount);
    }

    /** The fields of a submit's answer that settle it; reused for every submit. */
    private static final class SubmitAnswer {
        boolean sawSuccess;
        boolean success;
        boolean sawId;
        boolean idMatches;
        long makingAmount;
        long takingAmount;

        void reset() {
            this.sawSuccess = false;
            this.success = false;
            this.sawId = false;
            this.idMatches = false;
            this.makingAmount = OrderContext.QTY_ABSENT;
            this.takingAmount = OrderContext.QTY_ABSENT;
        }

        void read(final JsonDecoder.JsonNode entry, final OrderContext ctx) {
            // The decoder reads a null as whatever string comes after it.
            if (entry.isNull()) {
                return;
            }
            final GnomeString name = entry.getName();
            if (name.equals("success")) {
                this.sawSuccess = true;
                this.success = entry.asBoolean();
            } else if (name.equals("orderID")) {
                final GnomeString id = entry.asString();
                this.sawId = id.length() > 0;
                this.idMatches =
                        PolymarketIntlRequests.matches(id, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
            } else if (name.equals("makingAmount")) {
                this.makingAmount = amount(entry.asString());
            } else if (name.equals("takingAmount")) {
                this.takingAmount = amount(entry.asString());
            }
        }

        /** An amount in shares or dollars, both 6-decimal; QTY_ABSENT if the venue left it empty. */
        private static long amount(final GnomeString value) {
            return value.length() == 0 ? OrderContext.QTY_ABSENT : value.toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        }
    }

    private static boolean isImmediate(final TimeInForce tif) {
        return tif == TimeInForce.IMMEDIATE_OR_CANCELED || tif == TimeInForce.FILL_OR_KILL;
    }

    /**
     * The smallest step of an order's second amount (dollars for a limit BUY, shares for a market BUY): two decimals
     * more than the tick's, as Polymarket's client rounds them.
     */
    private static long amountIncrement(final long tickSize) {
        int tickDecimals = 0;
        long scaled = tickSize;
        while (scaled % Statics.PRICE_SCALING_FACTOR != 0) {
            scaled *= 10;
            tickDecimals++;
        }
        long increment = Statics.SIZE_SCALING_FACTOR;
        for (int i = 0; i < tickDecimals + 2; i++) {
            increment /= 10;
        }
        return Math.max(1, increment);
    }

    private static String resolveOrderType(final TimeInForce tif) {
        if (tif == TimeInForce.FILL_OR_KILL) {
            return "FOK";
        }
        if (tif == TimeInForce.IMMEDIATE_OR_CANCELED) {
            return "FAK";
        }
        return "GTC";
    }

    @Override
    protected void keepConnectionsWarm() {
        this.idleConnections.keepWarm();
    }
}
