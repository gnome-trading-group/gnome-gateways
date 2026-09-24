package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundSocketWriter;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundWriter extends OutboundSocketWriter {

    private static final String ORDER_PATH = "/trade-api/v2/portfolio/events/orders";
    private static final ViewString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    private static final String AMEND_SUFFIX = "/amend";
    private static final byte[] AMEND_SUFFIX_BYTES = AMEND_SUFFIX.getBytes(StandardCharsets.US_ASCII);

    private static final String HEADER_KEY = "KALSHI-ACCESS-KEY";
    private static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    private static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";

    private static final byte[] ORDER_ID_MARKER = "\"order_id\":\"".getBytes(StandardCharsets.UTF_8);

    // PRICE_SCALING_FACTOR / 10_000 — converts internal price to 4-decimal fractional part
    private static final long PRICE_SCALE_DIVISOR = Statics.PRICE_SCALING_FACTOR / 10_000L;
    // SIZE_SCALING_FACTOR / 100 — converts internal size to 2-decimal fractional part
    private static final long SIZE_SCALE_DIVISOR = Statics.SIZE_SCALING_FACTOR / 100L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final HTTPClient httpClient;
    private final String apiHost;
    private final KalshiAuthSigner authSigner;
    private final EpochNanoClock clock;
    private final String marketTicker;
    private final boolean isNoListing;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private int jsonBodyLength;

    private final MutableString cancelPath = new MutableString(
            ORDER_PATH.length() + 1 + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH + AMEND_SUFFIX.length());

    public KalshiOutboundWriter(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final ManyToOneRingBuffer<OrderContext> contextQueue,
            final ManyToOneRingBuffer<OrderContext> rejectQueue,
            final ManyToOneRingBuffer<OrderContext> completionQueue,
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochNanoClock clock,
            final Listing listing) {
        super(orderOutboundBuffer, contextQueue, rejectQueue, completionQueue);
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.clock = clock;
        this.jsonEncoder.wrap(this.jsonBodyBuffer);

        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIdx = exchangeSecurityId.indexOf(':');
        final String suffix = colonIdx >= 0 ? exchangeSecurityId.substring(colonIdx + 1) : "";
        this.marketTicker = colonIdx >= 0 ? exchangeSecurityId.substring(0, colonIdx) : exchangeSecurityId;
        this.isNoListing = "no".equalsIgnoreCase(suffix);
    }

    @Override
    public String roleName() {
        return "kalshi-outbound-writer";
    }

    @Override
    protected boolean submitOrder(final OrderContext ctx) throws Exception {
        long price = this.order.decoder.price();
        long size = this.order.decoder.size();
        Side side = this.order.decoder.side();
        final OrderType orderType = this.order.decoder.orderType();
        final TimeInForce tif = this.order.decoder.timeInForce();

        if (this.isNoListing) {
            side = (side == Side.Bid) ? Side.Ask : Side.Bid;
            price = Statics.PRICE_SCALING_FACTOR - price;
        }

        buildOrderJson(
                price, size, side, orderType, tif, this.order.decoder.flags().postOnly());
        this.authSigner.sign(epochMillis(), "POST", ORDER_PATH);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (!response.isSuccess()) {
            return false;
        }
        return parseOrderId(response, ctx);
    }

    @Override
    protected boolean cancelOrder(final OrderContext ctx) throws Exception {
        buildOrderPath(ctx);
        this.authSigner.sign(epochMillis(), "DELETE", this.cancelPath);

        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        return response.isSuccess();
    }

    @Override
    protected void handleModifyOrder() throws Exception {
        final long clientOidCounter = this.modifyOrder.getClientOidCounter();
        final OrderContext ctx = getActiveOrder(clientOidCounter);
        if (ctx == null) {
            return;
        }

        long price = this.modifyOrder.decoder.price();
        long size = this.modifyOrder.decoder.size();
        Side side = ctx.side;

        if (this.isNoListing) {
            side = (side == Side.Bid) ? Side.Ask : Side.Bid;
            price = Statics.PRICE_SCALING_FACTOR - price;
        }

        buildAmendPath(ctx);
        buildAmendJson(price, size, side);
        this.authSigner.sign(epochMillis(), "POST", this.cancelPath);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (response.isSuccess()) {
            ctx.originalQty = this.modifyOrder.decoder.size();
            ctx.leavesQty = ctx.originalQty - ctx.cumulativeFilledQty;
        } else {
            final OrderContext reject = buildCancelReject(ctx);
            enqueueReject(reject);
            returnToPool(reject);
        }
    }

    @Override
    protected boolean submitForModify(final OrderContext ctx) throws Exception {
        long price = this.modifyOrder.decoder.price();
        long size = this.modifyOrder.decoder.size();
        Side side = ctx.side;
        final OrderType orderType = this.modifyOrder.decoder.orderType();
        final TimeInForce tif = this.modifyOrder.decoder.timeInForce();

        if (this.isNoListing) {
            side = (side == Side.Bid) ? Side.Ask : Side.Bid;
            price = Statics.PRICE_SCALING_FACTOR - price;
        }

        buildOrderJson(
                price,
                size,
                side,
                orderType,
                tif,
                this.modifyOrder.decoder.flags().postOnly());
        this.authSigner.sign(epochMillis(), "POST", ORDER_PATH);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (!response.isSuccess()) {
            return false;
        }
        return parseOrderId(response, ctx);
    }

    private void buildOrderJson(
            final long price,
            final long size,
            final Side side,
            final OrderType orderType,
            final TimeInForce tif,
            final boolean postOnly) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("ticker", this.marketTicker);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", side == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePriceField(price);
        this.jsonEncoder.writeComma();
        writeSizeField(size);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("time_in_force", resolveTimeInForce(orderType, tif));
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("self_trade_prevention_type", "maker");
        if (postOnly) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("post_only", true);
        }
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void buildAmendJson(final long price, final long size, final Side side) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("ticker", this.marketTicker);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", side == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePriceField(price);
        this.jsonEncoder.writeComma();
        writeSizeField(size);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void writePriceField(final long price) {
        this.jsonEncoder.writeString("price").writeColon();
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, price / Statics.PRICE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 4, (price % Statics.PRICE_SCALING_FACTOR) / PRICE_SCALE_DIVISOR);
    }

    private void writeSizeField(final long size) {
        this.jsonEncoder.writeString("count").writeColon();
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, size / Statics.SIZE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 2, (size % Statics.SIZE_SCALING_FACTOR) / SIZE_SCALE_DIVISOR);
    }

    private void buildOrderPath(final OrderContext ctx) {
        this.cancelPath.reset();
        this.cancelPath.appendString(ORDER_PATH_GS);
        this.cancelPath.append((byte) '/');
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            this.cancelPath.append(ctx.exchangeOrderIdBytes[i]);
        }
    }

    private void buildAmendPath(final OrderContext ctx) {
        buildOrderPath(ctx);
        for (byte b : AMEND_SUFFIX_BYTES) {
            this.cancelPath.append(b);
        }
    }

    private static boolean parseOrderId(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        final int from = body.position();
        final int to = body.limit();
        final int markerPos = indexOfBytes(body, from, to, ORDER_ID_MARKER);
        if (markerPos < 0) {
            return false;
        }
        final int valueStart = markerPos + ORDER_ID_MARKER.length;
        int valueEnd = valueStart;
        while (valueEnd < to && body.get(valueEnd) != '"') {
            valueEnd++;
        }
        if (valueEnd >= to) {
            return false;
        }
        ctx.exchangeOrderIdLength = Math.min(valueEnd - valueStart, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            ctx.exchangeOrderIdBytes[i] = body.get(valueStart + i);
        }
        return true;
    }

    private static int indexOfBytes(final ByteBuffer buf, final int from, final int to, final byte[] pattern) {
        outer:
        for (int i = from; i <= to - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String resolveTimeInForce(final OrderType orderType, final TimeInForce tif) {
        if (orderType == OrderType.MARKET) {
            return "fill_or_kill";
        }
        if (tif == TimeInForce.IMMEDIATE_OR_CANCELED) {
            return "immediate_or_cancel";
        }
        return "good_till_canceled";
    }

    private long epochMillis() {
        return this.clock.nanoTime() / NANOS_PER_MILLI;
    }
}
