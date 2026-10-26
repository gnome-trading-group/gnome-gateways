package group.gnometrading.gateways.venues.polymarket.intl;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polymarket's market websocket, many outcome tokens per connection. The first command on a connection subscribes
 * with {@code type: market}; later ones add and drop tokens with {@code operation: subscribe/unsubscribe}, each
 * answered with a {@code book} for just the new tokens. Commands carry at most 100 tokens: an opening subscribe of
 * ~700 or more silently loses its books (measured 2026-10-26). A token is resynced by unsubscribing and subscribing it.
 *
 * <p>Frames are one event or an array of them: {@code book} (a token's whole book), {@code price_change} (level
 * changes, possibly for several tokens, each with the venue's best bid and ask after it), {@code last_trade_price}.
 * Changes for tokens not on the connection, including a subscribed token's complement, are skipped. There is no
 * sequence number; instead the book's best prices are checked against the venue's once a match's changes are all in.
 * Plain-text frames ({@code PONG}, {@code NO NEW ASSETS}, {@code INVALID ASSET IDS}) are ignored. Kept alive with
 * text {@code PING}s. Assumes {@code asset_id} precedes a book's levels, as Polymarket sends them.
 */
public final class PolymarketIntlProtocol implements VenueProtocol<WebSocketChannel> {

    private static final int TOKENS_PER_COMMAND = 100;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NO_TIME = Mbp10Encoder.timestampEventNullValue();
    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    private static final long UNREADABLE = Long.MIN_VALUE;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);
    // Changes in one event are for few tokens, a market's pair in practice; more grows the arrays once.
    private static final int INITIAL_TOUCHED = 16;

    /** A token's pending check: the venue's best prices after a match, and the book's then. */
    private static final class Check {
        boolean pending;
        long timestamp;
        long venueBid;
        long venueAsk;
        long bookBid;
        long bookAsk;
    }

    private final SubjectTable table;
    // By token, so a check carries over when a roll swaps the market. Added by the supervisor when a token is
    // subscribed, read by the reader.
    private final Map<String, Check> checks = new ConcurrentHashMap<>();
    private final ByteBuffer ping = ByteBuffer.wrap(PING);
    // Supervisor thread: whether this connection's opening subscribe has gone out.
    private boolean opened;

    public PolymarketIntlProtocol(final SubjectTable table) {
        this.table = table;
    }

    @Override
    public Books books() {
        return Books.CHANGES;
    }

    @Override
    public int framesPerCommand() {
        // A resync unsubscribes then subscribes again.
        return 2;
    }

    @Override
    public int maxSubjectsPerCommand() {
        return TOKENS_PER_COMMAND;
    }

    @Override
    public void onConnected() {
        opened = false;
    }

    @Override
    public void subscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            checks.computeIfAbsent(subject.key(), k -> new Check()).pending = false;
        }
        if (opened) {
            operation(channel.writer(), "subscribe", subjects);
            return;
        }
        // { "type": "market", "assets_ids": [...], "custom_feature_enabled": true }
        opened = true;
        final JsonEncoder json = channel.writer().json();
        json.writeObjectStart();
        json.writeObjectEntry("type", "market");
        json.writeComma();
        assets(json, subjects);
        json.writeComma();
        json.writeObjectEntry("custom_feature_enabled", true);
        json.writeObjectEnd();
        channel.writer().sendJson();
    }

    @Override
    public void unsubscribe(final WebSocketChannel channel, final List<Subject> subjects) {
        if (!opened) {
            return;
        }
        operation(channel.writer(), "unsubscribe", subjects);
        for (Subject subject : subjects) {
            checks.remove(subject.key());
        }
    }

    @Override
    public void resync(final WebSocketChannel channel, final List<Subject> subjects) {
        if (!opened) {
            return;
        }
        operation(channel.writer(), "unsubscribe", subjects);
        operation(channel.writer(), "subscribe", subjects);
    }

    @Override
    public void keepAlive(final WebSocketChannel channel) {
        ping.rewind();
        channel.writer().sendText(ping, true);
    }

    private static void operation(final WebSocketWriter writer, final String operation, final List<Subject> subjects) {
        // { "operation": "subscribe", "assets_ids": [...], "custom_feature_enabled": true }
        final JsonEncoder json = writer.json();
        json.writeObjectStart();
        json.writeObjectEntry("operation", operation);
        json.writeComma();
        assets(json, subjects);
        json.writeComma();
        json.writeObjectEntry("custom_feature_enabled", true);
        json.writeObjectEnd();
        writer.sendJson();
    }

    private static void assets(final JsonEncoder json, final List<Subject> subjects) {
        json.writeString("assets_ids");
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

    private enum Type {
        OTHER,
        BOOK,
        PRICE_CHANGE,
        TRADE
    }

    private final JsonDecoder decoder = new JsonDecoder();
    private long receiveNanos;
    // The event being read.
    private Type type;
    private long timestamp;
    private Subject eventSubject;
    private boolean snapshotStarted;
    private long tradePrice;
    private long tradeSize;
    private Side tradeSide;
    private Subject[] touched = new Subject[INITIAL_TOUCHED];
    private long[] touchedBid = new long[INITIAL_TOUCHED];
    private long[] touchedAsk = new long[INITIAL_TOUCHED];
    private int touchedCount;

    @Override
    public void onFrame(final ByteBuffer frame, final long receive) {
        receiveNanos = receive;
        final byte first = frame.get(frame.position());
        if (first != '{' && first != '[') {
            frame.position(frame.limit());
            return;
        }
        try (var node = decoder.wrap(frame)) {
            if (node.isArray()) {
                events(node);
            } else {
                event(node);
            }
        }
    }

    private void events(final JsonDecoder.JsonNode node) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem()) {
                    event(item);
                }
            }
        }
    }

    private void event(final JsonDecoder.JsonNode node) {
        type = Type.OTHER;
        timestamp = NO_TIME;
        eventSubject = null;
        snapshotStarted = false;
        tradePrice = UNREADABLE;
        tradeSize = UNREADABLE;
        tradeSide = Side.None;
        touchedCount = 0;
        try (var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    eventField(key);
                }
            }
        }
        // Nothing is published without the venue's time.
        if (timestamp == NO_TIME) {
            return;
        }
        switch (type) {
            case BOOK -> finishBook();
            case PRICE_CHANGE -> finishChanges();
            case TRADE -> finishTrade();
            default -> {
                // tick_size_change, best_bid_ask, new_market: the raw capture keeps them.
            }
        }
    }

    private void eventField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("event_type")) {
            type = type(key.asString());
        } else if (name.equals("asset_id")) {
            eventSubject = table.get(key.asString());
        } else if (name.equals("timestamp")) {
            final long millis = key.asString().toFixedPointLong(1L, -1);
            timestamp = millis < 0 ? NO_TIME : millis * NANOS_PER_MILLI;
        } else if (name.equals("bids")) {
            bookSide(key, Side.Bid);
        } else if (name.equals("asks")) {
            bookSide(key, Side.Ask);
        } else if (name.equals("price_changes")) {
            changes(key);
        } else {
            tradeField(name, key);
        }
    }

    private void tradeField(final GnomeString name, final JsonDecoder.JsonNode key) {
        if (name.equals("price")) {
            tradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("size")) {
            tradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("side")) {
            tradeSide = key.asString().equals("BUY") ? Side.Bid : Side.Ask;
        }
    }

    /** A book event's side; the first one starts the snapshot, since only book events carry levels. */
    private void bookSide(final JsonDecoder.JsonNode key, final Side side) {
        if (eventSubject == null) {
            return;
        }
        if (!snapshotStarted) {
            snapshotStarted = true;
            eventSubject.beginSnapshot();
        }
        levels(key, side);
    }

    private static Type type(final GnomeString value) {
        if (value.equals("book")) {
            return Type.BOOK;
        } else if (value.equals("price_change")) {
            return Type.PRICE_CHANGE;
        }
        return value.equals("last_trade_price") ? Type.TRADE : Type.OTHER;
    }

    /** A level that can't be read is left out of the book; thrown, a resync would bring the same book back. */
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
                    if (key.getName().equals("price")) {
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
                    } else if (key.getName().equals("size")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
                    }
                }
            }
        }
        if (price != UNREADABLE && size != UNREADABLE) {
            eventSubject.level(side, price, size, 1);
        }
    }

    private void finishBook() {
        if (eventSubject == null) {
            return;
        }
        if (!snapshotStarted) {
            // A book with no levels at all: the market is empty.
            eventSubject.beginSnapshot();
        }
        // A whole book replaces whatever the check was waiting to compare.
        final Check check = checks.get(eventSubject.key());
        if (check != null) {
            check.pending = false;
        }
        eventSubject.endSnapshot(timestamp, receiveNanos, NO_SEQUENCE);
    }

    private void finishTrade() {
        // A trade with a price or size that can't be read isn't published: it would read as a real trade.
        if (eventSubject != null && tradePrice != UNREADABLE && tradeSize != UNREADABLE) {
            eventSubject.trade(tradePrice, tradeSize, tradeSide, timestamp, receiveNanos);
        }
    }

    private void changes(final JsonDecoder.JsonNode node) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var changeNode = array.nextItem()) {
                    change(changeNode);
                }
            }
        }
    }

    // The change being read.
    private Subject changeSubject;
    private long changePrice;
    private long changeSize;
    private long changeBestBid;
    private long changeBestAsk;
    private Side changeSide;

    private void change(final JsonDecoder.JsonNode changeNode) {
        changeSubject = null;
        changePrice = UNREADABLE;
        changeSize = UNREADABLE;
        changeBestBid = UNREADABLE;
        changeBestAsk = UNREADABLE;
        changeSide = null;
        try (var change = changeNode.asObject()) {
            while (change.hasNextKey()) {
                try (var key = change.nextKey()) {
                    changeField(key);
                }
            }
        }
        if (changeSubject == null || !changeSubject.accepting()) {
            return;
        }
        // Skipped, a change that can't be read would leave its level wrong until the venue next touches it.
        if (changePrice == UNREADABLE || changeSize == UNREADABLE || changeSide == null) {
            changeSubject.stale(GapReason.UNREADABLE, receiveNanos);
            return;
        }
        changeSubject.set(changeSide, changePrice, changeSize, 1);
        touch(changeSubject, changeBestBid, changeBestAsk);
    }

    private void changeField(final JsonDecoder.JsonNode key) {
        final GnomeString name = key.getName();
        if (name.equals("asset_id")) {
            changeSubject = table.get(key.asString());
        } else if (name.equals("price")) {
            changePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("size")) {
            changeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("side")) {
            final GnomeString side = key.asString();
            changeSide = side.equals("BUY") ? Side.Bid : side.equals("SELL") ? Side.Ask : null;
        } else if (name.equals("best_bid")) {
            changeBestBid = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        } else if (name.equals("best_ask")) {
            changeBestAsk = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
        }
    }

    /** Notes a token this event changed, with the venue's best prices after its last change. */
    private void touch(final Subject subject, final long bestBid, final long bestAsk) {
        for (int i = 0; i < touchedCount; i++) {
            if (touched[i] == subject) {
                touchedBid[i] = bestBid;
                touchedAsk[i] = bestAsk;
                return;
            }
        }
        if (touchedCount == touched.length) {
            touched = Arrays.copyOf(touched, touchedCount * 2);
            touchedBid = Arrays.copyOf(touchedBid, touchedCount * 2);
            touchedAsk = Arrays.copyOf(touchedAsk, touchedCount * 2);
        }
        touched[touchedCount] = subject;
        touchedBid[touchedCount] = bestBid;
        touchedAsk[touchedCount] = bestAsk;
        touchedCount++;
    }

    private void finishChanges() {
        for (int i = 0; i < touchedCount; i++) {
            final Subject subject = touched[i];
            if (checkBestPrices(subject, touchedBid[i], touchedAsk[i])) {
                subject.endUpdate(timestamp, receiveNanos, NO_SEQUENCE);
            }
            touched[i] = null;
        }
    }

    /**
     * The venue sends, with every change, its best bid and ask once the match that caused it has fully applied. One
     * match's changes can come as several messages with one timestamp, so mid-match the book and those prices can
     * disagree for a moment; once the timestamp moves on they must agree, or the book missed a change. (Measured over
     * 19,624 matches on two busy markets: they always agreed.) Returns whether the book is still trusted.
     */
    private boolean checkBestPrices(final Subject subject, final long venueBid, final long venueAsk) {
        final Check check = checks.get(subject.key());
        if (check == null) {
            return true;
        }
        if (check.pending && timestamp != check.timestamp) {
            check.pending = false;
            if (check.bookBid != check.venueBid || check.bookAsk != check.venueAsk) {
                subject.stale(GapReason.BEST_PRICE, receiveNanos);
                return false;
            }
        }
        if (venueBid != UNREADABLE && venueAsk != UNREADABLE) {
            check.pending = true;
            check.timestamp = timestamp;
            check.venueBid = venueBid;
            check.venueAsk = venueAsk;
            // The venue's word for an empty side: no bid is 0, no ask is 1.
            check.bookBid = subject.book().bids().best(0L);
            check.bookAsk = subject.book().asks().best(Statics.PRICE_SCALING_FACTOR);
        }
        return true;
    }
}
