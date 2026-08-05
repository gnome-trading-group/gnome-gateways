package group.gnometrading.gateways.inbound.exchanges.polymarket;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.JsonWebSocketReader;
import group.gnometrading.gateways.inbound.JsonWebSocketWriter;
import group.gnometrading.gateways.inbound.SocketWriter;
import group.gnometrading.gateways.inbound.WebSocketWriter;
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

public final class PolymarketSocketReader extends JsonWebSocketReader<Mbp10Schema> implements MbpBufferSchemaFactory {

    private static final int MAX_BOOK_LEVELS = 1 << 10;
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

        private void reset() {
            this.type = EVENT_TYPE_UNKNOWN;
            this.timestamp = Mbp10Encoder.timestampEventNullValue();
            this.price = Mbp10Encoder.priceNullValue();
            this.size = Mbp10Encoder.sizeNullValue();
            this.side = Side.None;
            this.snapshotInitialized = false;
        }
    }

    private final MbpBufferBook book;
    private final ParsedEvent parsedEvent;
    private final ByteBuffer pingBuffer;
    private final String tokenId;
    private long lastTradePrice;
    private long lastTradeSize;

    public PolymarketSocketReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
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
        ((WebSocketWriter) this.socketWriter).writeText(this.pingBuffer, true);
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
            event.price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("price_changes")) {
            parsePriceChanges(key);
        } else if (name.equals("size")) {
            event.size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
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

    private void emitParsedEvent(final ParsedEvent event) {
        switch (event.type) {
            case EVENT_TYPE_BOOK -> {
                if (event.price != Mbp10Encoder.priceNullValue()) {
                    this.lastTradePrice = event.price;
                }
                emit(event.timestamp, Action.Modify, Side.None, this.lastTradePrice, this.lastTradeSize);
            }
            case EVENT_TYPE_PRICE_CHANGE -> emit(
                    event.timestamp, Action.Modify, Side.None, this.lastTradePrice, this.lastTradeSize);
            case EVENT_TYPE_LAST_TRADE -> {
                if (event.price != Mbp10Encoder.priceNullValue()) {
                    this.lastTradePrice = event.price;
                }
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

    private long parseTimestamp(final JsonDecoder.JsonNode node) {
        return node.asString().toFixedPointLong(1L) * NANOS_PER_MILLI;
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
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("size")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    }
                }
            }
        }

        updateLevel(isBid, price, size);
    }

    private void parsePriceChanges(final JsonDecoder.JsonNode node) {
        try (var changes = node.asArray()) {
            while (changes.hasNextItem()) {
                try (var changeNode = changes.nextItem();
                        var change = changeNode.asObject()) {
                    parsePriceChange(change);
                }
            }
        }
    }

    private void parsePriceChange(final JsonDecoder.JsonObject change) {
        boolean matchesToken = false;
        long price = Mbp10Encoder.askPrice0NullValue();
        long size = Mbp10Encoder.askSize0NullValue();
        boolean isBid = false;
        boolean sideParsed = false;

        while (change.hasNextKey()) {
            try (var key = change.nextKey()) {
                final GnomeString name = key.getName();
                if (name.equals("asset_id")) {
                    matchesToken = key.asString().equals(this.tokenId);
                } else if (name.equals("price")) {
                    price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (name.equals("size")) {
                    size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("side")) {
                    final GnomeString side = key.asString();
                    isBid = side.equals("BUY");
                    sideParsed = isBid || side.equals("SELL");
                }
            }
        }

        if (!matchesToken || !sideParsed) {
            return;
        }
        updateLevel(isBid, price, size);
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
        // { "assets_ids": ["<token_id>"], "type": "market" }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();

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

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }
}
