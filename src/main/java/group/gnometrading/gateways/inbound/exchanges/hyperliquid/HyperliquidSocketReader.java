package group.gnometrading.gateways.inbound.exchanges.hyperliquid;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.gateways.inbound.*;
import group.gnometrading.gateways.inbound.mbp.MBP10Book;
import group.gnometrading.gateways.inbound.mbp.MBP10SchemaFactory;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.*;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;

public class HyperliquidSocketReader extends JSONWebSocketReader<MBP10Schema> implements MBP10SchemaFactory {

    private static final int MAX_LEVEL_DEPTH = 10;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private enum Channel {
        L2BOOK,
        TRADES,
        ADMIN
    }

    private final MBP10Book book;
    private long lastTradePrice, lastTradeSize;

    public HyperliquidSocketReader(
            Logger logger,
            RingBuffer<MBP10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder
    ) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (MBP10Book) this.internalBook;
    }

    @Override
    protected void keepAlive() throws IOException {
        // { "method": "ping" }
        final JSONWebSocketWriter jsonWebSocketWriter = (JSONWebSocketWriter) this.socketWriter;
        final JSONEncoder jsonEncoder = jsonWebSocketWriter.getJSONEncoder();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("method", "ping");
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJSONBodyBuffer(), true);
    }

    @Override
    public Book<MBP10Schema> fetchSnapshot() throws IOException {
        // Hyperliquid does not support snapshots
        return null;
    }

    @Override
    protected void handleJSONMessage(final JSONDecoder.JSONNode node) {
        try (final var obj = node.asObject()) {
            Channel channel = null;
            while (obj.hasNextKey()) {
                try (final var key = obj.nextKey()) {
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

    private boolean parseLevel(final JSONDecoder.JSONArray levels, final MBP10Book.PriceLevel existingLevel) {
        long price = MBP10Encoder.askPrice0NullValue();
        long size = MBP10Encoder.askSize0NullValue();
        long orderCount = MBP10Encoder.askCount0NullValue();

        try (final var levelNode = levels.nextItem(); final var level = levelNode.asObject()) {
            while (level.hasNextKey()) {
                try (final var key = level.nextKey()) {
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

    private short parseLevels(final JSONDecoder.JSONArray array) {
        int depth = MBP10Encoder.depthNullValue();
        try (final var bidsNode = array.nextItem(); final var bids = bidsNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (bids.hasNextItem()) {
                    depth = parseLevel(bids, book.bids[i]) ? Math.min(depth, i) : depth;
                } else {
                    book.bids[i].reset();
                }
            }

            while (bids.hasNextItem()) {
                try (final var levelNode = bids.nextItem(); final var level = levelNode.asObject()) {}
            }
        }

        try (final var asksNode = array.nextItem(); final var asks = asksNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (asks.hasNextItem()) {
                    depth = parseLevel(asks, book.asks[i]) ? Math.min(depth, i) : depth;
                } else {
                    book.asks[i].reset();
                }
            }

            while (asks.hasNextItem()) {
                try (final var levelNode = asks.nextItem(); final var level = levelNode.asObject()) {}
            }
        }
        this.book.writeTo(this.schema);
        return (short) depth;
    }

    private void parseL2Book(final JSONDecoder.JSONNode node) {
        prepareEncoder();

        this.schema.encoder.timestampEvent(MBP10Encoder.timestampEventNullValue());
        this.schema.encoder.sequence(MBP10Encoder.sequenceNullValue());
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth(MBP10Encoder.depthNullValue());

        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);

        try (final var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (final var key = object.nextKey()) {
                    if (key.getName().equals("coin")) {
                        // NO-OP: Maybe check if it's correct in the future?
                    } else if (key.getName().equals("time")) {
                        // Hyperliquid is in epoch millis
                        long timestamp = key.asLong() * NANOS_PER_MILLI;
                        this.schema.encoder.timestampEvent(timestamp);
                        this.schema.encoder.sequence(timestamp);
                    } else if (key.getName().equals("levels")) {
                        try (final var array = key.asArray()) {
                            this.schema.encoder.depth(parseLevels(array));
                        }
                    }
                }
            }
        }

        offer();
    }

    private void parseTrade(final JSONDecoder.JSONObject trade) {
        prepareEncoder();

        this.book.writeTo(this.schema);

        Side side = Side.None;
        long price = MBP10Encoder.priceNullValue();
        long size = MBP10Encoder.sizeNullValue();
        long timestampEvent = MBP10Encoder.timestampEventNullValue();

        while (trade.hasNextKey()) {
            try (final var key = trade.nextKey()) {
                if (key.getName().equals("side")) {
                    if (key.asString().equals("A")) {
                        side = Side.Ask;
                    } else {
                        side = Side.Bid;
                    }
                } else if (key.getName().equals("px")) {
                    price = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (key.getName().equals("sz")) {
                    size = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (key.getName().equals("time")) {
                    timestampEvent = key.asLong() * NANOS_PER_MILLI;
                }
            }
        }

        this.schema.encoder.timestampEvent(timestampEvent);
        this.schema.encoder.sequence(timestampEvent);
        this.schema.encoder.price(price);
        this.schema.encoder.size(size);
        this.schema.encoder.action(Action.Trade);
        this.schema.encoder.side(side);
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.schema.encoder.depth(MBP10Encoder.depthNullValue()); // TODO: Do we want to send the correct depth? Is it worth?

        offer();
        this.lastTradeSize = size;
        this.lastTradePrice = price;
    }

    private void parseTrades(final JSONDecoder.JSONNode node) {
        try (final var trades = node.asArray()) {
            while (trades.hasNextItem()) {
                try (final var tradeNode = trades.nextItem(); final var trade = tradeNode.asObject()) {
                    parseTrade(trade);
                }
            }
        }
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(listing.exchangeId());
        this.schema.encoder.securityId(listing.securityId());
        this.schema.encoder.timestampSent(MBP10Encoder.timestampSentNullValue()); // Hyperliquid only has event timestamps
        this.schema.encoder.timestampRecv(recvTimestamp);
    }

    private void writeSubscription(final String channel) {
        // { "method": "subscribe", "subscription": { "type": "<channel>", "coin": "<coin_symbol>" } }
        final JSONWebSocketWriter jsonWebSocketWriter = (JSONWebSocketWriter) this.socketWriter;
        final JSONEncoder jsonEncoder = jsonWebSocketWriter.getJSONEncoder();

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

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJSONBodyBuffer(), false);
    }

    @Override
    protected void subscribe() throws IOException {
        this.writeSubscription("l2Book");
        this.writeSubscription("trades");
    }

}
