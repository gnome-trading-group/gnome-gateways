package group.gnometrading.gateways.inbound.exchanges.lighter;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.gateways.inbound.*;
import group.gnometrading.gateways.inbound.mbp.buffer.MBPBufferBook;
import group.gnometrading.gateways.inbound.mbp.buffer.MBPBufferSchemaFactory;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.*;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;

public class LighterSocketReader extends JSONWebSocketReader<MBP10Schema> implements MBPBufferSchemaFactory {

    private static final long NANOS_PER_MILLIS = 1_000_000L;
    private static final int MAX_LEVELS = 10;

    private final MBPBufferBook book;
    private final String orderBookChannel, tradeChannel;

    private long lastTradePrice, lastTradeSize, lastSequenceNumber;

    public LighterSocketReader(
            Logger logger,
            RingBuffer<MBP10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder
    ) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (MBPBufferBook) this.internalBook;

        this.orderBookChannel = "order_book/" + listing.exchangeSecurityId();
        this.tradeChannel = "trade/" + listing.exchangeSecurityId();

        this.lastTradePrice = MBP10Encoder.priceNullValue();
        this.lastTradeSize = MBP10Encoder.sizeNullValue();
        this.lastSequenceNumber = MBP10Encoder.sequenceNullValue();
    }

    @Override
    protected void handleJSONMessage(JSONDecoder.JSONNode node) {
        boolean shouldOffer = false;
        long timestamp = MBP10Encoder.timestampEventNullValue();

        try (final var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (final var key = obj.nextKey()) {
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

    private void parseTrades(final JSONDecoder.JSONNode node) {
        try (final var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (final var item = array.nextItem(); final var obj = item.asObject()) {
                    parseTrade(obj);
                }
            }
        }
    }

    private void parseTrade(final JSONDecoder.JSONObject obj) {
        prepareEncoder();
        long timestamp = MBP10Encoder.timestampEventNullValue();
        Side side = Side.None;

        while (obj.hasNextKey()) {
            try (final var key = obj.nextKey()) {
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
        this.schema.encoder.depth(MBP10Encoder.depthNullValue());

        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);

        offer();
    }

    private boolean parseOrderBook(final JSONDecoder.JSONNode node) {
        int depth = MBP10Encoder.depthNullValue();
        try (final var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (final var key = obj.nextKey()) {
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
        if (depth == MBP10Encoder.depthNullValue() || depth >= MAX_LEVELS) {
            return false;
        }

        prepareEncoder();

        // Timestamp event will be set in handleJSONMessage
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

    private int parseOrders(final JSONDecoder.JSONNode node, final boolean isAsk) {
        int depth = MBP10Encoder.depthNullValue();
        try (final var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (final var item = array.nextItem(); final var obj = item.asObject()) {
                    depth = Math.min(depth, parseOrder(obj, isAsk));
                }
            }
        }
        return depth;
    }

    private int parseOrder(final JSONDecoder.JSONObject obj, final boolean isAsk) {
        long price = 0, size = 0;
        while (obj.hasNextKey()) {
            try (final var key = obj.nextKey()) {
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
        this.schema.encoder.exchangeId(listing.exchangeId());
        this.schema.encoder.securityId(listing.securityId());
        this.schema.encoder.timestampSent(MBP10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(recvTimestamp);
    }

    private void writeSubscription(final String channel) {
        final JSONWebSocketWriter jsonWebSocketWriter = (JSONWebSocketWriter) this.socketWriter;
        final JSONEncoder jsonEncoder = jsonWebSocketWriter.getJSONEncoder();
        jsonEncoder
                .writeObjectStart()
                .writeObjectEntry("type", "subscribe")
                .writeComma()
                .writeObjectEntry("channel", channel)
                .writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJSONBodyBuffer(), false);
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
        final JSONWebSocketWriter jsonWebSocketWriter = (JSONWebSocketWriter) this.socketWriter;
        final JSONEncoder jsonEncoder = jsonWebSocketWriter.getJSONEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "pong");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJSONBodyBuffer(), true);
    }

    @Override
    protected void keepAlive() throws IOException {
        // { "type": "ping" }
        final JSONWebSocketWriter jsonWebSocketWriter = (JSONWebSocketWriter) this.socketWriter;
        final JSONEncoder jsonEncoder = jsonWebSocketWriter.getJSONEncoder();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", "ping");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJSONBodyBuffer(), true);
    }

    @Override
    public Book<MBP10Schema> fetchSnapshot() throws IOException {
        return null;
    }
}
