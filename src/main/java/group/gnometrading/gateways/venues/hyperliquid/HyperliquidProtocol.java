package group.gnometrading.gateways.venues.hyperliquid;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.feed.GapReason;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.strings.GnomeString;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.agrona.concurrent.EpochClock;

/**
 * Hyperliquid's websocket, many coins per connection: an {@code l2Book} and a {@code trades} subscription per coin,
 * each subscribed and unsubscribed on its own. Every {@code l2Book} message is the coin's whole book (20 levels a
 * side), so there is nothing to resync: the next message repairs anything. A new trades subscription first replays
 * recent trades: those from before it was subscribed are skipped (trade ids are hashes, so time is what tells them). Kept alive with JSON pings: Hyperliquid closes a connection it hasn't heard from
 * for 60 seconds.
 */
public final class HyperliquidProtocol implements VenueProtocol<WebSocketChannel> {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long UNREADABLE = -1L;
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();

    private final SubjectTable table;
    private final EpochClock clock;
    // When each coin's trades were subscribed, in venue milliseconds; a trade before that is the replay. Keyed by the
    // coin so it carries over when a roll swaps the market. Written by the supervisor, read by the reader.
    private final Map<String, Long> subscribedAtMillis = new ConcurrentHashMap<>();

    public HyperliquidProtocol(final SubjectTable table, final EpochClock clock) {
        this.table = table;
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
        // Each coin is its own pair of subscribe messages.
        return 1;
    }

    @Override
    public void onConnected() {
        subscribedAtMillis.clear();
    }

    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            subscribedAtMillis.put(subject.key(), clock.time());
            send(channel.writer(), "subscribe", "l2Book", subject.key());
            send(channel.writer(), "subscribe", "trades", subject.key());
        }
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            subscribedAtMillis.remove(subject.key());
            send(channel.writer(), "unsubscribe", "l2Book", subject.key());
            send(channel.writer(), "unsubscribe", "trades", subject.key());
        }
    }

    @Override
    public void resync(final WebSocketChannel channel, final List<Subject> subjects) {
        // The next l2Book message is the whole book.
    }

    @Override
    public void keepAlive(final WebSocketChannel channel) {
        // { "method": "ping" }
        final JsonEncoder json = channel.writer().json();
        json.writeObjectStart();
        json.writeObjectEntry("method", "ping");
        json.writeObjectEnd();
        channel.writer().sendJson();
    }

    private static void send(final WebSocketWriter writer, final String method, final String type, final String coin) {
        // { "method": "...", "subscription": { "type": "...", "coin": "..." } }
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeObjectEntry("method", method);
        json.writeComma();
        json.writeString("subscription");
        json.writeColon();
        json.writeObjectStart();
        json.writeObjectEntry("type", type);
        json.writeComma();
        json.writeObjectEntry("coin", coin);
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    // ----- decoding, on the reader thread -----

    private enum Channel {
        BOOK,
        TRADES,
        OTHER
    }

    private final JsonDecoder decoder = new JsonDecoder();
    private long receiveNanos;
    private Subject current;
    private long timestamp;
    private boolean unreadable;

    @Override
    public void onFrame(final ByteBuffer frame, final long receive) {
        receiveNanos = receive;
        Channel channel = Channel.OTHER;
        try (var node = decoder.wrap(frame);
                var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("channel")) {
                        channel = channel(key.asString());
                    } else if (key.getName().equals("data")) {
                        if (channel == Channel.BOOK) {
                            book(key);
                        } else if (channel == Channel.TRADES) {
                            trades(key);
                        }
                    }
                }
            }
        }
    }

    private static Channel channel(final GnomeString name) {
        if (name.equals("l2Book")) {
            return Channel.BOOK;
        }
        return name.equals("trades") ? Channel.TRADES : Channel.OTHER;
    }

    /** {"coin":"ETH","time":ms,"levels":[[bids…],[asks…]]}; every message is the coin's whole book. */
    private void book(final JsonDecoder.JsonNode data) {
        current = null;
        timestamp = Mbp10Encoder.timestampEventNullValue();
        unreadable = false;
        try (var object = data.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    bookField(key);
                }
            }
        }
        if (current == null) {
            return;
        }
        // Not published half-read; the next message is the whole book again.
        if (unreadable) {
            current.stale(GapReason.UNREADABLE, receiveNanos);
            return;
        }
        current.endSnapshot(timestamp, receiveNanos, NO_SEQUENCE);
    }

    private void bookField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("coin")) {
            current = table.get(key.asString());
            if (current != null) {
                current.beginSnapshot();
            }
        } else if (current == null) {
            return;
        } else if (name.equals("time")) {
            timestamp = key.asLong() * NANOS_PER_MILLI;
        } else if (name.equals("levels")) {
            try (var sides = key.asArray()) {
                side(sides, Side.Bid);
                side(sides, Side.Ask);
            }
        }
    }

    private void side(final JsonDecoder.JsonArray sides, final Side side) {
        if (!sides.hasNextItem()) {
            return;
        }
        try (var levelsNode = sides.nextItem();
                var levels = levelsNode.asArray()) {
            while (levels.hasNextItem()) {
                try (var levelNode = levels.nextItem()) {
                    level(levelNode, side);
                }
            }
        }
    }

    private void level(final JsonDecoder.JsonNode levelNode, final Side side) {
        long price = UNREADABLE;
        long size = UNREADABLE;
        long orders = 0;
        try (var level = levelNode.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals("px")) {
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
                    } else if (key.getName().equals("sz")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
                    } else if (key.getName().equals("n")) {
                        orders = key.asLong();
                    }
                }
            }
        }
        if (price == UNREADABLE || size == UNREADABLE) {
            unreadable = true;
            return;
        }
        current.level(side, price, size, orders);
    }

    /** [{"coin","side":"A"|"B","px","sz","time","tid"…}, …], all one coin's. */
    private void trades(final JsonDecoder.JsonNode data) {
        current = null;
        try (var trades = data.asArray()) {
            while (trades.hasNextItem()) {
                try (var tradeNode = trades.nextItem()) {
                    trade(tradeNode);
                }
            }
        }
    }

    // The trade being read.
    private Side tradeSide;
    private long tradePrice;
    private long tradeSize;
    private long tradeTime;

    private void trade(final JsonDecoder.JsonNode tradeNode) {
        tradeSide = Side.None;
        tradePrice = UNREADABLE;
        tradeSize = UNREADABLE;
        tradeTime = Mbp10Encoder.timestampEventNullValue();
        try (var trade = tradeNode.asObject()) {
            while (trade.hasNextKey()) {
                try (var key = trade.nextKey()) {
                    tradeField(key);
                }
            }
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (current != null && !replayed() && tradePrice != UNREADABLE && tradeSize != UNREADABLE) {
            current.trade(tradePrice, tradeSize, tradeSide, tradeTime, receiveNanos);
        }
    }

    private void tradeField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("coin") && current == null) {
            current = table.get(key.asString());
        } else if (name.equals("side")) {
            tradeSide = key.asString().equals("A") ? Side.Ask : Side.Bid;
        } else if (name.equals("px")) {
            tradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("sz")) {
            tradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("time")) {
            tradeTime = key.asLong() * NANOS_PER_MILLI;
        }
    }

    /** Whether the trade being read happened before its coin's trades were subscribed: replayed, already seen. */
    private boolean replayed() {
        final Long subscribedAt = subscribedAtMillis.get(current.key());
        return subscribedAt != null && tradeTime < subscribedAt * NANOS_PER_MILLI;
    }
}
