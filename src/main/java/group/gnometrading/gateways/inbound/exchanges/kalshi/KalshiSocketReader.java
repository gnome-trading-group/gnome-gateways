package group.gnometrading.gateways.inbound.exchanges.kalshi;

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
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Inbound gateway for Kalshi prediction market data.
 *
 * <p>Connects to the Kalshi WebSocket API and subscribes to the {@code orderbook_delta} and
 * {@code trade} channels for a single market ticker. Maintains a full-depth YES and NO orderbook
 * internally (indexed by integer cent price 1–99) and extracts top-10 levels into Mbp10Schema on
 * each book update.
 *
 * <p>YES levels map to bids. NO levels map to asks: a NO bid at price P implies a YES ask at price
 * (100 - P) cents.
 *
 * <p>Prices arrive as dollar strings (e.g., {@code "0.0800"} for 8 cents) and quantities as
 * fixed-point strings (e.g., {@code "300.00"} for 300 contracts). Deltas may be negative.
 * Both are parsed using {@code GnomeString.toFixedPointLong}.
 *
 * <p>Authentication uses RSA-PSS (SHA-256, MGF1-SHA-256, salt=32). The timestamp header is epoch
 * milliseconds; signatures are computed fresh on every connect so the timestamp is never stale
 * after a reconnect.
 *
 * <p>For binary markets, the listing's {@code exchangeSecurityId} may carry a {@code :yes} or
 * {@code :no} suffix (e.g., {@code "KXELONMARS-99:yes"}). This suffix is stripped before
 * subscribing because a single WebSocket subscription covers both YES and NO order sides.
 *
 * <p>Assumes Kalshi sends {@code "type"} before {@code "msg"} within each WebSocket message,
 * consistent with observed API behavior.
 */
