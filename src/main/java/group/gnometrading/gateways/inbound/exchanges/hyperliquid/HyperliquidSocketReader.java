package group.gnometrading.gateways.inbound.exchanges.hyperliquid;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.JsonWebSocketReader;
import group.gnometrading.gateways.inbound.JsonWebSocketWriter;
import group.gnometrading.gateways.inbound.SocketWriter;
import group.gnometrading.gateways.inbound.WebSocketWriter;
import group.gnometrading.gateways.inbound.mbp.Mbp10Book;
import group.gnometrading.gateways.inbound.mbp.Mbp10SchemaFactory;
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

public final class HyperliquidSocketReader extends JsonWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

    private static final int MAX_LEVEL_DEPTH = 10;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private enum Channel {
        L2BOOK,
        TRADES,
        ADMIN
    }

    private final Mbp10Book book;
    private long lastTradePrice;
    private long lastTradeSize;
    private boolean initialTradesBatchReceived;

    public HyperliquidSocketReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (Mbp10Book) this.internalBook;

        this.lastTradePrice = Mbp10Encoder.priceNullValue();
        this.lastTradeSize = Mbp10Encoder.sizeNullValue();
    }

    @Override
    protected void keepAlive() throws IOException {
        // { "method": "ping" }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("method", "ping");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), true);
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        // Hyperliquid does not support snapshots
        return null;
    }

    @Override
    protected void handleJsonMessage(JsonDecoder.JsonNode node) {
        try (var obj = node.asObject()) {
            Channel channel = null;
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("channel")) {
                        final var channelName = key.asString();
                        if (channelName.equals("l2Book")) {
                            channel = Channel.L2BOOK;
                        } else if (channelName.equals("trades")) {
                            channel = Channel.TRADES;
                        } else {
                            channel = Channel.ADMIN;
                        }
                    } else if (key.getName().equals("data")) {
                        if (channel == Channel.L2BOOK) {
                            parseL2Book(key);
                        } else if (channel == Channel.TRADES) {
                            parseTrades(key);
                        } else {
                            // NO-OP: consume it
                        }
                    }
                }
            }
        }
    }

    private void parseL2BookKey(final JsonDecoder.JsonObject object) {
        try (var key = object.nextKey()) {
            if (key.getName().equals("coin")) {
                // NO-OP: Maybe check if it's correct in the future?
            } else if (key.getName().equals("time")) {
                // Hyperliquid is in epoch millis
                long timestamp = key.asLong() * NANOS_PER_MILLI;
                this.schema.encoder.timestampEvent(timestamp);
                this.schema.encoder.sequence(timestamp);
            } else if (key.getName().equals("levels")) {
                parseLevelsKey(key);
            }
        }
    }

    private void parseLevelsKey(final JsonDecoder.JsonNode key) {
        try (var array = key.asArray()) {
            this.schema.encoder.depth(parseLevels(array));
        }
    }

    private boolean parseLevel(final JsonDecoder.JsonArray levels, final Mbp10Book.PriceLevel existingLevel) {
        long price = Mbp10Encoder.askPrice0NullValue();
        long size = Mbp10Encoder.askSize0NullValue();
        long orderCount = Mbp10Encoder.askCount0NullValue();

        try (var levelNode = levels.nextItem();
                var level = levelNode.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals("px")) {
                        price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("sz")) {
                        size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    } else if (key.getName().equals("n")) {
                        orderCount = key.asLong();
                    }
                }
            }
        }

        return existingLevel.update(price, size, orderCount);
    }

    private void consumeRemainingItems(final JsonDecoder.JsonArray array) {
        while (array.hasNextItem()) {
            try (var levelNode = array.nextItem();
                    var level = levelNode.asObject()) {
                // Consume remaining items beyond MAX_LEVEL_DEPTH
            }
        }
    }

    private short parseLevels(final JsonDecoder.JsonArray array) {
        int depth = Mbp10Encoder.depthNullValue();
        try (var bidsNode = array.nextItem();
                var bids = bidsNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (bids.hasNextItem()) {
                    depth = parseLevel(bids, book.bids[i]) ? Math.min(depth, i) : depth;
                } else {
                    book.bids[i].reset();
                }
            }
            consumeRemainingItems(bids);
        }

        try (var asksNode = array.nextItem();
                var asks = asksNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (asks.hasNextItem()) {
                    depth = parseLevel(asks, book.asks[i]) ? Math.min(depth, i) : depth;
                } else {
                    book.asks[i].reset();
                }
            }
            consumeRemainingItems(asks);
        }
        return (short) depth;
    }

    private void parseL2Book(final JsonDecoder.JsonNode node) {
        prepareEncoder();

        this.schema.encoder.timestampEvent(Mbp10Encoder.timestampEventNullValue());
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());

        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);

        try (var object = node.asObject()) {
            while (object.hasNextKey()) {
                parseL2BookKey(object);
            }
        }

        this.book.writeTo(this.schema);
        offer();
    }

    private void parseTrade(final JsonDecoder.JsonObject trade, boolean emit) {
        Side side = Side.None;
        long timestampEvent = Mbp10Encoder.timestampEventNullValue();

        while (trade.hasNextKey()) {
            try (var key = trade.nextKey()) {
                if (key.getName().equals("side")) {
                    if (key.asString().equals("A")) {
                        side = Side.Ask;
                    } else {
                        side = Side.Bid;
                    }
                } else if (key.getName().equals("px")) {
                    this.lastTradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (key.getName().equals("sz")) {
                    this.lastTradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (key.getName().equals("time")) {
                    timestampEvent = key.asLong() * NANOS_PER_MILLI;
                }
            }
        }

        if (!emit) {
            return;
        }

        prepareEncoder();
        this.schema.encoder.timestampEvent(timestampEvent);
        this.schema.encoder.sequence(timestampEvent);
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Trade);
        this.schema.encoder.side(side);
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.schema.encoder.depth(
                Mbp10Encoder.depthNullValue()); // TODO: Do we want to send the correct depth? Is it worth?
        this.book.writeTo(this.schema);

        offer();
    }

    private void parseTrades(final JsonDecoder.JsonNode node) {
        boolean emit = this.initialTradesBatchReceived;
        this.initialTradesBatchReceived = true;
        try (var trades = node.asArray()) {
            while (trades.hasNextItem()) {
                try (var tradeNode = trades.nextItem();
                        var trade = tradeNode.asObject()) {
                    parseTrade(trade, emit);
                }
            }
        }
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(listing.exchange().exchangeId());
        this.schema.encoder.securityId(listing.security().securityId());
        this.schema.encoder.timestampSent(
                Mbp10Encoder.timestampSentNullValue()); // Hyperliquid only has event timestamps
        this.schema.encoder.timestampRecv(recvTimestamp);
    }

    private void writeSubscription(final String channel) {
        // { "method": "subscribe", "subscription": { "type": "<channel>", "coin": "<coin_symbol>" } }
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("method", "subscribe");

        jsonEncoder.writeComma();
        jsonEncoder.writeString("subscription");
        jsonEncoder.writeColon();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("type", channel);
        jsonEncoder.writeComma();
        jsonEncoder.writeObjectEntry("coin", this.listing.exchangeSecuritySymbol());
        jsonEncoder.writeObjectEnd();

        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }

    @Override
    protected void subscribe() throws IOException {
        this.initialTradesBatchReceived = false;
        this.writeSubscription("l2Book");
        this.writeSubscription("trades");
    }
}
