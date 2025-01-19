package group.gnometrading.gateways.exchanges.hyperliquid;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.gateways.JSONWebSocketMarketInboundGateway;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.objects.Action;
import group.gnometrading.objects.MarketUpdateEncoder;
import group.gnometrading.objects.Side;
import group.gnometrading.objects.Statics;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;

public class HyperliquidInboundGateway extends JSONWebSocketMarketInboundGateway {

    private enum Channel {
        L2BOOK,
        TRADES,
        ADMIN
    }

    private final JSONEncoder jsonEncoder;
    private final Listing listing;

    public HyperliquidInboundGateway(
            WebSocketClient socketClient,
            Publication publication,
            EpochNanoClock clock,
            JSONDecoder jsonDecoder,
            int writeBufferSize,
            Listing listing,
            JSONEncoder jsonEncoder
    ) {
        super(socketClient, publication, clock, jsonDecoder, writeBufferSize);
        this.jsonEncoder = jsonEncoder;
        this.listing = listing;
        this.jsonEncoder.wrap(this.writeBuffer);

        this.marketUpdateEncoder.exchangeId(listing.exchangeId());
        this.marketUpdateEncoder.securityId(listing.securityId());
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

    private void parseLevel(final JSONDecoder.JSONObject level) {
        long price = MarketUpdateEncoder.priceNullValue();
        long size = MarketUpdateEncoder.sizeNullValue();
        long orderCount = MarketUpdateEncoder.orderIdNullValue();

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

        marketUpdateEncoder.orderId(orderCount);
        marketUpdateEncoder.clientOid(MarketUpdateEncoder.clientOidNullValue());
        marketUpdateEncoder.price(price);
        marketUpdateEncoder.size(size);
        marketUpdateEncoder.action(Action.Modify);

    }

    private void parseLevels(final JSONDecoder.JSONArray array) {
        try (final var bidsNode = array.nextItem(); final var bids = bidsNode.asArray()) {
             while (bids.hasNextItem()) {
                 try (final var bidNode = bids.nextItem(); final var bid = bidNode.asObject()) {
                     parseLevel(bid);
                     marketUpdateEncoder.side(Side.Buy);
                     this.marketUpdateFlagsEncoder.clear();
                     this.marketUpdateFlagsEncoder.marketByPrice(true);

                     this.offer();
                 }
             }
        }

        try (final var asksNode = array.nextItem(); final var asks = asksNode.asArray()) {
            while (asks.hasNextItem()) {
                try (final var askNode = asks.nextItem(); final var ask = askNode.asObject()) {
                    parseLevel(ask);
                    marketUpdateEncoder.side(Side.Sell);
                    this.marketUpdateFlagsEncoder.clear();
                    this.marketUpdateFlagsEncoder.marketByPrice(true);

                    this.offer();
                }
            }
        }
    }

    private void parseL2Book(final JSONDecoder.JSONNode node) {
        this.marketUpdateEncoder.timestampRecv(recvTimestamp);
        this.marketUpdateEncoder.timestampEvent(MarketUpdateEncoder.timestampEventNullValue());

        try (final var object = node.asObject()) {
            while (object.hasNextKey()) {
                try (final var key = object.nextKey()) {
                    if (key.getName().equals("coin")) {
                        // NO-OP: Maybe check if it's correct in the future?
                    } else if (key.getName().equals("time")) {
                        // Hyperliquid is in epoch millis
                        this.marketUpdateEncoder.timestampEvent(key.asLong() * 1_000_000);
                    } else if (key.getName().equals("levels")) {
                        try (final var array = key.asArray()) {
                            parseLevels(array);
                        }
                    }
                }
            }
        }
    }

    private void parseTrade(final JSONDecoder.JSONObject trade) {
        Side side = Side.None;
        long price = MarketUpdateEncoder.priceNullValue();
        long size = MarketUpdateEncoder.sizeNullValue();
        long timestampEvent = MarketUpdateEncoder.timestampEventNullValue();

        while (trade.hasNextKey()) {
            try (final var key = trade.nextKey()) {
                if (key.getName().equals("side")) {
                    if (key.asString().equals("A")) {
                        side = Side.Sell;
                    } else {
                        side = Side.Buy;
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

        this.marketUpdateEncoder.timestampEvent(timestampEvent);
        this.marketUpdateEncoder.orderId(MarketUpdateEncoder.orderIdNullValue());
        this.marketUpdateEncoder.clientOid(MarketUpdateEncoder.clientOidNullValue());
        this.marketUpdateEncoder.price(price);
        this.marketUpdateEncoder.size(size);
        this.marketUpdateEncoder.action(Action.Trade);
        this.marketUpdateEncoder.side(side);
        this.marketUpdateFlagsEncoder.clear();

        offer();
    }

    private void parseTrades(final JSONDecoder.JSONNode node) {
        this.marketUpdateEncoder.timestampRecv(recvTimestamp);

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

    @Override
    public void onStart() {
        try {
            this.socketClient.connect();
            this.subscribe();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onSocketClose() {

    }
}
