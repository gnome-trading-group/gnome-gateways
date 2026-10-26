package group.gnometrading.gateways.venues.lighter;

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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.agrona.collections.Object2LongHashMap;

/**
 * Lighter's websocket, many markets per connection: an {@code order_book/{index}} and a {@code trade/{index}} channel per
 * market, each subscribed on its own; a subscribe answers with that market's full book ({@code subscribed/order_book}),
 * and later messages ({@code update/order_book}) change levels to absolute sizes ("0" removes one). The type comes after
 * the levels, so a frame's levels are collected first and applied once it is known. Each update's {@code begin_nonce}
 * must be the market's previous {@code nonce}: any other value means updates were lost, and the market alone is
 * resubscribed. ({@code offset} is the API server's and isn't contiguous.) Lighter pings inside its own messages and expects a JSON pong; it drops a client
 * silent for two minutes, and limits a whole IP to 200 client messages a minute.
 */
public final class LighterProtocol implements VenueProtocol<WebSocketChannel> {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long UNREADABLE = -1L;
    private static final long NO_NONCE = -1L;
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    private static final byte[] PONG = "{\"type\":\"pong\"}".getBytes(StandardCharsets.US_ASCII);
    private static final int BOOK_PREFIX = "order_book:".length();
    private static final int TRADE_PREFIX = "trade:".length();

    private final SubjectTable table;
    private final WebSocketChannel channel;
    // A fixed message, so nothing encodes it: the supervisor's ping can go out at the same moment.
    private final ByteBuffer pong = ByteBuffer.wrap(PONG);
    // Reader thread: each market's last nonce, by key.
    private final Object2LongHashMap<String> nonces = new Object2LongHashMap<>(NO_NONCE);

    public LighterProtocol(final SubjectTable table, final WebSocketChannel channel) {
        this.table = table;
        this.channel = channel;
    }

    @Override
    public int maxSubjectsPerCommand() {
        // Each market is its own pair of messages.
        return 1;
    }

    @Override
    public Books books() {
        return Books.CHANGES;
    }

    @Override
    public int framesPerCommand() {
        return 2;
    }

    @Override
    public void onConnected() {}

