package group.gnometrading.gateways.venues.hyperliquid;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.feed.GapReason;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.transport.WebSocketChannel;
import group.gnometrading.gateways.transport.WebSocketWriter;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.strings.GnomeString;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.agrona.collections.Object2LongHashMap;

/**
 * Hyperliquid's websocket, many coins per connection: an {@code l2Book} and a {@code trades} subscription per coin,
 * each subscribed and unsubscribed on its own. Every {@code l2Book} message is the coin's whole book (20 levels a
 * side), so there is nothing to resync: the next message repairs anything. Books are asked for {@code fast}: without
 * it Hyperliquid sends a coin's book only every ten seconds or so, with it about every half second; an unsubscribe has
 * to name it too, or it matches nothing and the book keeps coming.
 *
 * <p>A trades subscription is answered with a {@code subscriptionResponse} and, straight after, one frame replaying the
 * coin's last 30 trades however old: that frame is never published. Its ids are kept, as its last block can come again
 * as the first live trades, and a trade whose id was just seen is skipped. (Not by time: a live trade can arrive a
 * second after its venue time, later than a subscribe sent meanwhile.) Kept alive with JSON pings: Hyperliquid closes a
 * connection it hasn't heard from for 60 seconds.
 */
public final class HyperliquidProtocol implements VenueProtocol<WebSocketChannel> {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long UNREADABLE = -1L;
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    private static final long NO_ID = -1L;
    // More than a block's trades come again: the replay's last block, at most a few dozen.
    static final int RECENT_TRADE_IDS = 64;

    private final SubjectTable table;
    // Each coin's last trade ids seen. Made by the supervisor as the coin is first subscribed, then only the reader
    // touches one.
    private final Map<String, RecentIds> seenIds = new ConcurrentHashMap<>();
    // Reader thread: coins whose trades subscription was answered, their replay still to come.
    private final List<Subject> replaysDue = new ArrayList<>();
    // Reader thread: each coin's last book's time, in nanoseconds, by key.
    private final Object2LongHashMap<String> bookTimes = new Object2LongHashMap<>(Long.MIN_VALUE);

    public HyperliquidProtocol(final SubjectTable table) {
        this.table = table;
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

    /** Called with the reader paused, so it may clear the reader's list. */
    @Override
    public void onConnected() {
        replaysDue.clear();
    }

    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            seenIds.computeIfAbsent(subject.key(), coin -> new RecentIds(RECENT_TRADE_IDS));
            send(channel.writer(), "subscribe", "l2Book", subject.key());
            send(channel.writer(), "subscribe", "trades", subject.key());
        }
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
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
        if (type.equals("l2Book")) {
            json.writeComma();
            json.writeObjectEntry("fast", true);
        }
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    // ----- decoding, on the reader thread -----

    private enum Channel {
        BOOK,
        TRADES,
        RESPONSE,
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
                        } else if (channel == Channel.RESPONSE) {
                            response(key);
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
        if (name.equals("trades")) {
            return Channel.TRADES;
        }
        return name.equals("subscriptionResponse") ? Channel.RESPONSE : Channel.OTHER;
    }

    // The response being read.
    private boolean subscribed;
    private boolean tradesSubscription;
    private Subject respondedFor;

