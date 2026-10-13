package group.gnometrading.gateways.inbound.exchanges.lighter;

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
import org.agrona.concurrent.EpochNanoClock;

public final class LighterInboundReader extends InboundJsonWebSocketReader<Mbp10Schema>
        implements MbpBufferSchemaFactory {

    private static final long NANOS_PER_MILLIS = 1_000_000L;
    private static final int MAX_LEVELS = 10;
    // What a venue number that can't be read parses to; every real price and size is non-negative.
    private static final long UNREADABLE = -1L;

    private final MbpBufferBook book;
    private final String orderBookChannel;
    private final String tradeChannel;
    private long lastTradePrice;
    private long lastTradeSize;
    private long lastSequenceNumber;
    // Whether this connection's first order book, the snapshot, has been read; every one after it is an update.
    // Reset when the supervisor subscribes, on its own thread, while the reader is paused.
    private volatile boolean snapshotRead;

    public LighterInboundReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            InboundSocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (MbpBufferBook) this.internalBook;

        this.orderBookChannel = "order_book/" + listing.exchangeSecurityId();
        this.tradeChannel = "trade/" + listing.exchangeSecurityId();

        this.lastTradePrice = Mbp10Encoder.priceNullValue();
        this.lastTradeSize = Mbp10Encoder.sizeNullValue();
        this.lastSequenceNumber = Mbp10Encoder.sequenceNullValue();
    }

    @Override
    protected void handleJsonMessage(JsonDecoder.JsonNode node) {
        boolean shouldOffer = false;
        long timestamp = Mbp10Encoder.timestampEventNullValue();

        try (var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("offset")) {
                        this.lastSequenceNumber = key.asLong();
                    } else if (key.getName().equals("order_book")) {
                        shouldOffer = parseOrderBook(key);
                    } else if (key.getName().equals("trades")) {
                        parseTrades(key);
                    } else if (key.getName().equals("type") && key.asString().equals("ping")) {
                        sendPong();
                    } else if (key.getName().equals("timestamp")) {
                        timestamp = key.asLong() * NANOS_PER_MILLIS;
                    } else {
                        // NO-OP: consume it
                    }
                }
            }
        }

        if (shouldOffer) {
            this.schema.encoder.timestampEvent(timestamp);
            offer();
        }
    }

    private void parseTrades(final JsonDecoder.JsonNode node) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem();
                        var obj = item.asObject()) {
                    parseTrade(obj);
                }
            }
        }
    }

    private void parseTrade(final JsonDecoder.JsonObject obj) {
        prepareEncoder();
        long timestamp = Mbp10Encoder.timestampEventNullValue();
        Side side = Side.None;
        long price = UNREADABLE;
        long size = UNREADABLE;

        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                if (key.getName().equals("price")) {
                    price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, UNREADABLE);
                } else if (key.getName().equals("size")) {
                    size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, UNREADABLE);
                } else if (key.getName().equals("timestamp")) {
                    timestamp = key.asLong() * NANOS_PER_MILLIS;
                } else if (key.getName().equals("is_maker_ask")) {
                    side = key.asBoolean() ? Side.Bid : Side.Ask; // is_maker_ask = true implies the aggressor was a bid
                } else {
                    // NO-OP: consume it
                }
            }
        }

        // A trade with a price or size that can't be read is neither published nor kept as the last trade.
        if (price == UNREADABLE || size == UNREADABLE) {
            return;
        }
        this.lastTradePrice = price;
        this.lastTradeSize = size;
        this.schema.encoder.timestampEvent(timestamp);
        this.schema.encoder.sequence(this.lastSequenceNumber);
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Trade);
        this.schema.encoder.side(side);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());

        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);

        offer();
    }

    private boolean parseOrderBook(final JsonDecoder.JsonNode node) {
        int depth = Mbp10Encoder.depthNullValue();
        final boolean snapshot = !this.snapshotRead;
        try (var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("asks")) {
                        depth = Math.min(depth, parseOrders(key, true, snapshot));
                    } else if (key.getName().equals("bids")) {
                        depth = Math.min(depth, parseOrders(key, false, snapshot));
                    } else {
                        // NO-OP: consume it
                    }
                }
            }
        }
        this.snapshotRead = true;
        if (depth == Mbp10Encoder.depthNullValue() || depth >= MAX_LEVELS) {
            return false;
        }

        prepareEncoder();

        // Timestamp event will be set in handleJsonMessage
        this.schema.encoder.sequence(this.lastSequenceNumber);
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth((short) depth);

        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);

        this.book.writeTo(this.schema);
        return true;
    }

    private int parseOrders(final JsonDecoder.JsonNode node, final boolean isAsk, final boolean snapshot) {
        int depth = Mbp10Encoder.depthNullValue();
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem();
                        var obj = item.asObject()) {
                    depth = Math.min(depth, parseOrder(obj, isAsk, snapshot));
                }
            }
        }
        return depth;
    }

    /**
     * A snapshot level that can't be read is left out: thrown, the reconnect would bring the same snapshot back. An
     * update that can't be read throws, for a fresh snapshot: skipped, the level would stay wrong.
     */
    private int parseOrder(final JsonDecoder.JsonObject obj, final boolean isAsk, final boolean snapshot) {
        long price = 0;
        long size = 0;
        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                if (key.getName().equals("price")) {
                    price = parseAmount(key.asString(), Statics.PRICE_SCALING_FACTOR, snapshot);
                } else if (key.getName().equals("size")) {
                    size = parseAmount(key.asString(), Statics.SIZE_SCALING_FACTOR, snapshot);
                } else {
                    // NO-OP: consume it
                }
            }
        }
        if (price == UNREADABLE || size == UNREADABLE) {
            return Mbp10Encoder.depthNullValue();
        }
        if (isAsk) {
            return this.book.updateAsk(price, size, 1);
        } else {
            return this.book.updateBid(price, size, 1);
        }
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(listing.exchange().exchangeId());
        this.schema.encoder.securityId(listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(recvTimestamp);
    }

    private void writeSubscription(final String channel) {
        final InboundJsonWebSocketWriter jsonInboundWebSocketWriter = (InboundJsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonInboundWebSocketWriter.getJsonEncoder();
        jsonEncoder
                .writeObjectStart()
                .writeObjectEntry("type", "subscribe")
                .writeComma()
                .writeObjectEntry("channel", channel)
                .writeObjectEnd();

        ((InboundWebSocketWriter) this.socketWriter)
                .writeText(jsonInboundWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }

    private static long parseAmount(final GnomeString text, final long scale, final boolean lenient) {
        return lenient ? text.toFixedPointLong(scale, UNREADABLE) : text.toFixedPointLong(scale);
    }

    @Override
    protected void subscribe() throws IOException {
        this.snapshotRead = false;
        // { "type": "subscribe", "channel": "order_book/{MARKET_INDEX}"}
        // { "type": "subscribe", "channel": "trade/{MARKET_INDEX}" }
        this.writeSubscription(this.orderBookChannel);
        this.writeSubscription(this.tradeChannel);
    }

    private void sendPong() {
        // { "type": "pong" }
        final InboundJsonWebSocketWriter jsonInboundWebSocketWriter = (InboundJsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonInboundWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "pong");
        jsonEncoder.writeObjectEnd();

        ((InboundWebSocketWriter) this.socketWriter)
                .writeText(jsonInboundWebSocketWriter.getAndFlipJsonBodyBuffer(), true);
    }

    @Override
    protected void keepAlive() throws IOException {
        // { "type": "ping" }
        final InboundJsonWebSocketWriter jsonInboundWebSocketWriter = (InboundJsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonInboundWebSocketWriter.getJsonEncoder();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "ping");
        jsonEncoder.writeObjectEnd();

        ((InboundWebSocketWriter) this.socketWriter)
                .writeText(jsonInboundWebSocketWriter.getAndFlipJsonBodyBuffer(), true);
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        return null;
    }
}
