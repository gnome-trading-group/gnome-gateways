package group.gnometrading.gateways.inbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketReader;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.InboundSocketWriter;
import group.gnometrading.gateways.inbound.InboundWebSocketWriter;
import group.gnometrading.gateways.inbound.mbp.buffer.MbpBufferBook;
import group.gnometrading.gateways.inbound.mbp.buffer.MbpBufferSchemaFactory;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Encoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.EpochNanoClock;

public final class PolymarketIntlInboundReader extends InboundJsonWebSocketReader<Mbp10Schema>
        implements MbpBufferSchemaFactory {

    private static final int MAX_BOOK_LEVELS = 1 << 10;
    private static final long NO_VENUE_BEST = -1L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);
    private static final byte EVENT_TYPE_UNKNOWN = 0;
    private static final byte EVENT_TYPE_BOOK = 1;
    private static final byte EVENT_TYPE_PRICE_CHANGE = 2;
    private static final byte EVENT_TYPE_LAST_TRADE = 3;

    private static final class ParsedEvent {
        private byte type;
        private long timestamp;
        private long price;
        private long size;
        private Side side;
        private boolean snapshotInitialized;
        // The venue's best prices after the last of this message's changes to the asset, if it had any.
        private boolean hasVenueBest;
        private long venueBestBid;
        private long venueBestAsk;

        private void reset() {
            this.type = EVENT_TYPE_UNKNOWN;
            this.timestamp = Mbp10Encoder.timestampEventNullValue();
            this.price = Mbp10Encoder.priceNullValue();
            this.size = Mbp10Encoder.sizeNullValue();
            this.side = Side.None;
            this.snapshotInitialized = false;
            this.hasVenueBest = false;
        }
    }

    private final MbpBufferBook book;
    // The last match whose best prices still have to be checked, once its timestamp has passed. Reset by the
    // supervisor's subscribe, on its own thread, while the reader is paused.
    private volatile boolean pendingCheck;
    private long pendingTimestamp;
    private long pendingVenueBid;
    private long pendingVenueAsk;
    private long pendingBookBid;
    private long pendingBookAsk;
    // The price change being read; fields rather than locals so that reading one allocates nothing.
    private boolean changeMatchesToken;
    private long changePrice;
    private long changeSize;
    private long changeBestBid;
    private long changeBestAsk;
    private boolean changeIsBid;
    private boolean changeSideParsed;
    private final ParsedEvent parsedEvent;
    private final ByteBuffer pingBuffer;
    private final String tokenId;
    private long lastTradePrice;
    private long lastTradeSize;

    public PolymarketIntlInboundReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            InboundSocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (MbpBufferBook) this.internalBook;
        this.parsedEvent = new ParsedEvent();
        this.pingBuffer = ByteBuffer.wrap(PING);
        // exchangeSecurityId is "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIndex = exchangeSecurityId.indexOf(':');
        this.tokenId = colonIndex >= 0 ? exchangeSecurityId.substring(colonIndex + 1) : exchangeSecurityId;

        this.lastTradePrice = Mbp10Encoder.priceNullValue();
        this.lastTradeSize = Mbp10Encoder.sizeNullValue();
    }

    @Override
    public MbpBufferBook createBook() {
        return new MbpBufferBook(MAX_BOOK_LEVELS);
    }

    @Override
    protected void keepAlive() throws IOException {
        this.pingBuffer.rewind();
        ((InboundWebSocketWriter) this.socketWriter).writeText(this.pingBuffer, true);
    }

    @Override
    protected boolean handleNonJsonMessage(final ByteBuffer buffer) {
        if (buffer.remaining() == 4
                && buffer.get(buffer.position()) == 'P'
                && buffer.get(buffer.position() + 1) == 'O'
                && buffer.get(buffer.position() + 2) == 'N'
                && buffer.get(buffer.position() + 3) == 'G') {
            return true;
        }
        if (buffer.hasRemaining() && buffer.get(buffer.position()) == '[') {
            parseEventArray(buffer);
            return true;
        }
        return false;
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        // Polymarket sends a full book snapshot via WebSocket upon subscription.
        // It is captured in the replay buffer before connect() unpauses the reader.
        return null;
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonNode node) {
        try (var obj = node.asObject()) {
            parseEventObject(obj);
        }
    }

    private void parseEventArray(final ByteBuffer buffer) {
        try (var node = this.jsonDecoder.wrap(buffer);
                var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem();
                        var obj = item.asObject()) {
                    parseEventObject(obj);
                }
            }
        }
    }

    private void parseEventObject(final JsonDecoder.JsonObject obj) {
        final ParsedEvent event = this.parsedEvent;
        event.reset();

        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                parseEventKey(key.getName(), key, event);
            }
        }
        emitParsedEvent(event);
    }

    private void parseEventKey(final GnomeString name, final JsonDecoder.JsonNode key, final ParsedEvent event) {
        if (name.equals("event_type")) {
            event.type = parseEventType(key.asString());
        } else if (name.equals("timestamp")) {
            event.timestamp = parseTimestamp(key);
        } else if (name.equals("bids")) {
            parseSnapshotEventSide(key, true, event);
        } else if (name.equals("asks")) {
            parseSnapshotEventSide(key, false, event);
        } else if (isEventPrice(name)) {
            event.price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, Mbp10Encoder.priceNullValue());
        } else if (name.equals("price_changes")) {
            parsePriceChanges(key, event);
        } else if (name.equals("size")) {
            event.size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, Mbp10Encoder.sizeNullValue());
        } else if (name.equals("side")) {
            event.side = key.asString().equals("BUY") ? Side.Bid : Side.Ask;
        }
    }

    private byte parseEventType(final GnomeString value) {
        if (value.equals("book")) {
            return EVENT_TYPE_BOOK;
        } else if (value.equals("price_change")) {
            return EVENT_TYPE_PRICE_CHANGE;
        } else if (value.equals("last_trade_price")) {
            return EVENT_TYPE_LAST_TRADE;
        }
        return EVENT_TYPE_UNKNOWN;
    }

    private boolean isEventPrice(final GnomeString name) {
        return name.equals("last_trade_price") || name.equals("price");
    }

    private void parseSnapshotEventSide(final JsonDecoder.JsonNode key, final boolean isBid, final ParsedEvent event) {
        if (!event.snapshotInitialized) {
            this.book.reset();
            event.snapshotInitialized = true;
        }
        parseSnapshotSide(key, isBid);
    }

    /**
     * Venue numbers that don't parse are left null rather than thrown: a market that has never traded sends an empty
     * last_trade_price, and a throw here reconnects, gets the same snapshot, and throws again.
     */
    private void emitParsedEvent(final ParsedEvent event) {
        // A book is never published without the venue's time.
        if (event.timestamp == Mbp10Encoder.timestampEventNullValue()) {
            return;
        }
        switch (event.type) {
            case EVENT_TYPE_BOOK -> {
                // A whole book replaces what the check was waiting to compare.
                this.pendingCheck = false;
                if (event.price != Mbp10Encoder.priceNullValue()) {
                    this.lastTradePrice = event.price;
                }
                emit(event.timestamp, Action.Modify, Side.None, this.lastTradePrice, this.lastTradeSize);
            }
            case EVENT_TYPE_PRICE_CHANGE -> {
                checkBestPrices(event);
                emit(event.timestamp, Action.Modify, Side.None, this.lastTradePrice, this.lastTradeSize);
            }
            case EVENT_TYPE_LAST_TRADE -> {
                if (event.price == Mbp10Encoder.priceNullValue()) {
                    return;
                }
                this.lastTradePrice = event.price;
                if (event.size != Mbp10Encoder.sizeNullValue()) {
                    this.lastTradeSize = event.size;
                }
                emit(event.timestamp, Action.Trade, event.side, event.price, event.size);
            }
            default -> {
                // Other event types are retained in the lossless archive.
            }
        }
    }

    /**
     * The venue sends, with every change, its own best bid and ask once the match that caused it has fully applied. One
     * match's changes can come as several messages sharing a timestamp, so mid-match the book and those prices can
     * disagree for a moment. Once the timestamp moves on, they must agree; if they don't, the book missed a change.
     * Measured live over 19,624 matches on two busy markets: they always agreed.
     */
    private void checkBestPrices(final ParsedEvent event) {
        if (this.pendingCheck && event.timestamp != this.pendingTimestamp) {
            this.pendingCheck = false;
            if (this.pendingBookBid != this.pendingVenueBid) {
                resyncBook(this.pendingVenueBid, this.pendingBookBid, GAP_BEST_PRICE);
            }
            if (this.pendingBookAsk != this.pendingVenueAsk) {
                resyncBook(this.pendingVenueAsk, this.pendingBookAsk, GAP_BEST_PRICE);
            }
        }
        if (event.hasVenueBest) {
            this.pendingCheck = true;
            this.pendingTimestamp = event.timestamp;
            this.pendingVenueBid = event.venueBestBid;
            this.pendingVenueAsk = event.venueBestAsk;
            // The venue's word for an empty side: no bid is 0, no ask is 1.
            this.pendingBookBid = this.book.bestBid(0L);
            this.pendingBookAsk = this.book.bestAsk(Statics.PRICE_SCALING_FACTOR);
        }
    }

    @Override
    protected void publishSuspectBook() {
        prepareEncoder();
        this.schema.encoder.timestampEvent(this.pendingTimestamp);
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.schema.encoder.flags().maybeBadBook(true);
        this.book.writeTo(this.schema);
        offer();
    }

    private long parseTimestamp(final JsonDecoder.JsonNode node) {
        final long millis = node.asString().toFixedPointLong(1L, -1);
        return millis < 0 ? Mbp10Encoder.timestampEventNullValue() : millis * NANOS_PER_MILLI;
    }

    private void emit(long timestampEvent, Action action, Side side, long price, long size) {
        prepareEncoder();
        this.schema.encoder.timestampEvent(timestampEvent);
        // Polymarket does not publish an order-book sequence number.
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(price);
        this.schema.encoder.size(size);
        this.schema.encoder.action(action);
        this.schema.encoder.side(side);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);
        offer();
    }

    private void parseSnapshotSide(final JsonDecoder.JsonNode node, final boolean isBid) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                parseLevel(array, isBid);
            }
        }
    }

    private void parseLevel(final JsonDecoder.JsonArray array, final boolean isBid) {
        long price = Mbp10Encoder.askPrice0NullValue();
        long size = Mbp10Encoder.askSize0NullValue();

        try (var levelNode = array.nextItem();
                var levelObj = levelNode.asObject()) {
            while (levelObj.hasNextKey()) {
                try (var key = levelObj.nextKey()) {
                    if (key.getName().equals("price")) {
                        price = key.asString()
                                .toFixedPointLong(Statics.PRICE_SCALING_FACTOR, Mbp10Encoder.askPrice0NullValue());
                    } else if (key.getName().equals("size")) {
                        size = key.asString()
                                .toFixedPointLong(Statics.SIZE_SCALING_FACTOR, Mbp10Encoder.askSize0NullValue());
                    }
                }
            }
        }

        updateLevel(isBid, price, size);
    }

    private void parsePriceChanges(final JsonDecoder.JsonNode node, final ParsedEvent event) {
        try (var changes = node.asArray()) {
            while (changes.hasNextItem()) {
                try (var changeNode = changes.nextItem();
                        var change = changeNode.asObject()) {
                    parsePriceChange(change, event);
                }
            }
        }
    }

    /**
     * A change the book can't read throws: skipped, the level would stay wrong until the venue next touches it. The
     * reconnect that follows brings a fresh snapshot, and a change is never sent again, so it can't loop.
     */
    private void parsePriceChange(final JsonDecoder.JsonObject change, final ParsedEvent event) {
        this.changeMatchesToken = false;
        this.changePrice = Mbp10Encoder.askPrice0NullValue();
        this.changeSize = Mbp10Encoder.askSize0NullValue();
        this.changeBestBid = NO_VENUE_BEST;
        this.changeBestAsk = NO_VENUE_BEST;
        this.changeIsBid = false;
        this.changeSideParsed = false;
        while (change.hasNextKey()) {
            try (var key = change.nextKey()) {
                parseChangeKey(key.getName(), key);
            }
        }

        if (!this.changeMatchesToken || !this.changeSideParsed) {
            return;
        }
        updateLevel(this.changeIsBid, this.changePrice, this.changeSize);
        if (this.changeBestBid != NO_VENUE_BEST && this.changeBestAsk != NO_VENUE_BEST) {
            event.hasVenueBest = true;
            event.venueBestBid = this.changeBestBid;
            event.venueBestAsk = this.changeBestAsk;
        }
    }

    private void parseChangeKey(final GnomeString name, final JsonDecoder.JsonNode key) {
        if (name.equals("asset_id")) {
            this.changeMatchesToken = key.asString().equals(this.tokenId);
        } else if (name.equals("price")) {
            this.changePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("size")) {
            this.changeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("side")) {
            final GnomeString side = key.asString();
            this.changeIsBid = side.equals("BUY");
            this.changeSideParsed = this.changeIsBid || side.equals("SELL");
        } else if (name.equals("best_bid")) {
            this.changeBestBid = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("best_ask")) {
            this.changeBestAsk = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        }
    }

    private void updateLevel(final boolean isBid, final long price, final long size) {
        if (price == Mbp10Encoder.askPrice0NullValue() || size == Mbp10Encoder.askSize0NullValue()) {
            return;
        }
        if (isBid) {
            this.book.updateBid(price, size, 1L);
        } else {
            this.book.updateAsk(price, size, 1L);
        }
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(this.listing.exchange().exchangeId());
        this.schema.encoder.securityId(this.listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(this.recvTimestamp);
    }

    @Override
    protected void subscribe() throws IOException {
        this.pendingCheck = false;
        // { "assets_ids": ["<token_id>"], "type": "market" }
        final InboundJsonWebSocketWriter jsonInboundWebSocketWriter = (InboundJsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonInboundWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "market");
        jsonEncoder.writeComma();
        jsonEncoder.writeString("assets_ids");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString(this.tokenId);
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeComma();
        jsonEncoder.writeObjectEntry("custom_feature_enabled", true);
        jsonEncoder.writeObjectEnd();

        ((InboundWebSocketWriter) this.socketWriter)
                .writeText(jsonInboundWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }
}
