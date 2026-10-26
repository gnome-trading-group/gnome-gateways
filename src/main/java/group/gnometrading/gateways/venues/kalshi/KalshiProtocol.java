package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.feed.GapReason;
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
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Kalshi's market data websocket, many markets per connection.
 *
 * <p>A connection holds one {@code orderbook_delta} and one {@code trade} subscription (sid). Subscribing again adds
 * markets to the same sids; {@code delete_markets} takes them off and {@code get_snapshot} re-sends one market's book
 * without touching the others. Each sid numbers <em>every</em> frame it sends, command replies included, so a skipped
 * {@code seq} is exact — but it is per connection, not per market: a gap on the book sid makes every market here stale
 * and re-snapshots them all. (Measured on demo, 2026-10-26.)
 *
 * <p>Prices are YES-leg dollar strings (the subscription sets {@code use_yes_price}): YES levels are bids, NO levels
 * asks at the same price. Books are kept in tenths of a cent, Kalshi's finest tick; a price off every tick is dropped.
 * Quantities are contract strings with two decimals. Assumes {@code type} precedes {@code msg} in data frames and
 * {@code market_ticker} leads {@code msg}, as Kalshi sends them.
 *
 * <p>The upgrade is signed with RSA-PSS (SHA-256, MGF1-SHA-256, salt 32) over a fresh millisecond timestamp.
 */
public final class KalshiProtocol implements VenueProtocol<WebSocketChannel> {

    static final String WEBSOCKET_PATH = "/trade-api/ws/v2";
    private static final int MARKETS_PER_COMMAND = 100;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int TICKS_PER_DOLLAR = 1000;
    private static final long TICK_TO_PRICE = Statics.PRICE_SCALING_FACTOR / TICKS_PER_DOLLAR;
    // Read one digit finer than the finest tick, so a price off every tick can be told apart and dropped.
    private static final long FINE_TICKS_PER_DOLLAR = TICKS_PER_DOLLAR * 10L;
    private static final long CENTS_TO_SIZE = Statics.SIZE_SCALING_FACTOR / 100L;
    private static final long UNREADABLE = -1L;
    private static final long NONE = -1L;
    private static final long NO_TIME = Mbp10Encoder.timestampEventNullValue();
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();

    private enum Type {
        OTHER,
        SUBSCRIBED,
        SNAPSHOT,
        DELTA,
        TRADE,
        ERROR
    }

    private final Logger logger;
    private final SubjectTable table;
    private final String apiKey;
    private final PrivateKey privateKey;
    private final EpochNanoClock clock;

    // The connection's two subscriptions and the seq each sends next; reset by the supervisor on connect, while the
    // reader is paused.
    private long bookSid;
    private long bookNext;
    private long tradeSid;
    private long tradeNext;
    private int nextCommandId;

    public KalshiProtocol(
            final Logger logger,
            final SubjectTable table,
            final String apiKey,
            final PrivateKey privateKey,
            final EpochNanoClock clock) {
        this.logger = logger;
        this.table = table;
        this.apiKey = apiKey;
        this.privateKey = privateKey;
        this.clock = clock;
        onConnected();
    }

    @Override
    public Books books() {
        return Books.CHANGES;
    }

    @Override
    public int framesPerCommand() {
        // An unsubscribe updates the book and the trade subscription, one message each.
        return 2;
    }

    @Override
    public int maxSubjectsPerCommand() {
        return MARKETS_PER_COMMAND;
    }

    @Override
    public void authenticate(final WebSocketChannel channel) throws IOException {
        final String timestamp = Long.toString(clock.nanoTime() / NANOS_PER_MILLI);
        try {
            final Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            signature.initSign(privateKey);
            signature.update((timestamp + "GET" + WEBSOCKET_PATH).getBytes(StandardCharsets.UTF_8));
            channel.client().setHeader("KALSHI-ACCESS-KEY", apiKey);
            channel.client().setHeader("KALSHI-ACCESS-TIMESTAMP", timestamp);
            channel.client()
                    .setHeader("KALSHI-ACCESS-SIGNATURE", Base64.getEncoder().encodeToString(signature.sign()));
        } catch (Exception e) {
            throw new IOException("Failed to sign the Kalshi upgrade", e);
        }
    }

