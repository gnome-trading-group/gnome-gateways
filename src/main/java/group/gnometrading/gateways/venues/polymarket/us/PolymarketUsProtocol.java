package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Polymarket US's market websocket, {@code /v1/ws/markets}, many markets per connection. A connection may hold only 10
 * subscriptions (book and trade subscriptions both count; measured 2026-10-27), and a subscription can't be changed
 * once made, nor a market be in two at once. So markets are kept in up to {@link #GROUPS} groups of at most
 * {@link #MARKETS_PER_GROUP}, each one book and one trade subscription, and a group whose markets change is replaced:
 * its subscriptions are dropped and new ones made under fresh ids. Every {@code marketData} message is the market's
 * whole top of book, in long (YES) terms, so a replaced group's books come back whole and nothing needs resyncing; its
 * other markets may miss a trade in the round trip. A {@code :short} listing is the complement projection.
 *
 * <p>Frames are routed by the market they name, assuming {@code marketSlug} leads each {@code marketData} and
 * {@code trade} object, as Polymarket US sends them.
 *
 * <p>The upgrade is signed with Ed25519 over a fresh millisecond timestamp. No {@code heartbeat} frames were seen in
 * practice, so it pings.
 */
public final class PolymarketUsProtocol implements VenueProtocol<WebSocketChannel> {

    public static final String WEBSOCKET_PATH = "/v1/ws/markets";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long UNREADABLE = -1L;
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    // Two of a connection's 10 subscriptions each.
    static final int GROUPS = 5;
    // Under the 100 markets a subscription is documented to take, and small so replacing one group touches few.
    static final int MARKETS_PER_GROUP = 20;
    // Replacing a group drops its two subscriptions and makes two new ones.
    private static final int FRAMES_PER_GROUP = 4;

    private final Logger logger;
    private final SubjectTable table;
    private final PolymarketUsAuthSigner signer;
    private final EpochNanoClock clock;

    /** Supervisor thread only, like every command. */
    private static final class Group {
        final List<Subject> markets = new ArrayList<>();
        String bookRequest;
        String tradeRequest;
        boolean changed;
    }

    private final Group[] groups = new Group[GROUPS];
    private long requests;

    public PolymarketUsProtocol(
            final Logger logger,
            final SubjectTable table,
            final PolymarketUsAuthSigner signer,
            final EpochNanoClock clock) {
        this.logger = logger;
        this.table = table;
        this.signer = signer;
        this.clock = clock;
        for (int i = 0; i < GROUPS; i++) {
            groups[i] = new Group();
        }
    }

    @Override
    public Books books() {
        return Books.WHOLE;
    }

    @Override
    public int framesPerCommand() {
        // A command's markets may be spread over every group.
        return GROUPS * FRAMES_PER_GROUP;
    }

    @Override
    public int maxSubjectsPerCommand() {
        return MARKETS_PER_GROUP;
    }

    @Override
    public void authenticate(final WebSocketChannel channel) throws IOException {
        signer.sign(clock.nanoTime() / NANOS_PER_MILLI, "GET", WEBSOCKET_PATH);
        channel.client().setHeader(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, signer.apiKey());
        channel.client().setHeader(PolymarketUsAuthSigner.TIMESTAMP_HEADER, signer.timestamp());
        channel.client().setHeader(PolymarketUsAuthSigner.SIGNATURE_HEADER, signer.signature());
    }

    /** A new socket carries no subscriptions; the connection subscribes its markets again. */
    @Override
    public void onConnected() {
        for (Group group : groups) {
            group.markets.clear();
            group.bookRequest = null;
            group.tradeRequest = null;
            group.changed = false;
        }
    }

    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            final Group group = roomFor();
            group.markets.add(subject);
            group.changed = true;
        }
        replaceChanged(channel.writer());
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            for (Group group : groups) {
                if (group.markets.remove(subject)) {
                    group.changed = true;
                    break;
                }
            }
        }
        replaceChanged(channel.writer());
    }

    /** The fullest group with room, so adds land on as few groups as possible and each replacement touches few. */
    private Group roomFor() {
        Group best = null;
        for (Group group : groups) {
            final int size = group.markets.size();
            if (size < MARKETS_PER_GROUP && (best == null || size > best.markets.size())) {
                best = group;
            }
        }
        if (best == null) {
            throw new IllegalStateException("A Polymarket US connection holds at most " + GROUPS * MARKETS_PER_GROUP
                    + " markets; lower the source's max_per_connection");
        }
        return best;
    }

    private void replaceChanged(final WebSocketWriter writer) {
        for (int i = 0; i < GROUPS; i++) {
            final Group group = groups[i];
            if (!group.changed) {
                continue;
            }
            group.changed = false;
            // Dropped first: a market may be in only one subscription at a time.
            if (group.bookRequest != null) {
                unsubscription(writer, group.bookRequest);
                unsubscription(writer, group.tradeRequest);
                group.bookRequest = null;
                group.tradeRequest = null;
            }
            if (!group.markets.isEmpty()) {
                // Fresh ids: the venue refuses one already used on the connection.
                final long id = ++requests;
                group.bookRequest = "m" + i + "." + id;
                group.tradeRequest = "t" + i + "." + id;
                subscription(writer, group.bookRequest, "SUBSCRIPTION_TYPE_MARKET_DATA", group.markets);
                subscription(writer, group.tradeRequest, "SUBSCRIPTION_TYPE_TRADE", group.markets);
            }
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
            final WebSocketWriter writer, final String requestId, final String type, final List<Subject> markets) {
        // {"subscribe":{"requestId":"m0.1","subscriptionType":"...","marketSlugs":["<slug>",...]}}
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeString("subscribe");
        json.writeColon();
        json.writeObjectStart();
        json.writeObjectEntry("requestId", requestId);
        json.writeComma();
        json.writeObjectEntry("subscriptionType", type);
        json.writeComma();
        json.writeString("marketSlugs");
        json.writeColon();
        json.writeArrayStart();
        for (int i = 0; i < markets.size(); i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeString(markets.get(i).key());
        }
        json.writeArrayEnd();
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    private static void unsubscription(final WebSocketWriter writer, final String requestId) {
        // {"unsubscribe":{"requestId":"m0.1"}}
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeString("unsubscribe");
        json.writeColon();
        json.writeObjectStart();
        json.writeObjectEntry("requestId", requestId);
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
                    if (name.equals("marketData")) {
                        marketData(key);
                    } else if (name.equals("trade")) {
                        trade(key);
                    } else if (name.equals("error")) {
                        // e.g. a subscription refused: its markets would otherwise just never get a book.
                        logger.logf(
                                LogMessage.UNKNOWN_ERROR,
                                "Polymarket US error: %s",
                                key.asString().toString());
                    }
                }
            }
        }
    }

    private void marketData(final JsonDecoder.JsonNode node) {
        long timestamp = Mbp10Encoder.timestampEventNullValue();
        try (var marketData = node.asObject()) {
            while (marketData.hasNextKey()) {
                try (var key = marketData.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("marketSlug")) {
                        current = table.get(key.asString());
                        if (current == null) {
                            return;
                        }
                        current.beginSnapshot();
                    } else if (current == null) {
                        return;
                    } else if (name.equals("bids")) {
                        levels(key, Side.Bid);
                    } else if (name.equals("offers")) {
                        levels(key, Side.Ask);
                    } else if (name.equals("transactTime")) {
                        timestamp = time(key);
                    }
                }
            }
        }
        if (current == null) {
            return;
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
                    if (key.getName().equals("marketSlug")) {
                        current = table.get(key.asString());
                    } else if (current == null) {
                        return;
                    } else {
                        tradeField(key);
                    }
                }
            }
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (current != null && tradePrice >= 0 && tradeSize >= 0) {
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