    @Override
    public void subscribe(final WebSocketChannel to, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            send(to.writer(), "subscribe", "order_book/", subject.key());
            send(to.writer(), "subscribe", "trade/", subject.key());
        }
    }

    @Override
    public void unsubscribe(final WebSocketChannel to, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            send(to.writer(), "unsubscribe", "order_book/", subject.key());
            send(to.writer(), "unsubscribe", "trade/", subject.key());
        }
    }

    /** Lighter's documented recovery: resubscribe the market's book, which answers with the full book. */
    @Override
    public void resync(final WebSocketChannel to, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            send(to.writer(), "unsubscribe", "order_book/", subject.key());
            send(to.writer(), "subscribe", "order_book/", subject.key());
        }
    }

    @Override
    public void keepAlive(final WebSocketChannel to) {
        final JsonEncoder json = to.writer().json();
        json.writeObjectStart().writeObjectEntry("type", "ping").writeObjectEnd();
        to.writer().sendJson();
    }

    private static void send(final WebSocketWriter writer, final String type, final String channel, final String key) {
        // { "type": "subscribe", "channel": "order_book/{index}" }
        final JsonEncoder json = writer.json();
        json.writeObjectStart()
                .writeObjectEntry("type", type)
                .writeComma()
                .writeObjectEntry("channel", channel + key)
                .writeObjectEnd();
        writer.sendJson();
    }

    // ----- decoding, on the reader thread -----

    private static final int INITIAL_LEVELS = 1 << 10;

    private final JsonDecoder decoder = new JsonDecoder();
    private long receiveNanos;
    private Subject current;
    private boolean snapshot;
    private boolean book;
    // The frame's levels, held until its type says whether they are the whole book; grown, rarely, for a deep one.
    private Side[] levelSides = new Side[INITIAL_LEVELS];
    private long[] levelPrices = new long[INITIAL_LEVELS];
    private long[] levelSizes = new long[INITIAL_LEVELS];
    private int levels;
    private boolean unreadable;
    private long nonce;
    private long beginNonce;
    private long timestamp;

    @Override
    public void onFrame(final ByteBuffer frame, final long receive) {
        receiveNanos = receive;
        current = null;
        book = false;
        snapshot = false;
        levels = 0;
        unreadable = false;
        nonce = NO_NONCE;
        beginNonce = NO_NONCE;
        timestamp = Mbp10Encoder.timestampEventNullValue();
        try (var node = decoder.wrap(frame);
                var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    frameField(key);
                }
            }
        }
        if (book) {
            finishBook();
        }
    }

    private void frameField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("channel")) {
            current = lookUp(key.asString());
        } else if (name.equals("order_book") && current != null) {
            book = true;
            orderBook(key);
        } else if (name.equals("trades") && current != null) {
            trades(key);
        } else if (name.equals("timestamp")) {
            timestamp = key.asLong() * NANOS_PER_MILLI;
        } else if (name.equals("type")) {
            final GnomeString type = key.asString();
            if (type.equals("ping")) {
                pong.rewind();
                channel.writer().sendText(pong, true);
            } else if (type.equals("subscribed/order_book")) {
                snapshot = true;
            }
        }
    }

    private Subject lookUp(final GnomeString channelName) {
        if (channelName.length() > BOOK_PREFIX && channelName.byteAt(0) == 'o') {
            return table.get(channelName, BOOK_PREFIX);
        }
        if (channelName.length() > TRADE_PREFIX && channelName.byteAt(0) == 't') {
            return table.get(channelName, TRADE_PREFIX);
        }
        return null;
    }

    private void orderBook(final JsonDecoder.JsonNode node) {
        try (var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("asks")) {
                        orders(key, Side.Ask);
                    } else if (name.equals("bids")) {
                        orders(key, Side.Bid);
                    } else if (name.equals("nonce")) {
                        nonce = key.asLong();
                    } else if (name.equals("begin_nonce")) {
                        beginNonce = key.asLong();
                    }
                }
            }
        }
    }

    private void finishBook() {
        if (snapshot) {
            current.beginSnapshot();
            for (int i = 0; i < levels; i++) {
                current.level(levelSides[i], levelPrices[i], levelSizes[i], 1);
            }
            nonces.put(current.key(), nonce);
            current.endSnapshot(timestamp, receiveNanos, NO_SEQUENCE);
            return;
        }
        // Skipped, an update that can't be read would leave its level wrong for good.
        if (unreadable) {
            current.stale(GapReason.UNREADABLE, receiveNanos);
            return;
        }
        final long previous = nonces.getValue(current.key());
        if (nonce != NO_NONCE) {
            nonces.put(current.key(), nonce);
        }
        if (previous != NO_NONCE && beginNonce != previous) {
            current.stale(GapReason.NONCE, receiveNanos);
            return;
        }
        for (int i = 0; i < levels; i++) {
            current.set(levelSides[i], levelPrices[i], levelSizes[i], 1);
        }
        current.endUpdate(timestamp, receiveNanos, NO_SEQUENCE);
    }

    /**
     * A snapshot level that can't be read is left out: the resubscribe would bring the same snapshot back. An update
     * with one makes the market stale.
     */
    private void orders(final JsonDecoder.JsonNode node, final Side side) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem()) {
                    order(item, side);
                }
            }
        }
    }

    private void order(final JsonDecoder.JsonNode item, final Side side) {
        long price = UNREADABLE;
        long size = UNREADABLE;
        try (var level = item.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals("price")) {
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
                    } else if (key.getName().equals("size")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
                    }
                }
            }
        }
        if (price == UNREADABLE || size == UNREADABLE) {
            unreadable = true;
            return;
        }
        if (levels == levelPrices.length) {
            levelSides = Arrays.copyOf(levelSides, levels * 2);
            levelPrices = Arrays.copyOf(levelPrices, levels * 2);
            levelSizes = Arrays.copyOf(levelSizes, levels * 2);
        }
        levelSides[levels] = side;
        levelPrices[levels] = price;
        levelSizes[levels] = size;
        levels++;
    }

    private void trades(final JsonDecoder.JsonNode node) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem()) {
                    trade(item);
                }
            }
        }
    }

    // The trade being read.
    private long tradePrice;
    private long tradeSize;
    private long tradeTime;
    private Side tradeSide;

    private void trade(final JsonDecoder.JsonNode item) {
        tradePrice = UNREADABLE;
        tradeSize = UNREADABLE;
        tradeTime = Mbp10Encoder.timestampEventNullValue();
        tradeSide = Side.None;
        try (var trade = item.asObject()) {
            while (trade.hasNextKey()) {
                try (var key = trade.nextKey()) {
                    tradeField(key);
                }
            }
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (tradePrice != UNREADABLE && tradeSize != UNREADABLE) {
            current.trade(tradePrice, tradeSize, tradeSide, tradeTime, receiveNanos);
        }
    }

    private void tradeField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("price")) {
            tradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("size")) {
            tradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("timestamp")) {
            tradeTime = key.asLong() * NANOS_PER_MILLI;
        } else if (name.equals("is_maker_ask")) {
            // A resting ask was hit, so the aggressor bought.
            tradeSide = key.asBoolean() ? Side.Bid : Side.Ask;
        }
    }
}