    @Override
    public void onConnected() {
        bookSid = NONE;
        bookNext = NONE;
        tradeSid = NONE;
        tradeNext = NONE;
        nextCommandId = 1;
    }

    /** Adds markets: the connection's first subscribe opens both sids, later ones add to them. */
    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        // {"id":n,"cmd":"subscribe","params":{"channels":["orderbook_delta","trade"],"market_tickers":[...],
        // "use_yes_price":true}}
        final WebSocketWriter writer = channel.writer();
        final JsonEncoder json = writer.json();
        command(json, "subscribe");
        json.writeObjectStart();
        json.writeString("channels");
        json.writeColon();
        json.writeArrayStart();
        json.writeString("orderbook_delta");
        json.writeComma();
        json.writeString("trade");
        json.writeArrayEnd();
        json.writeComma();
        tickers(json, subjects);
        json.writeComma();
        json.writeObjectEntry("use_yes_price", true);
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        update(channel, bookSid, subjects, "delete_markets");
        update(channel, tradeSid, subjects, "delete_markets");
    }

    @Override
    public void resync(final WebSocketChannel channel, final List<Subject> subjects) {
        // Before the book sid is known, a subscribe is still on its way, and its snapshots with it.
        update(channel, bookSid, subjects, "get_snapshot");
    }

    @Override
    public void keepAlive(final WebSocketChannel channel) {
        // Kalshi pings every 10 seconds and the transport answers; nothing more is needed.
    }

    private void update(
            final WebSocketChannel channel, final long sid, final List<Subject> subjects, final String action) {
        if (sid == NONE) {
            return;
        }
        // {"id":n,"cmd":"update_subscription","params":{"sids":[sid],"market_tickers":[...],"action":"..."}}
        final WebSocketWriter writer = channel.writer();
        final JsonEncoder json = writer.json();
        command(json, "update_subscription");
        json.writeObjectStart();
        json.writeString("sids");
        json.writeColon();
        json.writeArrayStart();
        json.writeNumber(sid);
        json.writeArrayEnd();
        json.writeComma();
        tickers(json, subjects);
        json.writeComma();
        json.writeObjectEntry("action", action);
        json.writeObjectEnd();
        json.writeObjectEnd();
        writer.sendJson();
    }

    private void command(final JsonEncoder json, final String cmd) {
        json.writeObjectStart();
        json.writeObjectEntry("id", nextCommandId++);
        json.writeComma();
        json.writeObjectEntry("cmd", cmd);
        json.writeComma();
        json.writeString("params");
        json.writeColon();
    }

    private static void tickers(final JsonEncoder json, final List<Subject> subjects) {
        json.writeString("market_tickers");
        json.writeColon();
        json.writeArrayStart();
        for (int i = 0; i < subjects.size(); i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeString(subjects.get(i).key());
        }
        json.writeArrayEnd();
    }

    // ----- decoding, on the reader thread -----

    private final JsonDecoder decoder = new JsonDecoder();
    // The frame being read; fields rather than locals so nothing is allocated per frame.
    private Type type;
    private long sid;
    private long seq;
    private boolean sequenceChecked;
    private long receiveNanos;
    private long sentNanos;
    // A snapshot ends once the whole frame is read: its only time, the frame's send time, comes after its levels.
    private Subject snapshotSubject;

    @Override
    public void onFrame(final ByteBuffer frame, final long receive) {
        receiveNanos = receive;
        type = Type.OTHER;
        sid = NONE;
        seq = NONE;
        sequenceChecked = false;
        sentNanos = NO_TIME;
        snapshotSubject = null;
        try (var node = decoder.wrap(frame);
                var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("type")) {
                        type = type(key.asString());
                    } else if (name.equals("sid")) {
                        sid = key.asLong();
                    } else if (name.equals("seq")) {
                        seq = key.asLong();
                    } else if (name.equals("sending_ts_ms")) {
                        sentNanos = key.asLong() * NANOS_PER_MILLI;
                    } else if (name.equals("msg")) {
                        // Checked first, so the frame that shows a gap lands on books already stale, not published.
                        checkSequence();
                        message(key);
                    }
                }
            }
        }
        checkSequence();
        if (snapshotSubject != null) {
            // Kalshi's snapshot carries no time of its own; it is the book as of when Kalshi sent it.
            snapshotSubject.endSnapshot(sentNanos, receiveNanos, NO_SEQUENCE);
        }
    }

    private static Type type(final GnomeString value) {
        if (value.equals("orderbook_delta")) {
            return Type.DELTA;
        } else if (value.equals("trade")) {
            return Type.TRADE;
        } else if (value.equals("orderbook_snapshot")) {
            return Type.SNAPSHOT;
        } else if (value.equals("subscribed")) {
            return Type.SUBSCRIBED;
        } else if (value.equals("error")) {
            return Type.ERROR;
        }
        return Type.OTHER;
    }

    private void message(final JsonDecoder.JsonNode msg) {
        switch (type) {
            case SNAPSHOT -> snapshot(msg);
            case DELTA -> delta(msg);
            case TRADE -> trade(msg);
            case SUBSCRIBED -> subscribed(msg);
            case ERROR -> error(msg);
            default -> {
                // ok, unsubscribed: their seq is checked; the body tells us nothing more.
            }
        }
    }

    /**
     * Every frame on a sid carries the next number. One skipped on the book sid means a lost delta for some market
     * here, so every market is re-snapshotted; one skipped on the trade sid loses trades, which nothing can bring back.
     */
    private void checkSequence() {
        if (sequenceChecked || sid == NONE || seq == NONE) {
            return;
        }
        sequenceChecked = true;
        if (sid == bookSid) {
            checkBookSequence();
        } else if (sid == tradeSid) {
            if (tradeNext != NONE && seq != tradeNext) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Kalshi trades skipped from %d to %d", tradeNext, seq);
            }
            tradeNext = seq + 1;
        }
    }

    private void checkBookSequence() {
        if (bookNext != NONE && seq != bookNext) {
            logger.log(LogMessage.MARKET_DATA_GAP, 0, bookNext, seq, 0);
            for (Subject subject : table.subjects()) {
                subject.stale(GapReason.CONNECTION_SEQUENCE, receiveNanos);
            }
        }
        bookNext = seq + 1;
    }

    private void subscribed(final JsonDecoder.JsonNode msg) {
        boolean book = false;
        long subscribedSid = NONE;
        try (var object = msg.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("channel")) {
                        book = key.asString().equals("orderbook_delta");
                    } else if (key.getName().equals("sid")) {
                        subscribedSid = key.asLong();
                    }
                }
            }
        }
        // A new sid numbers its frames from 1.
        if (book) {
            bookSid = subscribedSid;
            bookNext = 1;
        } else {
            tradeSid = subscribedSid;
            tradeNext = 1;
        }
    }

    private void error(final JsonDecoder.JsonNode msg) {
        try (var object = msg.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("msg")) {
                        logger.logf(
                                LogMessage.UNKNOWN_ERROR,
                                "Kalshi error: %s",
                                key.asString().toString());
                    }
                }
            }
        }
    }

    private void snapshot(final JsonDecoder.JsonNode msg) {
        Subject subject = null;
        try (var object = msg.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    final GnomeString name = key.getName();
                    if (name.equals("market_ticker")) {
                        subject = table.get(key.asString());
                        if (subject != null) {
                            subject.beginSnapshot();
                        }
                    } else if (subject != null && name.equals("yes_dollars_fp")) {
                        levels(key, subject, Side.Bid);
                    } else if (subject != null && name.equals("no_dollars_fp")) {
                        levels(key, subject, Side.Ask);
                    }
                }
            }
        }
        snapshotSubject = subject;
    }

    /**
     * A snapshot level that can't be read is left out: thrown, a resync would bring the same snapshot back and fail
     * again, and never get a book.
     */
    private static void levels(final JsonDecoder.JsonNode node, final Subject subject, final Side side) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var pairNode = array.nextItem()) {
                    level(pairNode, subject, side);
                }
            }
        }
    }

    private static void level(final JsonDecoder.JsonNode pairNode, final Subject subject, final Side side) {
        int tick = 0;
        long quantity = UNREADABLE;
        try (var pair = pairNode.asArray()) {
            if (pair.hasNextItem()) {
                try (var price = pair.nextItem()) {
                    tick = tick(price.asString().toFixedPointLong(FINE_TICKS_PER_DOLLAR, UNREADABLE));
                }
            }
            if (pair.hasNextItem()) {
                try (var size = pair.nextItem()) {
                    quantity = size.asString().toFixedPointLong(100, UNREADABLE);
                }
            }
        }
        if (tick > 0 && tick < TICKS_PER_DOLLAR && quantity > 0) {
            subject.level(side, tick * TICK_TO_PRICE, quantity * CENTS_TO_SIZE, 1);
        }
    }

    // The delta or trade being read.
    private Subject current;
    private int tick;
    private long amount;
    private long price;
    private Side side;
    private long timestamp;
    private boolean readable;

    private void delta(final JsonDecoder.JsonNode msg) {
        current = null;
        tick = 0;
        amount = 0;
        side = null;
        timestamp = NO_TIME;
        readable = true;
        try (var object = msg.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    deltaField(key);
                }
            }
        }
        if (current == null) {
            return;
        }
        // Skipped, a delta that can't be read would leave its level wrong for good: resync instead.
        if (!readable || side == null) {
            current.stale(GapReason.UNREADABLE, receiveNanos);
            return;
        }
        if (tick > 0 && tick < TICKS_PER_DOLLAR) {
            current.add(side, tick * TICK_TO_PRICE, amount * CENTS_TO_SIZE, 1);
        }
        current.endUpdate(timestamp, receiveNanos, NO_SEQUENCE);
    }

    private void deltaField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("market_ticker")) {
            current = table.get(key.asString());
        } else if (current == null) {
            return;
        } else if (name.equals("price_dollars")) {
            final long fine = key.asString().toFixedPointLong(FINE_TICKS_PER_DOLLAR, UNREADABLE);
            readable &= fine != UNREADABLE;
            tick = tick(fine);
        } else if (name.equals("delta_fp")) {
            amount = key.asString().toFixedPointLong(100, Long.MIN_VALUE);
            readable &= amount != Long.MIN_VALUE;
        } else if (name.equals("side")) {
            side = key.asString().equals("yes") ? Side.Bid : Side.Ask;
        } else if (name.equals("ts_ms")) {
            timestamp = key.asLong() * NANOS_PER_MILLI;
        }
    }

    private void trade(final JsonDecoder.JsonNode msg) {
        current = null;
        price = UNREADABLE;
        amount = UNREADABLE;
        side = Side.None;
        timestamp = NO_TIME;
        try (var object = msg.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    tradeField(key);
                }
            }
        }
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (current != null && price >= 0 && amount >= 0) {
            current.trade(price, amount, side, timestamp, receiveNanos);
        }
    }

    private void tradeField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("market_ticker")) {
            current = table.get(key.asString());
        } else if (current == null) {
            return;
        } else if (name.equals("yes_price_dollars")) {
            price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("count_fp")) {
            amount = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("taker_book_side")) {
            side = key.asString().equals("bid") ? Side.Bid : Side.Ask;
        } else if (name.equals("ts_ms")) {
            timestamp = key.asLong() * NANOS_PER_MILLI;
        }
    }

    /** The tick for a price read one digit finer: 0, so dropped, for a price off every tick or one unreadable. */
    private static int tick(final long fine) {
        return fine >= 0 && fine % 10 == 0 ? (int) (fine / 10) : 0;
    }
}