    /** {"method":"subscribe","subscription":{"type":"trades","coin":"BTC",…}}: that coin's replay comes next. */
    private void response(final JsonDecoder.JsonNode data) {
        subscribed = false;
        tradesSubscription = false;
        respondedFor = null;
        try (var object = data.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("method")) {
                        subscribed = key.asString().equals("subscribe");
                    } else if (key.getName().equals("subscription")) {
                        subscription(key);
                    }
                }
            }
        }
        if (subscribed && tradesSubscription && respondedFor != null && !replaysDue.contains(respondedFor)) {
            replaysDue.add(respondedFor);
        }
    }

    private void subscription(final JsonDecoder.JsonNode node) {
        try (var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("type")) {
                        tradesSubscription = key.asString().equals("trades");
                    } else if (key.getName().equals("coin")) {
                        respondedFor = table.get(key.asString());
                    }
                }
            }
        }
    }

    // The book being read: whether it is one already had, and whether its levels were read.
    private boolean repeatBook;
    private boolean levelsRead;

    /**
     * {"coin":"ETH","time":ms,"levels":[[bids…],[asks…]]}; every message is the coin's whole book. A live coin's book
     * no newer than the last is skipped: right after a subscribe, the next book often repeats the one sent with it,
     * which a connection subscribed earlier never got, and a roll counts on both connections seeing the same books.
     */
    private void book(final JsonDecoder.JsonNode data) {
        current = null;
        timestamp = Mbp10Encoder.timestampEventNullValue();
        unreadable = false;
        repeatBook = false;
        levelsRead = false;
        try (var object = data.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    bookField(key);
                }
            }
        }
        if (current == null || repeatBook) {
            return;
        }
        if (!levelsRead) {
            current.beginSnapshot();
        }
        // Not published half-read; the next message is the whole book again.
        if (unreadable) {
            current.stale(GapReason.UNREADABLE, receiveNanos);
            return;
        }
        bookTimes.put(current.key(), timestamp);
        current.endSnapshot(timestamp, receiveNanos, NO_SEQUENCE);
    }

    private void bookField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("coin")) {
            current = table.get(key.asString());
        } else if (current == null) {
            return;
        } else if (name.equals("time")) {
            timestamp = key.asLong() * NANOS_PER_MILLI;
        } else if (name.equals("levels")) {
            repeatBook = current.state().trusted()
                    && timestamp != Mbp10Encoder.timestampEventNullValue()
                    && timestamp <= bookTimes.getValue(current.key());
            if (repeatBook) {
                return;
            }
            levelsRead = true;
            current.beginSnapshot();
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

    // Whether the trades frame being read is a subscription's replay, and whether its coin is known yet.
    private boolean replay;
    private boolean placed;

    /** [{"coin","side":"A"|"B","px","sz","time","tid"…}, …], all one coin's. */
    private void trades(final JsonDecoder.JsonNode data) {
        current = null;
        replay = false;
        placed = false;
        try (var trades = data.asArray()) {
            while (trades.hasNextItem()) {
                try (var tradeNode = trades.nextItem()) {
                    trade(tradeNode);
                }
            }
        }
        // An empty replay names no coin; replays come in the order their subscriptions were answered.
        if (!placed && !replaysDue.isEmpty()) {
            replaysDue.remove(0);
        }
    }

    /** Once the frame's coin is known: whether it is the replay that coin's subscription was answered with. */
    private void place() {
        placed = true;
        replay = replaysDue.remove(current);
    }

    // The trade being read.
    private Side tradeSide;
    private long tradePrice;
    private long tradeSize;
    private long tradeTime;
    private long tradeId;

    private void trade(final JsonDecoder.JsonNode tradeNode) {
        tradeSide = Side.None;
        tradePrice = UNREADABLE;
        tradeSize = UNREADABLE;
        tradeTime = Mbp10Encoder.timestampEventNullValue();
        tradeId = NO_ID;
        try (var trade = tradeNode.asObject()) {
            while (trade.hasNextKey()) {
                try (var key = trade.nextKey()) {
                    tradeField(key);
                }
            }
        }
        if (current == null) {
            return;
        }
        if (!placed) {
            place();
        }
        final RecentIds seen = seenIds.get(current.key());
        if (seen != null && tradeId != NO_ID) {
            if (seen.contains(tradeId)) {
                return;
            }
            seen.add(tradeId);
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (!replay && tradePrice != UNREADABLE && tradeSize != UNREADABLE) {
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
        } else if (name.equals("tid")) {
            tradeId = key.asLong();
        }
    }

    /** The last few ids added, oldest overwritten first. */
    static final class RecentIds {
        private final long[] ids;
        private int next;

        RecentIds(final int capacity) {
            ids = new long[capacity];
            Arrays.fill(ids, NO_ID);
        }

        boolean contains(final long id) {
            for (long held : ids) {
                if (held == id) {
                    return true;
                }
            }
            return false;
        }

        void add(final long id) {
            ids[next] = id;
            next = next + 1 == ids.length ? 0 : next + 1;
        }
    }
}