public final class KalshiSocketReader extends JsonWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

    private static final int MAX_LEVEL_DEPTH = 10;
    // Kalshi prices: integer cents 1–99. Index 0 and 100 unused.
    private static final int PRICE_ARRAY_SIZE = 100;
    // Converts integer cents to internal fixed-point price: cents * (PRICE_SCALING_FACTOR / 100)
    private static final long CENTS_TO_PRICE_SCALE = Statics.PRICE_SCALING_FACTOR / 100L;
    // Kalshi qty strings have 2 decimal places (cent-dollar precision). We store qty arrays
    // in cent-dollars (multiply by 100 on parse) so $0.01 orders are preserved. Divide by 100
    // when writing to the size field to keep the same schema scale as other exchanges.
    private static final long CENT_DOLLAR_TO_SIZE = Statics.SIZE_SCALING_FACTOR / 100L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final String WEBSOCKET_PATH = "/trade-api/ws/v2";

    private enum MsgType {
        UNKNOWN,
        SNAPSHOT,
        DELTA,
        TRADE
    }

    private final Mbp10Book book;
    private final String apiKey;
    private final PrivateKey privateKey;
    private final String marketTicker;

    // Full-depth internal book indexed by price in cents (1–99). Zero means no orders at that level.
    private final long[] yesQty = new long[PRICE_ARRAY_SIZE];
    private final long[] noQty = new long[PRICE_ARRAY_SIZE];

    private long lastTimestampNanos;

    public KalshiSocketReader(
            Logger logger,
            SequencedRingBuffer<Mbp10Schema> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder,
            String apiKey,
            PrivateKey privateKey) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient, jsonDecoder);
        this.book = (Mbp10Book) this.internalBook;
        this.apiKey = apiKey;
        this.privateKey = privateKey;
        final String rawId = listing.exchangeSecurityId();
        final int colonIdx = rawId.indexOf(':');
        this.marketTicker = (colonIdx > 0) ? rawId.substring(0, colonIdx) : rawId;
        this.lastTimestampNanos = Mbp10Encoder.timestampEventNullValue();
    }

    @Override
    protected void beforeConnect() throws IOException {
        long timestampMillis = clock.nanoTime() / NANOS_PER_MILLI;
        String timestamp = Long.toString(timestampMillis);
        String payload = timestamp + "GET" + WEBSOCKET_PATH;

        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            sig.initSign(privateKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(sig.sign());

            socketClient.setHeader("KALSHI-ACCESS-KEY", apiKey);
            socketClient.setHeader("KALSHI-ACCESS-TIMESTAMP", timestamp);
            socketClient.setHeader("KALSHI-ACCESS-SIGNATURE", signature);
        } catch (Exception e) {
            throw new IOException("Failed to compute Kalshi auth signature", e);
        }
    }

    @Override
    protected void subscribe() throws IOException {
        // {"id": 1, "cmd": "subscribe", "params": {"channels": ["orderbook_delta", "trade"], "market_tickers":
        // ["<ticker>"]}}
        final JsonWebSocketWriter jsonWebSocketWriter = (JsonWebSocketWriter) this.socketWriter;
        final JsonEncoder jsonEncoder = jsonWebSocketWriter.getJsonEncoder();

        jsonEncoder.writeObjectStart();
        jsonEncoder.writeObjectEntry("id", 1);
        jsonEncoder.writeComma();
        jsonEncoder.writeObjectEntry("cmd", "subscribe");
        jsonEncoder.writeComma();
        jsonEncoder.writeString("params");
        jsonEncoder.writeColon();
        jsonEncoder.writeObjectStart();
        jsonEncoder.writeString("channels");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString("orderbook_delta");
        jsonEncoder.writeComma();
        jsonEncoder.writeString("trade");
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeComma();
        jsonEncoder.writeString("market_tickers");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString(marketTicker);
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeObjectEnd();
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }

    @Override
    protected void keepAlive() throws IOException {
        // Kalshi server sends WebSocket ping frames every 10 seconds; no application-level keepalive required
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() throws IOException {
        // Kalshi sends orderbook_snapshot over WebSocket after subscription (captured in replay buffer)
        return null;
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonNode node) {
        try (var obj = node.asObject()) {
            MsgType type = MsgType.UNKNOWN;
            while (obj.hasNextKey()) {
                try (var key = obj.nextKey()) {
                    if (key.getName().equals("type")) {
                        type = parseMsgType(key.asString());
                    } else if (key.getName().equals("msg")) {
                        if (type == MsgType.SNAPSHOT) {
                            parseSnapshot(key);
                        } else if (type == MsgType.DELTA) {
                            parseDelta(key);
                        } else if (type == MsgType.TRADE) {
                            parseTrade(key);
                        }
                        // else: auto-consumed on close
                    }
                    // id, sid, seq, and other fields: auto-consumed on close
                }
            }
        }
    }

    private MsgType parseMsgType(final GnomeString typeStr) {
        if (typeStr.equals("orderbook_snapshot")) {
            return MsgType.SNAPSHOT;
        } else if (typeStr.equals("orderbook_delta")) {
            return MsgType.DELTA;
        } else if (typeStr.equals("trade")) {
            return MsgType.TRADE;
        }
        return MsgType.UNKNOWN;
    }

    private void parseSnapshot(final JsonDecoder.JsonNode msgNode) {
        Arrays.fill(yesQty, 0L);
        Arrays.fill(noQty, 0L);
        lastTimestampNanos = Mbp10Encoder.timestampEventNullValue();

        try (var msg = msgNode.asObject()) {
            while (msg.hasNextKey()) {
                try (var key = msg.nextKey()) {
                    if (key.getName().equals("yes_dollars_fp")) {
                        parseLevelPairs(key, yesQty);
                    } else if (key.getName().equals("no_dollars_fp")) {
                        parseLevelPairs(key, noQty);
                    }
                    // market_ticker, market_id: auto-consumed on close
                }
            }
        }

        refreshMbp10Book();
    }

    private void parseLevelPairs(final JsonDecoder.JsonNode node, final long[] qtyArray) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var pairNode = array.nextItem();
                        var pair = pairNode.asArray()) {
                    parsePair(pair, qtyArray);
                }
            }
        }
    }

    private void parsePair(final JsonDecoder.JsonArray pair, final long[] qtyArray) {
        int priceCents = 0;
        long qty = 0;
        if (pair.hasNextItem()) {
            try (var priceNode = pair.nextItem()) {
                priceCents = (int) priceNode.asString().toFixedPointLong(100);
            }
        }
        if (pair.hasNextItem()) {
            try (var qtyNode = pair.nextItem()) {
                qty = qtyNode.asString().toFixedPointLong(100);
            }
        }
        if (priceCents > 0 && priceCents < PRICE_ARRAY_SIZE) {
            qtyArray[priceCents] = qty;
        }
    }

    private void parseDelta(final JsonDecoder.JsonNode msgNode) {
        int priceCents = 0;
        long delta = 0;
        boolean isYes = false;
        boolean sideParsed = false;

        try (var msg = msgNode.asObject()) {
            while (msg.hasNextKey()) {
                try (var key = msg.nextKey()) {
                    if (key.getName().equals("price_dollars")) {
                        priceCents = (int) key.asString().toFixedPointLong(100);
                    } else if (key.getName().equals("delta_fp")) {
                        delta = key.asString().toFixedPointLong(100);
                    } else if (key.getName().equals("side")) {
                        isYes = key.asString().equals("yes");
                        sideParsed = true;
                    } else if (key.getName().equals("ts_ms")) {
                        lastTimestampNanos = key.asLong() * NANOS_PER_MILLI;
                    }
                    // market_ticker, market_id, client_order_id, subaccount: auto-consumed
                }
            }
        }

        if (!sideParsed || priceCents <= 0 || priceCents >= PRICE_ARRAY_SIZE) {
            return;
        }

        long[] qtyArray = isYes ? yesQty : noQty;
        qtyArray[priceCents] = Math.max(0L, qtyArray[priceCents] + delta);

        refreshMbp10Book();
        emitBookUpdate();
    }

    private void parseTrade(final JsonDecoder.JsonNode msgNode) {
        long tradePrice = 0;
        long tradeSize = 0;
        boolean takerIsBid = false;
        long tsMs = 0;

        try (var msg = msgNode.asObject()) {
            while (msg.hasNextKey()) {
                try (var key = msg.nextKey()) {
                    if (key.getName().equals("yes_price_dollars")) {
                        tradePrice = key.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals("count_fp")) {
                        tradeSize = key.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    } else if (key.getName().equals("taker_book_side")) {
                        takerIsBid = key.asString().equals("bid");
                    } else if (key.getName().equals("ts_ms")) {
                        tsMs = key.asLong();
                    }
                    // trade_id, market_ticker, no_price_dollars, taker_side, is_block_trade: auto-consumed
                }
            }
        }

        prepareEncoder();
        schema.encoder.timestampEvent(tsMs * NANOS_PER_MILLI);
        schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        schema.encoder.price(tradePrice);
        schema.encoder.size(tradeSize);
        schema.encoder.action(Action.Trade);
        schema.encoder.side(takerIsBid ? Side.Bid : Side.Ask);
        schema.encoder.depth(Mbp10Encoder.depthNullValue());
        schema.encoder.flags().clear();
        schema.encoder.flags().marketByPrice(true);
        book.writeTo(schema);
        offer();
    }

    private void refreshMbp10Book() {
        // Bids: YES levels, descending by price (highest = best bid first)
        int bidIdx = 0;
        for (int p = PRICE_ARRAY_SIZE - 1; p >= 1 && bidIdx < MAX_LEVEL_DEPTH; p--) {
            if (yesQty[p] > 0) {
                book.bids[bidIdx].update((long) p * CENTS_TO_PRICE_SCALE, yesQty[p] * CENT_DOLLAR_TO_SIZE, 1L);
                bidIdx++;
            }
        }
        for (; bidIdx < MAX_LEVEL_DEPTH; bidIdx++) {
            book.bids[bidIdx].reset();
        }

        // Asks: derived from NO levels. NO bid at P → YES ask at (100 - P).
        // Highest NO price → lowest YES ask, so iterate NO from high to low for ascending asks.
        int askIdx = 0;
        for (int p = PRICE_ARRAY_SIZE - 1; p >= 1 && askIdx < MAX_LEVEL_DEPTH; p--) {
            if (noQty[p] > 0) {
                long askPriceCents = PRICE_ARRAY_SIZE - p;
                book.asks[askIdx].update(askPriceCents * CENTS_TO_PRICE_SCALE, noQty[p] * CENT_DOLLAR_TO_SIZE, 1L);
                askIdx++;
            }
        }
        for (; askIdx < MAX_LEVEL_DEPTH; askIdx++) {
            book.asks[askIdx].reset();
        }
    }

    private void emitBookUpdate() {
        prepareEncoder();
        schema.encoder.timestampEvent(lastTimestampNanos);
        schema.encoder.sequence(Mbp10Encoder.sequenceNullValue());
        schema.encoder.price(Mbp10Encoder.priceNullValue());
        schema.encoder.size(Mbp10Encoder.sizeNullValue());
        schema.encoder.action(Action.Modify);
        schema.encoder.side(Side.None);
        schema.encoder.depth(Mbp10Encoder.depthNullValue());
        schema.encoder.flags().clear();
        schema.encoder.flags().marketByPrice(true);
        book.writeTo(schema);
        offer();
    }

    private void prepareEncoder() {
        schema.encoder.exchangeId(listing.exchange().exchangeId());
        schema.encoder.securityId(listing.security().securityId());
        schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        schema.encoder.timestampRecv(recvTimestamp);
    }
}
