package group.gnometrading.gateways.outbound.exchanges.polymarket;

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
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ByteBufferUtils;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class PolymarketOutboundWriter extends OutboundSocketWriter {

    private static final String ORDER_PATH = "/order";
    private static final GnomeString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private static final int BUY_SIDE = 0;
    private static final int SELL_SIDE = 1;

    private static final byte[] SUCCESS_MARKER = "\"success\":true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORDER_ID_MARKER = "\"orderID\":\"".getBytes(StandardCharsets.UTF_8);

    private final HTTPClient httpClient;
    private final String clobHost;
    private final PolymarketOrderSigner orderSigner;
    private final PolymarketAuthHeaders authHeaders;
    private final Listing listing;
    private final String tokenId;
    private final BigInteger tokenIdBigInt;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private int jsonBodyLength;

    private final MutableString cancelPath =
            new MutableString(ORDER_PATH.length() + 1 + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);

    public PolymarketOutboundWriter(
            SequencedRingBuffer<?> orderOutboundBuffer,
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue,
            HTTPClient httpClient,
            String clobHost,
            PolymarketOrderSigner orderSigner,
            PolymarketAuthHeaders authHeaders,
            Listing listing) {
        super(orderOutboundBuffer, contextQueue, rejectQueue, completionQueue);
        this.httpClient = httpClient;
        this.clobHost = clobHost;
        this.orderSigner = orderSigner;
        this.authHeaders = authHeaders;
        this.listing = listing;
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
    protected boolean submitOrder(final OrderContext ctx) throws Exception {
        final long price = this.order.decoder.price();
        final long size = this.order.decoder.size();
        final Side side = this.order.decoder.side();
        final OrderType orderType = this.order.decoder.orderType();
        final TimeInForce tif = this.order.decoder.timeInForce();

        final int pmSide = side == Side.Bid ? BUY_SIDE : SELL_SIDE;
        final long makerAmount;
        final long takerAmount;
        if (pmSide == BUY_SIDE) {
            makerAmount = price * size / Statics.PRICE_SCALING_FACTOR;
            takerAmount = size;
        } else {
            makerAmount = size;
            takerAmount = price * size / Statics.PRICE_SCALING_FACTOR;
        }

        final PolymarketOrderSigner.SignedOrder signed =
                this.orderSigner.signOrder(this.tokenIdBigInt, makerAmount, takerAmount, pmSide, 0L);

        buildOrderJson(
                signed,
                resolveOrderType(orderType, tif),
                this.order.decoder.flags().postOnly());
        this.authHeaders.sign("POST", ORDER_PATH, this.jsonBodyBuf, 0, this.jsonBodyLength);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.clobHost,
                ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                PolymarketAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());

        if (!response.isSuccess()) {
            return false;
        }

        return parseOrderHash(response, ctx);
    }

    @Override
    protected boolean cancelOrder(final OrderContext ctx) throws Exception {
        this.authHeaders.sign("DELETE", ORDER_PATH, null, 0, 0);

        this.cancelPath.reset();
        this.cancelPath.appendString(ORDER_PATH_GS);
        this.cancelPath.append((byte) '/');
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            this.cancelPath.append(ctx.exchangeOrderIdBytes[i]);
        }

        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.clobHost,
                this.cancelPath,
                PolymarketAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());

        return response.isSuccess();
    }

    @Override
    protected boolean submitForModify(final OrderContext ctx) throws Exception {
        final long price = this.modifyOrder.decoder.price();
        final long size = this.modifyOrder.decoder.size();
        final int pmSide = ctx.side == group.gnometrading.schemas.Side.Bid ? BUY_SIDE : SELL_SIDE;
        final long makerAmount;
        final long takerAmount;
        if (pmSide == BUY_SIDE) {
            makerAmount = price * size / Statics.PRICE_SCALING_FACTOR;
            takerAmount = size;
        } else {
            makerAmount = size;
            takerAmount = price * size / Statics.PRICE_SCALING_FACTOR;
        }

        final PolymarketOrderSigner.SignedOrder signed =
                this.orderSigner.signOrder(this.tokenIdBigInt, makerAmount, takerAmount, pmSide, 0L);

        buildOrderJson(
                signed,
                resolveOrderType(this.modifyOrder.decoder.orderType(), this.modifyOrder.decoder.timeInForce()),
                this.modifyOrder.decoder.flags().postOnly());
        this.authHeaders.sign("POST", ORDER_PATH, this.jsonBodyBuf, 0, this.jsonBodyLength);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.clobHost,
                ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                PolymarketAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());

        if (!response.isSuccess()) {
            return false;
        }

        return parseOrderHash(response, ctx);
    }

    private void buildOrderJson(
            final PolymarketOrderSigner.SignedOrder signed, final String orderType, final boolean postOnly) {
        this.jsonBodyBuffer.clear();

        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("order").writeColon().writeObjectStart();
        this.jsonEncoder.writeObjectEntry("salt", signed.salt()).writeComma();
        this.jsonEncoder.writeObjectEntry("maker", signed.maker()).writeComma();
        this.jsonEncoder.writeObjectEntry("signer", signed.signer()).writeComma();
        this.jsonEncoder.writeObjectEntry("taker", ZERO_ADDRESS).writeComma();
        this.jsonEncoder.writeObjectEntry("tokenId", this.tokenId).writeComma();
        writeQuotedLong("makerAmount", signed.makerAmount());
        writeQuotedLong("takerAmount", signed.takerAmount());
        this.jsonEncoder.writeObjectEntry("expiration", "0").writeComma();
        this.jsonEncoder.writeObjectEntry("nonce", "0").writeComma();
        writeQuotedLong("feeRateBps", signed.feeRateBps());
        this.jsonEncoder.writeObjectEntry("side", signed.side()).writeComma();
        this.jsonEncoder.writeObjectEntry("signatureType", 0).writeComma();
        this.jsonEncoder.writeObjectEntry("signature", signed.signatureHex());
        this.jsonEncoder.writeObjectEnd().writeComma();
        this.jsonEncoder.writeObjectEntry("owner", signed.maker()).writeComma();
        this.jsonEncoder.writeObjectEntry("orderType", orderType);
        if (postOnly) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("postOnly", true);
        }
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

    private static boolean parseOrderHash(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        final int from = body.position();
        final int to = body.limit();
        if (!containsBytes(body, from, to, SUCCESS_MARKER)) {
            return false;
        }
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

    private static boolean containsBytes(final ByteBuffer buf, final int from, final int to, final byte[] pattern) {
        outer:
        for (int i = from; i <= to - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
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

    private static String resolveOrderType(final OrderType orderType, final TimeInForce tif) {
        if (orderType == OrderType.MARKET) {
            return "FOK";
        }
        if (tif == TimeInForce.IMMEDIATE_OR_CANCELED) {
            return "FAK";
        }
        return "GTC";
    }
}
