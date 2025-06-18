package group.gnometrading.gateways.exchanges.hyperliquid;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.gateways.JSONWebSocketMarketInboundGateway;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.*;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;

public class HyperliquidInboundGateway extends JSONWebSocketMarketInboundGateway {

    private static final int MAX_LEVEL_DEPTH = 10;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private enum Channel {
        L2BOOK,
        TRADES,
        ADMIN
    }

    private final Listing listing;
    private final MBP10Encoder encoder;
    private long lastTradePrice, lastTradeSize;
    @VisibleForTesting protected final PriceLevel[] asks, bids;

    public HyperliquidInboundGateway(
            Publication publication,
            EpochNanoClock clock,
            SchemaType outputSchemaType,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder,
            JSONEncoder jsonEncoder,
            int writeBufferSize,
            Listing listing
    ) {
        super(publication, clock, new MBP10Schema(), outputSchemaType, socketClient, jsonDecoder, jsonEncoder, writeBufferSize);
        this.listing = listing;
        this.encoder = (MBP10Encoder) this.inputSchema.encoder;

        this.encoder.exchangeId(listing.exchangeId());
        this.encoder.securityId(listing.securityId());
        this.encoder.sequence(MBP10Encoder.sequenceNullValue()); // Hyperliquid does not have sequence nums
        this.encoder.timestampSent(MBP10Encoder.timestampSentNullValue()); // Hyperliquid only has event timestamps
        this.lastTradePrice = MBP10Encoder.priceNullValue();
        this.lastTradeSize = MBP10Encoder.sizeNullValue();

        this.asks = new PriceLevel[MAX_LEVEL_DEPTH];
        this.bids = new PriceLevel[MAX_LEVEL_DEPTH];
        for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
            this.asks[i] = new PriceLevel();
            this.bids[i] = new PriceLevel();
        }
    }

    @Override
    protected void handleJSONMessage(final JSONDecoder.JSONNode node) throws IOException {
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

    private boolean parseLevel(final JSONDecoder.JSONArray levels, final PriceLevel existingLevel) {
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

    private void writeBookLevels() {
        this.encoder.bidPrice0(this.bids[0].price);
        this.encoder.bidSize0(this.bids[0].size);
        this.encoder.bidCount0(this.bids[0].count);

        this.encoder.bidPrice1(this.bids[1].price);
        this.encoder.bidSize1(this.bids[1].size);
        this.encoder.bidCount1(this.bids[1].count);

        this.encoder.bidPrice2(this.bids[2].price);
        this.encoder.bidSize2(this.bids[2].size);
        this.encoder.bidCount2(this.bids[2].count);

        this.encoder.bidPrice3(this.bids[3].price);
        this.encoder.bidSize3(this.bids[3].size);
        this.encoder.bidCount3(this.bids[3].count);

        this.encoder.bidPrice4(this.bids[4].price);
        this.encoder.bidSize4(this.bids[4].size);
        this.encoder.bidCount4(this.bids[4].count);

        this.encoder.bidPrice5(this.bids[5].price);
        this.encoder.bidSize5(this.bids[5].size);
        this.encoder.bidCount5(this.bids[5].count);

        this.encoder.bidPrice6(this.bids[6].price);
        this.encoder.bidSize6(this.bids[6].size);
        this.encoder.bidCount6(this.bids[6].count);

        this.encoder.bidPrice7(this.bids[7].price);
        this.encoder.bidSize7(this.bids[7].size);
        this.encoder.bidCount7(this.bids[7].count);

        this.encoder.bidPrice8(this.bids[8].price);
        this.encoder.bidSize8(this.bids[8].size);
        this.encoder.bidCount8(this.bids[8].count);

        this.encoder.bidPrice9(this.bids[9].price);
        this.encoder.bidSize9(this.bids[9].size);
        this.encoder.bidCount9(this.bids[9].count);


        this.encoder.askPrice0(this.asks[0].price);
        this.encoder.askSize0(this.asks[0].size);
        this.encoder.askCount0(this.asks[0].count);


        this.encoder.askPrice1(this.asks[1].price);
        this.encoder.askSize1(this.asks[1].size);
        this.encoder.askCount1(this.asks[1].count);

        this.encoder.askPrice2(this.asks[2].price);
        this.encoder.askSize2(this.asks[2].size);
        this.encoder.askCount2(this.asks[2].count);

        this.encoder.askPrice3(this.asks[3].price);
        this.encoder.askSize3(this.asks[3].size);
        this.encoder.askCount3(this.asks[3].count);

        this.encoder.askPrice4(this.asks[4].price);
        this.encoder.askSize4(this.asks[4].size);
        this.encoder.askCount4(this.asks[4].count);

        this.encoder.askPrice5(this.asks[5].price);
        this.encoder.askSize5(this.asks[5].size);
        this.encoder.askCount5(this.asks[5].count);

        this.encoder.askPrice6(this.asks[6].price);
        this.encoder.askSize6(this.asks[6].size);
        this.encoder.askCount6(this.asks[6].count);

        this.encoder.askPrice7(this.asks[7].price);
        this.encoder.askSize7(this.asks[7].size);
        this.encoder.askCount7(this.asks[7].count);

        this.encoder.askPrice8(this.asks[8].price);
        this.encoder.askSize8(this.asks[8].size);
        this.encoder.askCount8(this.asks[8].count);

        this.encoder.askPrice9(this.asks[9].price);
        this.encoder.askSize9(this.asks[9].size);
        this.encoder.askCount9(this.asks[9].count);
    }

    private short parseLevels(final JSONDecoder.JSONArray array) {
        int depth = MBP10Encoder.depthNullValue();
        try (final var bidsNode = array.nextItem(); final var bids = bidsNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (bids.hasNextItem()) {
                    depth = parseLevel(bids, this.bids[i]) ? Math.min(depth, i) : depth;
                } else {
                    this.bids[i].reset();
                }
            }

            while (bids.hasNextItem()) {
                try (final var levelNode = bids.nextItem(); final var level = levelNode.asObject()) {}
            }
        }

        try (final var asksNode = array.nextItem(); final var asks = asksNode.asArray()) {
            for (int i = 0; i < MAX_LEVEL_DEPTH; i++) {
                if (asks.hasNextItem()) {
                    depth = parseLevel(asks, this.asks[i]) ? Math.min(depth, i) : depth;
                } else {
                    this.asks[i].reset();
                }
            }

            while (asks.hasNextItem()) {
                try (final var levelNode = asks.nextItem(); final var level = levelNode.asObject()) {}
            }
        }
        writeBookLevels();
        return (short) depth;
    }

    private void parseL2Book(final JSONDecoder.JSONNode node) {
        this.encoder.timestampRecv(recvTimestamp);
        this.encoder.timestampEvent(MBP10Encoder.timestampEventNullValue());
        this.encoder.price(this.lastTradePrice);
        this.encoder.size(lastTradeSize);
        this.encoder.action(Action.Modify);
        this.encoder.side(Side.None);
        this.encoder.depth(MBP10Encoder.depthNullValue());

        this.encoder.flags().clear();
        this.encoder.flags().marketByPrice(true);

        try (final var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (final var key = object.nextKey()) {
                    if (key.getName().equals("coin")) {
                        // NO-OP: Maybe check if it's correct in the future?
                    } else if (key.getName().equals("time")) {
                        // Hyperliquid is in epoch millis
                        this.encoder.timestampEvent(key.asLong() * NANOS_PER_MILLI);
                    } else if (key.getName().equals("levels")) {
                        try (final var array = key.asArray()) {
                            this.encoder.depth(parseLevels(array));
                        }
                    }
                }
            }
        }

        offer();
    }

    private void parseTrade(final JSONDecoder.JSONObject trade) {
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
                    timestampEvent = key.asLong() * 1_000_000;
                }
            }
        }

        this.encoder.timestampEvent(timestampEvent);
        this.encoder.price(price);
        this.encoder.size(size);
        this.encoder.action(Action.Trade);
        this.encoder.side(side);
        this.encoder.flags().clear();
        this.encoder.flags().marketByPrice(true);
        this.encoder.depth(MBP10Encoder.depthNullValue()); // TODO: Do we want to send the correct depth? Is it worth?

        offer();
        this.lastTradeSize = size;
        this.lastTradePrice = price;
    }

    private void parseTrades(final JSONDecoder.JSONNode node) {
        this.encoder.timestampRecv(recvTimestamp);
        writeBookLevels();

        try (final var trades = node.asArray()) {
            while (trades.hasNextItem()) {
                try (final var tradeNode = trades.nextItem(); final var trade = tradeNode.asObject()) {
                    parseTrade(trade);
                }
            }
        }
    }

    private void writeSubscription(final String channel) {
        this.writeBuffer.clear();

        // { "method": "subscribe", "subscription": { "type": "<channel>", "coin": "<coin_symbol>" } }
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("method", "subscribe");

        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeString("subscription");
        this.jsonEncoder.writeColon();

        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("type", channel);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("coin", this.listing.exchangeSecuritySymbol());
        this.jsonEncoder.writeObjectEnd();

        this.jsonEncoder.writeObjectEnd();

        this.writeBuffer.flip();
    }

    private void subscribe() throws IOException {
        this.writeSubscription("l2Book");
        if (!this.socketClient.send(Opcode.TEXT, this.writeBuffer)) {
            throw new RuntimeException("Unable to subscribe");
        }

         this.writeSubscription("trades");
        if (!this.socketClient.send(Opcode.TEXT, this.writeBuffer)) {
            throw new RuntimeException("Unable to subscribe");
        }
    }

    private void connect() {
        try {
            this.socketClient.connect();
            this.socketClient.configureBlocking(false);
            this.socketClient.setTcpNoDelay(true);
            this.socketClient.setKeepAlive(true);
            this.subscribe();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void reconnect() {
        try {
            this.socketClient.close();
            this.connect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onStart() {
        this.connect();
    }

    @Override
    public void onSocketClose() {
        throw new RuntimeException("Socket closed");
    }

    protected static class PriceLevel {
        long price = MBP10Encoder.askPrice0NullValue();
        long size = MBP10Encoder.askSize0NullValue();
        long count = MBP10Encoder.askCount0NullValue();

        public void reset() {
            price = MBP10Encoder.askPrice0NullValue();
            size = MBP10Encoder.askSize0NullValue();
            count = MBP10Encoder.askCount0NullValue();
        }

        public boolean update(final long price, final long size, final long count) {
            boolean updated = false;
            if (this.price != price) {
                this.price = price;
                updated = true;
            }
            if (this.size != size) {
                this.size = size;
                updated = true;
            }
            if (this.count != count) {
                this.count = count;
                updated = true;
            }
            return updated;
        }
    }
}
