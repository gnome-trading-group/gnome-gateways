package group.gnometrading.gateways.inbound.exchanges.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketReader;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.InboundSocketWriter;
import group.gnometrading.gateways.inbound.InboundWebSocketWriter;
import group.gnometrading.gateways.inbound.mbp.Mbp10Book;
import group.gnometrading.gateways.inbound.mbp.Mbp10SchemaFactory;
import group.gnometrading.gateways.inbound.mbp.MbpBook;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
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

/**
 * Inbound gateway for Polymarket US market data.
 *
 * <p>Subscribes to {@code SUBSCRIPTION_TYPE_MARKET_DATA} and {@code SUBSCRIPTION_TYPE_TRADE} for
 * one market slug on {@code /v1/ws/markets}. Each market is a single book priced in long (YES)
 * terms, so both the {@code :long} and {@code :short} listings of a market consume the same
 * stream; the suffix is stripped before subscribing.
 *
 * <p>Every {@code marketData} message carries the top of the book sorted best-to-worst, so the
 * book is rebuilt from each message rather than patched. Prices and quantities are decimal
 * strings; timestamps are RFC 3339 strings.
 *
 * <p>The handshake is authenticated with Ed25519 headers, re-signed on every connect so the
 * timestamp is never stale after a reconnect. The docs promise {@code {"heartbeat":{}}} messages,
 * but none arrived in a five-minute live capture, so the reader pings to keep the link alive.
 */
