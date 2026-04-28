package group.gnometrading.gateways.inbound.exchanges.lighter;

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
import java.io.IOException;
import org.agrona.concurrent.EpochNanoClock;

public final class LighterSocketReader extends JsonWebSocketReader<Mbp10Schema> implements MbpBufferSchemaFactory {

    private static final long NANOS_PER_MILLIS = 1_000_000L;
    private static final int MAX_LEVELS = 10;

    private final MbpBufferBook book;
    private final String orderBookChannel;
    private final String tradeChannel;
    private long lastTradePrice;
    private long lastTradeSize;
    private long lastSequenceNumber;

    public LighterSocketReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
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

        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                if (key.getName().equals("price")) {
                    this.lastTradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (key.getName().equals("size")) {
                    this.lastTradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (key.getName().equals("timestamp")) {
                    timestamp = key.asLong() * NANOS_PER_MILLIS;
                } else if (key.getName().equals("is_maker_ask")) {
                    side = key.asBoolean() ? Side.Bid : Side.Ask; // is_maker_ask = true implies the aggressor was a bid
                } else {
                    // NO-OP: consume it
                }
            }
        }

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
        try (var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("asks")) {
                        depth = Math.min(depth, parseOrders(key, true));
                    } else if (key.getName().equals("bids")) {
                        depth = Math.min(depth, parseOrders(key, false));
                    } else {
                        // NO-OP: consume it
                    }
                }
            }
        }
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

    private int parseOrders(final JsonDecoder.JsonNode node, final boolean isAsk) {
        int depth = Mbp10Encoder.depthNullValue();
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem();
                        var obj = item.asObject()) {
                    depth = Math.min(depth, parseOrder(obj, isAsk));
                }
            }
        }
        return depth;
    }

    private int parseOrder(final JsonDecoder.JsonObject obj, final boolean isAsk) {
        long price = 0;
        long size = 0;
        while (obj.hasNextKey()) {
            try (var key = obj.nextKey()) {
                if (key.getName().equals("price")) {
                    price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (key.getName().equals("size")) {
                    size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else {
                    // NO-OP: consume it
                }
            }
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
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();
        jsonEncoder
                .writeObjectStart()
                .writeObjectEntry("type", "subscribe")
                .writeComma()
                .writeObjectEntry("channel", channel)
                .writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }

    @Override
    protected void subscribe() throws IOException {
        // { "type": "subscribe", "channel": "order_book/{MARKET_INDEX}"}
        // { "type": "subscribe", "channel": "trade/{MARKET_INDEX}" }
        this.writeSubscription(this.orderBookChannel);
        this.writeSubscription(this.tradeChannel);
    }

    private void sendPong() {
        // { "type": "pong" }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "pong");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), true);
    }

    @Override
    protected void keepAlive() throws IOException {
        // { "type": "ping" }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "ping");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), true);
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        return null;
    }
}
