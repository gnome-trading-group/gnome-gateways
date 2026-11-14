package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.AsciiParsing;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Kalshi's order entry wire format, without I/O: request bodies and paths written into the caller's buffers, and
 * answers read back. Market-specific facts come in as a {@link KalshiMarket}, so one codec serves every market on an
 * account. Prices and sides are taken in the listing's terms and turned into the YES book's for a NO listing. Not
 * thread-safe: one per sending thread. Nothing here allocates.
 */
public final class KalshiOrderCodec {

    public static final String ORDER_PATH = "/trade-api/v2/portfolio/events/orders";
    public static final ViewString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    public static final String EXCHANGE_STATUS_PATH = "/trade-api/v2/exchange/status";
    public static final String HEADER_KEY = "KALSHI-ACCESS-KEY";
    public static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    public static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";
    // Kalshi refuses a JSON body without it (400 invalid_content_type), and the HTTP client never writes one.
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final int HTTP_CONFLICT = 409;
    public static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private static final byte[] AMEND_SUFFIX = "/amend".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ORDER_ID_MARKER = "\"order_id\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] QUOTE = {'"'};
    private static final byte[] REMAINING_COUNT_MARKER = "\"remaining_count\":\"".getBytes(StandardCharsets.UTF_8);
    // PRICE_SCALING_FACTOR / 10_000: converts an internal price to its 4-decimal fractional part.
    private static final long PRICE_SCALE_DIVISOR = Statics.PRICE_SCALING_FACTOR / 10_000L;
    // SIZE_SCALING_FACTOR / 100: converts an internal size to its 2-decimal fractional part.
    private static final long SIZE_SCALE_DIVISOR = Statics.SIZE_SCALING_FACTOR / 100L;

    private final String selfTradePrevention;
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private final byte[] clientOrderIdBuf = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private final ByteBuffer clientOrderIdBuffer = ByteBuffer.wrap(this.clientOrderIdBuf);
    private final byte[] lookupOrderId = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int lookupOrderIdLength;

    /** @param selfTradePrevention Kalshi's {@code self_trade_prevention_type}, required on every order */
    public KalshiOrderCodec(final String selfTradePrevention) {
        this.selfTradePrevention = selfTradePrevention;
    }

    /**
     * {@code {sessionTag}-{counter}} into the order's correlation id. Each OMS counts from 1 and Kalshi deduplicates on
     * this id, so the session tag keeps ids unique across sessions.
     *
     * @param prefix the session tag followed by {@code -}
     */
    public void writeClientOrderId(final byte[] prefix, final OrderContext ctx) {
        this.clientOrderIdBuffer.clear();
        this.clientOrderIdBuffer.put(prefix);
        ByteBufferUtils.putLongAscii(this.clientOrderIdBuffer, ctx.clientOidCounter);
        ctx.correlationIdLength = this.clientOrderIdBuffer.position();
        System.arraycopy(this.clientOrderIdBuf, 0, ctx.correlationIdBytes, 0, ctx.correlationIdLength);
    }

    /** Writes a new order's body into {@code out} from its start; returns its length. */
    public int buildOrder(
            final KalshiMarket market,
            final long price,
            final long size,
            final Side side,
            final TimeInForce tif,
            final boolean postOnly,
            final OrderContext ctx,
            final ByteBuffer out) {
        start(out);
        this.jsonEncoder.writeObjectEntry("ticker", market.ticker());
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeString("client_order_id").writeColon();
        out.put((byte) '"');
        out.put(ctx.correlationIdBytes, 0, ctx.correlationIdLength);
        out.put((byte) '"');
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", venueSide(market, side) == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePrice(venuePrice(market, price), out);
        this.jsonEncoder.writeComma();
        writeSize(size, out);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("time_in_force", timeInForce(tif));
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("self_trade_prevention_type", this.selfTradePrevention);
        if (postOnly) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("post_only", true);
        }
        writeExchangeIndex(market);
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /** Writes an amend's body into {@code out} from its start; returns its length. */
    public int buildAmend(
            final KalshiMarket market, final long price, final long size, final Side side, final ByteBuffer out) {
        start(out);
        this.jsonEncoder.writeObjectEntry("ticker", market.ticker());
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", venueSide(market, side) == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePrice(venuePrice(market, price), out);
        this.jsonEncoder.writeComma();
        writeSize(size, out);
        writeExchangeIndex(market);
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /** {@code .../orders/{order_id}}, the path a cancel signs; its routing query is appended after signing. */
    public static void orderPath(final OrderContext ctx, final MutableString path) {
        path.reset();
        path.appendString(ORDER_PATH_GS);
        path.append((byte) '/');
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            path.append(ctx.exchangeOrderIdBytes[i]);
        }
    }

