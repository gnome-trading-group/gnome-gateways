package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
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
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ByteBufferUtils;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Order entry for Polymarket's international CLOB (V2).
 *
 * <p>Polymarket orders are signed and immutable, so there is no modify: the OMS cancels and resubmits
 * on this venue, and the base class refuses any modify that arrives.
 */
public final class PolymarketIntlOutboundWriter extends OutboundSocketWriter {

    private static final String ORDER_PATH = "/order";
    private static final String ORDER_LOOKUP_PATH = "/data/order/";
    private static final int HTTP_NOT_FOUND = 404;
    private static final GnomeString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    private static final String ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000";

    private static final int BUY_SIDE = 0;
    private static final int SELL_SIDE = 1;

    // Polymarket sizes are in 6-decimal units, matching SIZE_SCALING_FACTOR, and in whole hundredths of a share.
    private static final long SHARE_INCREMENT = Statics.SIZE_SCALING_FACTOR / 100;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final HTTPClient httpClient;
    private final String clobHost;
    private final PolymarketIntlOrderSigner orderSigner;
    private final PolymarketIntlAuthHeaders authHeaders;
    private final PolymarketIntlMarketInfo marketInfo;
    private final EpochNanoClock clock;
    private final String tokenId;
    private final BigInteger tokenIdBigInt;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private int jsonBodyLength;

    public PolymarketIntlOutboundWriter(
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
        this.httpClient = httpClient;
        this.clobHost = clobHost;
        this.orderSigner = orderSigner;
        this.authHeaders = authHeaders;
        this.marketInfo = marketInfo;
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
        final long makerAmount = pmSide == BUY_SIDE ? notional : size;
        final long takerAmount = pmSide == BUY_SIDE ? size : notional;

        final PolymarketIntlOrderSigner.SignedOrder signed = this.orderSigner.signOrder(
                this.tokenIdBigInt,
                makerAmount,
                takerAmount,
                pmSide,
                this.clock.nanoTime() / NANOS_PER_MILLI,
                this.marketInfo.negRisk());

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
        this.authHeaders.sign("POST", ORDER_PATH, this.jsonBodyBuf, 0, this.jsonBodyLength);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.clobHost,
                ORDER_PATH_GS,
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
            return classifyFailure(response.getStatusCode());
        }
        return readSubmitResponse(response, ctx);
    }

    @Override
    protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
        final String path = ORDER_LOOKUP_PATH
                + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
        this.authHeaders.sign("GET", path, null, 0, 0);

        final HTTPResponse response = this.httpClient.get(
                HTTPProtocol.HTTPS,
                this.clobHost,
                path,
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

        if (response.isSuccess()) {
            return SubmitResult.ACCEPTED;
        }
        return response.getStatusCode() == HTTP_NOT_FOUND ? SubmitResult.REJECTED : SubmitResult.UNKNOWN;
    }

    @Override
    protected boolean cancelOrder(final OrderContext ctx) throws Exception {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("orderID").writeColon();
        writeQuotedBytes(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();

        this.authHeaders.sign("DELETE", ORDER_PATH, this.jsonBodyBuf, 0, this.jsonBodyLength);

        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.clobHost,
                ORDER_PATH_GS,
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

        // The venue answers 200 even when it refuses, listing the order under not_canceled instead.
        return response.isSuccess() && wasCanceled(response, ctx);
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
        boolean sawSuccess = false;
        boolean success = false;
        boolean idMatches = false;
        boolean sawId = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    final GnomeString name = entry.getName();
                    if (name.equals("success")) {
                        sawSuccess = true;
                        success = entry.asBoolean();
                    } else if (name.equals("orderID")) {
                        final GnomeString id = entry.asString();
                        sawId = id.length() > 0;
                        idMatches = matches(id, ctx);
                    }
                }
            }
        }
        if (!sawSuccess) {
            return SubmitResult.UNKNOWN;
        }
        if (!success) {
            return SubmitResult.REJECTED;
        }
        if (sawId && !idMatches) {
            throw new IllegalStateException("Polymarket assigned an orderID that differs from the signed order's hash; "
                    + "the order signer no longer matches the venue");
        }
        return SubmitResult.ACCEPTED;
    }

    /** Whether the order's id is in the response's {@code canceled} list. */
    private boolean wasCanceled(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        boolean canceled = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("canceled")) {
                        canceled = listContains(entry, ctx);
                    }
                }
            }
        }
        return canceled;
    }

    private static boolean listContains(final JsonDecoder.JsonNode list, final OrderContext ctx) {
        boolean found = false;
        try (var ids = list.asArray()) {
            while (ids.hasNextItem()) {
                try (var id = ids.nextItem()) {
                    found |= matches(id.asString(), ctx);
                }
            }
        }
        return found;
    }

    private static boolean matches(final GnomeString id, final OrderContext ctx) {
        if (id.length() != ctx.exchangeOrderIdLength) {
            return false;
        }
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            if (id.byteAt(i) != ctx.exchangeOrderIdBytes[i]) {
                return false;
            }
        }
        return true;
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
}