public final class PolymarketUsInboundReader extends InboundJsonWebSocketReader<Mbp10Schema>
        implements Mbp10SchemaFactory {

    public static final String WEBSOCKET_PATH = "/v1/ws/markets";

    private static final int MAX_LEVEL_DEPTH = 10;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final String MARKET_DATA_REQUEST_ID = "md";
    private static final String TRADE_REQUEST_ID = "tr";

    private final Mbp10Book book;
    private final PolymarketUsAuthSigner signer;
    private final String marketSlug;

    private long tradePrice;
    private long tradeSize;
    private long tradeTimestamp;
    private Side tradeSide;
    private long levelPrice;
    private long levelSize;

    public PolymarketUsInboundReader(
            final Logger logger,
            final SequencedRingBuffer<Mbp10Schema> outputBuffer,
            final EpochNanoClock clock,
            final InboundSocketWriter socketWriter,
            final Listing listing,
            final WebSocketClient socketClient,
            final JsonDecoder jsonDecoder,
            final PolymarketUsAuthSigner signer) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (Mbp10Book) this.internalBook;
        this.signer = signer;
        this.marketSlug = marketSlug(listing.exchangeSecurityId());
    }

    public static String marketSlug(final String exchangeSecurityId) {
        final int colonIdx = exchangeSecurityId.lastIndexOf(':');
        return colonIdx > 0 ? exchangeSecurityId.substring(0, colonIdx) : exchangeSecurityId;
    }

    @Override
    protected void beforeConnect() throws IOException {
        this.signer.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WEBSOCKET_PATH);
        this.socketClient.setHeader(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, this.signer.apiKey());
        this.socketClient.setHeader(PolymarketUsAuthSigner.TIMESTAMP_HEADER, this.signer.timestamp());
        this.socketClient.setHeader(PolymarketUsAuthSigner.SIGNATURE_HEADER, this.signer.signature());
    }

    @Override
    protected void subscribe() throws IOException {
        writeSubscription(MARKET_DATA_REQUEST_ID, "SUBSCRIPTION_TYPE_MARKET_DATA");
        writeSubscription(TRADE_REQUEST_ID, "SUBSCRIPTION_TYPE_TRADE");
    }

    private void writeSubscription(final String requestId, final String subscriptionType) throws IOException {
        // {"subscribe":{"requestId":"md","subscriptionType":"...","marketSlugs":["<slug>"]}}
        final InboundJsonWebSocketWriter jsonWriter = (InboundJsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeString("subscribe");
        jsonEncoder.writeColon();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("requestId", requestId);
        jsonEncoder.writeComma();
        jsonEncoder.writeObjectEntry("subscriptionType", subscriptionType);
        jsonEncoder.writeComma();
        jsonEncoder.writeString("marketSlugs");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString(this.marketSlug);
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeObjectEnd();
        jsonEncoder.writeObjectEnd();

        ((InboundWebSocketWriter) this.socketWriter).writeText(jsonWriter.getAndFlipJsonBodyBuffer(), false);
    }

    @Override
    protected void keepAlive() throws IOException {
        // Quiet markets go minutes without a book update or heartbeat, so ping to keep the
        // silence check fed by the pong.
        ((InboundWebSocketWriter) this.socketWriter).writePing();
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        // Every marketData message is a full top-of-book snapshot (verified live; captured in the replay buffer).
        return null;
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonNode node) {
        try (var obj = node.asObject()) {
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("marketData")) {
                        parseMarketData(key);
                    } else if (key.getName().equals("trade")) {
                        parseTrade(key);
                    }
                    // requestId, subscriptionType, heartbeat, error: auto-consumed on close
                }
            }
        }
    }

    private void parseMarketData(final JsonDecoder.JsonNode marketDataNode) {
        long timestamp = Mbp10Encoder.timestampEventNullValue();
        boolean sawBids = false;
        boolean sawOffers = false;
        try (var marketData = marketDataNode.asObject()) {
            while (marketData.hasNextKey()) {
                try (var key = marketData.nextKey()) {
                    if (key.getName().equals("bids")) {
                        parseLevels(key, this.book.bids);
                        sawBids = true;
                    } else if (key.getName().equals("offers")) {
                        parseLevels(key, this.book.asks);
                        sawOffers = true;
                    } else if (key.getName().equals("transactTime")) {
                        timestamp = parseTimestamp(key);
                    }
                    // marketSlug, state, stats: auto-consumed on close
                }
            }
        }
        // Empty sides may be omitted from the message entirely.
        if (!sawBids) {
            resetLevels(this.book.bids, 0);
        }
        if (!sawOffers) {
            resetLevels(this.book.asks, 0);
        }

        prepareEncoder();
        this.schema.encoder.timestampEvent(timestamp);
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(Mbp10Encoder.priceNullValue());
        this.schema.encoder.size(Mbp10Encoder.sizeNullValue());
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);
        offer();
    }

    private void parseLevels(final JsonDecoder.JsonNode levelsNode, final MbpBook.PriceLevel[] levels) {
        int idx = 0;
        try (var array = levelsNode.asArray()) {
            while (array.hasNextItem()) {
                try (var levelNode = array.nextItem()) {
                    parseLevel(levelNode);
                }
                if (idx < MAX_LEVEL_DEPTH && this.levelSize > 0) {
                    levels[idx++].update(this.levelPrice, this.levelSize, 1L);
                }
            }
        }
        resetLevels(levels, idx);
    }

    private void parseLevel(final JsonDecoder.JsonNode levelNode) {
        this.levelPrice = 0;
        this.levelSize = 0;
        try (var level = levelNode.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals("px")) {
                        this.levelPrice = parseAmount(key, Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("qty")) {
                        this.levelSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    }
                }
            }
        }
    }

    private static void resetLevels(final MbpBook.PriceLevel[] levels, final int from) {
        for (int i = from; i < MAX_LEVEL_DEPTH; i++) {
            levels[i].reset();
        }
    }

    private void parseTrade(final JsonDecoder.JsonNode tradeNode) {
        this.tradePrice = Mbp10Encoder.priceNullValue();
        this.tradeSize = Mbp10Encoder.sizeNullValue();
        this.tradeTimestamp = Mbp10Encoder.timestampEventNullValue();
        this.tradeSide = Side.None;

        try (var trade = tradeNode.asObject()) {
            while (trade.hasNextKey()) {
                try (var key = trade.nextKey()) {
                    if (key.getName().equals("price")) {
                        this.tradePrice = parseAmount(key, Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("quantity")) {
                        this.tradeSize = parseAmount(key, Statics.SIZE_SCALING_FACTOR);
                    } else if (key.getName().equals("tradeTime")) {
                        this.tradeTimestamp = parseTimestamp(key);
                    } else if (key.getName().equals("taker")) {
                        this.tradeSide = parseTakerSide(key);
                    }
                    // marketSlug, maker: auto-consumed on close
                }
            }
        }

        prepareEncoder();
        this.schema.encoder.timestampEvent(this.tradeTimestamp);
        this.schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        this.schema.encoder.price(this.tradePrice);
        this.schema.encoder.size(this.tradeSize);
        this.schema.encoder.action(Action.Trade);
        this.schema.encoder.side(this.tradeSide);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);
        offer();
    }

    /**
     * The book is priced in long terms, so the aggressor side follows the taker's intent: buying
     * short (NO) sells long and hits the bids even though its order side is BUY. Falls back to the
     * order side when the intent is undefined.
     */
    private static Side parseTakerSide(final JsonDecoder.JsonNode takerNode) {
        Side fromSide = Side.None;
        Side fromIntent = Side.None;
        try (var taker = takerNode.asObject()) {
            while (taker.hasNextKey()) {
                try (var key = taker.nextKey()) {
                    if (key.getName().equals("side")) {
                        fromSide = sideFromOrderSide(key.asString());
                    } else if (key.getName().equals("intent")) {
                        fromIntent = sideFromIntent(key.asString());
                    }
                    // outcomeSide, action: auto-consumed on close
                }
            }
        }
        return fromIntent != Side.None ? fromIntent : fromSide;
    }

    private static Side sideFromOrderSide(final GnomeString value) {
        if (value.equals("ORDER_SIDE_BUY")) {
            return Side.Bid;
        } else if (value.equals("ORDER_SIDE_SELL")) {
            return Side.Ask;
        }
        return Side.None;
    }

    private static Side sideFromIntent(final GnomeString value) {
        if (value.equals("ORDER_INTENT_BUY_LONG") || value.equals("ORDER_INTENT_SELL_SHORT")) {
            return Side.Bid;
        } else if (value.equals("ORDER_INTENT_SELL_LONG") || value.equals("ORDER_INTENT_BUY_SHORT")) {
            return Side.Ask;
        }
        return Side.None;
    }

    /** Reads an {@code Amount} object: {@code {"value":"0.555","currency":"USD"}}. */
    private static long parseAmount(final JsonDecoder.JsonNode amountNode, final long scalingFactor) {
        long value = 0;
        try (var amount = amountNode.asObject()) {
            while (amount.hasNextKey()) {
                try (var key = amount.nextKey()) {
                    if (key.getName().equals("value")) {
                        value = key.asString().toFixedPointLong(scalingFactor);
                    }
                }
            }
        }
        return value;
    }

    private static long parseTimestamp(final JsonDecoder.JsonNode node) {
        final long nanos = Rfc3339.toEpochNanos(node.asString());
        return nanos == Rfc3339.INVALID ? Mbp10Encoder.timestampEventNullValue() : nanos;
    }

    private void prepareEncoder() {
        this.schema.encoder.exchangeId(this.listing.exchange().exchangeId());
        this.schema.encoder.securityId(this.listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(this.recvTimestamp);
    }
}