    public static void amendPath(final OrderContext ctx, final MutableString path) {
        orderPath(ctx, path);
        for (final byte b : AMEND_SUFFIX) {
            path.append(b);
        }
    }

    /** Room a path built by {@link #orderPath} or {@link #amendPath} needs for the market, routing query included. */
    public static int pathCapacity(final KalshiMarket market) {
        return ORDER_PATH.length()
                + 1
                + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH
                + AMEND_SUFFIX.length
                + KalshiApiUtil.routingQuery(market.ticker(), KalshiApiUtil.SHARD_UNKNOWN)
                        .length()
                + "&exchange_index=".length()
                + 11;
    }

    public static void appendRoutingQuery(final KalshiMarket market, final MutableString path) {
        for (final byte b : market.routingQuery()) {
            path.append(b);
        }
    }

    /** Takes the venue's order id from a submit's answer into the context; false if the answer has none. */
    public static boolean readOrderId(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        final int from = body.position();
        final int to = body.limit();
        final int markerPos = AsciiParsing.indexOf(body, from, to, ORDER_ID_MARKER);
        if (markerPos < 0) {
            return false;
        }
        final int valueStart = markerPos + ORDER_ID_MARKER.length;
        final int valueEnd = AsciiParsing.indexOf(body, valueStart, to, QUOTE);
        if (valueEnd < 0) {
            return false;
        }
        ctx.exchangeOrderIdLength = Math.min(valueEnd - valueStart, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            ctx.exchangeOrderIdBytes[i] = body.get(valueStart + i);
        }
        return true;
    }

