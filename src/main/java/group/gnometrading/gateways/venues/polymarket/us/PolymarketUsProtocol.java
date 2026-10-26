package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Polymarket US's market websocket, {@code /v1/ws/markets}, many markets per connection: each market has its own
 * market-data and trade subscription, named {@code m.<slug>} and {@code t.<slug>}, so it can be added and dropped alone
 * (a market may be in only one subscription per connection). Every {@code marketData} message is the market's whole top
 * of book, in long (YES) terms, so nothing needs resyncing; a {@code :short} listing is the complement projection.
 * Assumes {@code requestId} leads each frame, as Polymarket US sends them.
 *
 * <p>The upgrade is signed with Ed25519 over a fresh millisecond timestamp. No {@code heartbeat} frames were seen in
 * practice, so it pings.
 */
public final class PolymarketUsProtocol implements VenueProtocol<WebSocketChannel> {

    public static final String WEBSOCKET_PATH = "/v1/ws/markets";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long UNREADABLE = -1L;
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    private static final int REQUEST_PREFIX = 2;

    private final SubjectTable table;
    private final PolymarketUsAuthSigner signer;
    private final EpochNanoClock clock;

    public PolymarketUsProtocol(
            final SubjectTable table, final PolymarketUsAuthSigner signer, final EpochNanoClock clock) {
        this.table = table;
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public Books books() {
        return Books.WHOLE;
    }

    @Override
    public int framesPerCommand() {
        // A market's book and its trades are subscribed one message each.
        return 2;
    }

    @Override
    public int maxSubjectsPerCommand() {
        return 1;
    }

    @Override
    public void authenticate(final WebSocketChannel channel) throws IOException {
        signer.sign(clock.nanoTime() / NANOS_PER_MILLI, "GET", WEBSOCKET_PATH);
        channel.client().setHeader(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, signer.apiKey());
        channel.client().setHeader(PolymarketUsAuthSigner.TIMESTAMP_HEADER, signer.timestamp());
        channel.client().setHeader(PolymarketUsAuthSigner.SIGNATURE_HEADER, signer.signature());
    }

    @Override
    public void onConnected() {}

    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            subscription(channel.writer(), "m.", "SUBSCRIPTION_TYPE_MARKET_DATA", subject.key());
            subscription(channel.writer(), "t.", "SUBSCRIPTION_TYPE_TRADE", subject.key());
        }
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            unsubscription(channel.writer(), "m.", subject.key());
            unsubscription(channel.writer(), "t.", subject.key());
        }
    }

    @Override
    public void resync(final WebSocketChannel channel, final List<Subject> subjects) {
        // The next marketData message is the whole book.
    }

    @Override
    public void keepAlive(final WebSocketChannel channel) {
        channel.writer().sendPing();
    }

    private static void subscription(
            final WebSocketWriter writer, final String prefix, final String type, final String slug) {
        // {"subscribe":{"requestId":"m.<slug>","subscriptionType":"...","marketSlugs":["<slug>"]}}
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeString("subscribe");
        json.writeColon();
        json.writeObjectStart();
        json.writeObjectEntry("requestId", prefix + slug);
        json.writeComma();
        json.writeObjectEntry("subscriptionType", type);
        json.writeComma();
        json.writeString("marketSlugs");
        json.writeColon();
        json.writeArrayStart();
        json.writeString(slug);
        json.writeArrayEnd();
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    private static void unsubscription(final WebSocketWriter writer, final String prefix, final String slug) {
        // {"unsubscribe":{"requestId":"m.<slug>"}}
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeString("unsubscribe");
        json.writeColon();
        json.writeObjectStart();
        json.writeObjectEntry("requestId", prefix + slug);
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    // ----- decoding, on the reader thread -----

    private final JsonDecoder decoder = new JsonDecoder();
    private long receiveNanos;
    private Subject current;

    @Override
    public void onFrame(final ByteBuffer frame, final long receive) {
        receiveNanos = receive;
        current = null;
        try (var node = decoder.wrap(frame);
                var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("requestId")) {
                        current = table.get(key.asString(), REQUEST_PREFIX);
                    } else if (current == null) {
                        continue;
                    } else if (name.equals("marketData")) {
                        marketData(key);
                    } else if (name.equals("trade")) {
                        trade(key);
                    }
                }
            }
        }
    }

    private void marketData(final JsonDecoder.JsonNode node) {
        long timestamp = Mbp10Encoder.timestampEventNullValue();
        current.beginSnapshot();
        try (var marketData = node.asObject()) {
            while (marketData.hasNextKey()) {
                try (var key = marketData.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("bids")) {
                        levels(key, Side.Bid);
                    } else if (name.equals("offers")) {
                        levels(key, Side.Ask);
                    } else if (name.equals("transactTime")) {
                        timestamp = time(key);
                    }
                }
            }
        }
        // An empty side may be left out of the message entirely; the cleared book already has none.
        current.endSnapshot(timestamp, receiveNanos, NO_SEQUENCE);
    }

    /** Every message is the whole book, so a level that can't be read is left out of this one only. */
    private void levels(final JsonDecoder.JsonNode node, final Side side) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var levelNode = array.nextItem()) {
                    level(levelNode, side);
                }
            }
        }
    }

    private void level(final JsonDecoder.JsonNode levelNode, final Side side) {
        long price = UNREADABLE;
        long size = UNREADABLE;
        try (var level = levelNode.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals("px")) {
                        price = amount(key, Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("qty")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
                    }
                }
            }
        }
        if (price > 0 && size > 0) {
            current.level(side, price, size, 1);
        }
    }

    // The trade being read.
    private long tradePrice;
    private long tradeSize;
    private long tradeTime;
    private Side tradeSide;

    private void trade(final JsonDecoder.JsonNode node) {
        tradePrice = UNREADABLE;
        tradeSize = UNREADABLE;
        tradeTime = Mbp10Encoder.timestampEventNullValue();
        tradeSide = Side.None;
        try (var trade = node.asObject()) {
            while (trade.hasNextKey()) {
                try (var key = trade.nextKey()) {
                    tradeField(key);
                }
            }
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (tradePrice >= 0 && tradeSize >= 0) {
            current.trade(tradePrice, tradeSize, tradeSide, tradeTime, receiveNanos);
        }
    }

    private void tradeField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("price")) {
            tradePrice = amount(key, Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("quantity")) {
            tradeSize = amount(key, Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("tradeTime")) {
            tradeTime = time(key);
        } else if (name.equals("taker")) {
            tradeSide = takerSide(key);
        }
    }

    /**
     * The book is in long terms, so the aggressor follows the taker's intent: buying short sells long and hits the
     * bids though its order side is BUY. Falls back to the order side when the intent is undefined.
     */
    private static Side takerSide(final JsonDecoder.JsonNode node) {
        Side fromSide = Side.None;
        Side fromIntent = Side.None;
        try (var taker = node.asObject()) {
            while (taker.hasNextKey()) {
                try (var key = taker.nextKey()) {
                    if (key.getName().equals("side")) {
                        fromSide = orderSide(key.asString());
                    } else if (key.getName().equals("intent")) {
                        fromIntent = intent(key.asString());
                    }
                }
            }
        }
        return fromIntent != Side.None ? fromIntent : fromSide;
    }

    private static Side orderSide(final GnomeString value) {
        if (value.equals("ORDER_SIDE_BUY")) {
            return Side.Bid;
        }
        return value.equals("ORDER_SIDE_SELL") ? Side.Ask : Side.None;
    }

    private static Side intent(final GnomeString value) {
        if (value.equals("ORDER_INTENT_BUY_LONG") || value.equals("ORDER_INTENT_SELL_SHORT")) {
            return Side.Bid;
        }
        if (value.equals("ORDER_INTENT_SELL_LONG") || value.equals("ORDER_INTENT_BUY_SHORT")) {
            return Side.Ask;
        }
        return Side.None;
    }

    /** An {@code Amount}, {@code {"value":"0.555","currency":"USD"}}; {@link #UNREADABLE} if it can't be read. */
    private static long amount(final JsonDecoder.JsonNode node, final long scale) {
        long value = UNREADABLE;
        try (var amount = node.asObject()) {
            while (amount.hasNextKey()) {
                try (var key = amount.nextKey()) {
                    if (key.getName().equals("value")) {
                        value = key.asString().toFixedPointLong(scale, UNREADABLE);
                    }
                }
            }
        }
        return value;
    }

    private static long time(final JsonDecoder.JsonNode node) {
        final long nanos = Rfc3339.toEpochNanos(node.asString());
        return nanos == Rfc3339.INVALID ? Mbp10Encoder.timestampEventNullValue() : nanos;
    }
}