    /**
     * An accepted amend's {@code remaining_count}, a quoted decimal such as {@code "5.00"}, scaled to
     * {@link Statics#SIZE_SCALING_FACTOR}; {@link OrderContext#QTY_ABSENT} when missing, null or unreadable.
     */
    public static long readRemainingCount(final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return OrderContext.QTY_ABSENT;
        }
        final int markerPos = AsciiParsing.indexOf(body, body.position(), body.limit(), REMAINING_COUNT_MARKER);
        if (markerPos < 0) {
            return OrderContext.QTY_ABSENT;
        }
        // The value is quoted: it ends where its digits do, after an optional point.
        final int start = markerPos + REMAINING_COUNT_MARKER.length;
        int end = AsciiParsing.skipDigits(body, start, body.limit());
        if (end < body.limit() && body.get(end) == '.') {
            end = AsciiParsing.skipDigits(body, end + 1, body.limit());
        }
        return AsciiParsing.parseNaturalFixedPoint(
                body, start, end, Statics.SIZE_SCALING_FACTOR, OrderContext.QTY_ABSENT);
    }

    /**
     * Whether the exchange status answer says the market's shard is trading: by the shard's own entry when listed,
     * else by the exchange-wide flag.
     */
    public boolean readTradingActive(final ByteBuffer body, final int exchangeIndex) {
        boolean exchangeTrading = true;
        int shardTrading = -1;
        try (var root = this.jsonDecoder.wrap(body);
                var status = root.asObject()) {
            while (status.hasNextKey()) {
                try (var entry = status.nextKey()) {
                    if (entry.getName().equals("trading_active")) {
                        exchangeTrading = entry.asBoolean();
                    } else if (entry.getName().equals("exchange_index_statuses")) {
                        shardTrading = readShardTrading(entry, exchangeIndex);
                    }
                }
            }
        }
        return shardTrading >= 0 ? shardTrading == 1 : exchangeTrading;
    }

    /** 1 if the shard is listed as trading, 0 if listed as not, -1 if it isn't listed. */
    private static int readShardTrading(final JsonDecoder.JsonNode statuses, final int exchangeIndex) {
        int result = -1;
        try (var list = statuses.asArray()) {
            while (list.hasNextItem()) {
                final int shard = readShardIfOurs(list.nextItem(), exchangeIndex);
                if (shard >= 0) {
                    result = shard;
                }
            }
        }
        return result;
    }

    private static int readShardIfOurs(final JsonDecoder.JsonNode item, final int exchangeIndex) {
        int index = -1;
        boolean trading = true;
        try (item;
                var shard = item.asObject()) {
            while (shard.hasNextKey()) {
                final var field = shard.nextKey();
                if (field.getName().equals("exchange_index")) {
                    index = (int) field.asLong();
                } else if (field.getName().equals("trading_active")) {
                    trading = field.asBoolean();
                }
                field.close();
            }
        }
        if (index != exchangeIndex) {
            return -1;
        }
        return trading ? 1 : 0;
    }

    /**
     * The top-level {@code ts_ms} of a create, cancel or amend answer (when the matching engine processed it, epoch
     * milliseconds), or -1; leaves the body where it was, for the answer's own read.
     */
    public long tsMillis(final ByteBuffer body) {
        if (body == null || !body.hasRemaining()) {
            return -1;
        }
        final int position = body.position();
        long millis = -1;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("ts_ms")) {
                        millis = entry.asLong();
                    }
                }
            }
        } finally {
            body.position(position);
        }
        return millis;
    }

    /**
     * Scans an order listing page ({@code {"orders":[...]}}) for the order's client id, taking the venue's order id
     * into the context if found.
     */
    public boolean findInOrderList(final ByteBuffer body, final OrderContext ctx) {
        boolean found = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("orders")) {
                        found = scanOrders(entry, ctx);
                    }
                }
            }
        }
        return found;
    }

    private boolean scanOrders(final JsonDecoder.JsonNode orders, final OrderContext ctx) {
        boolean found = false;
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    found |= !found && readOrderIfOurs(order, ctx);
                }
            }
        }
        return found;
    }

    private boolean readOrderIfOurs(final JsonDecoder.JsonObject order, final OrderContext ctx) {
        boolean ours = false;
        this.lookupOrderIdLength = 0;
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                final GnomeString name = field.getName();
                if (name.equals("client_order_id")) {
                    ours = equalsCorrelationId(field.asString(), ctx);
                } else if (name.equals("order_id")) {
                    this.lookupOrderIdLength = copyBytes(field.asString(), this.lookupOrderId);
                }
            }
        }
        if (ours && this.lookupOrderIdLength > 0) {
            ctx.exchangeOrderIdLength = this.lookupOrderIdLength;
            System.arraycopy(this.lookupOrderId, 0, ctx.exchangeOrderIdBytes, 0, this.lookupOrderIdLength);
            return true;
        }
        return false;
    }

    private void start(final ByteBuffer out) {
        out.clear();
        this.jsonEncoder.wrap(out);
        this.jsonEncoder.writeObjectStart();
    }

    private void writeExchangeIndex(final KalshiMarket market) {
        if (market.exchangeIndex() >= 0) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("exchange_index", market.exchangeIndex());
        }
    }

    // Kalshi takes fixed-point decimals as JSON strings and refuses numbers.
    private void writePrice(final long price, final ByteBuffer out) {
        this.jsonEncoder.writeString("price").writeColon();
        out.put((byte) '"');
        ByteBufferUtils.putLongAscii(out, price / Statics.PRICE_SCALING_FACTOR);
        out.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(out, 4, (price % Statics.PRICE_SCALING_FACTOR) / PRICE_SCALE_DIVISOR);
        out.put((byte) '"');
    }

    private void writeSize(final long size, final ByteBuffer out) {
        this.jsonEncoder.writeString("count").writeColon();
        out.put((byte) '"');
        ByteBufferUtils.putLongAscii(out, size / Statics.SIZE_SCALING_FACTOR);
        out.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(out, 2, (size % Statics.SIZE_SCALING_FACTOR) / SIZE_SCALE_DIVISOR);
        out.put((byte) '"');
    }

    private static Side venueSide(final KalshiMarket market, final Side side) {
        if (!market.noSide()) {
            return side;
        }
        return side == Side.Bid ? Side.Ask : Side.Bid;
    }

    private static long venuePrice(final KalshiMarket market, final long price) {
        return market.noSide() ? Statics.PRICE_SCALING_FACTOR - price : price;
    }

    private static String timeInForce(final TimeInForce tif) {
        return switch (tif) {
            case IMMEDIATE_OR_CANCELED -> "immediate_or_cancel";
            case FILL_OR_KILL -> "fill_or_kill";
            default -> "good_till_canceled";
        };
    }

    private static boolean equalsCorrelationId(final GnomeString value, final OrderContext ctx) {
        if (value.length() != ctx.correlationIdLength) {
            return false;
        }
        for (int i = 0; i < ctx.correlationIdLength; i++) {
            if (value.byteAt(i) != ctx.correlationIdBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static int copyBytes(final GnomeString value, final byte[] dest) {
        final int length = Math.min(value.length(), dest.length);
        for (int i = 0; i < length; i++) {
            dest[i] = value.byteAt(i);
        }
        return length;
    }
}
