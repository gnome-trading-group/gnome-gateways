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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Inbound gateway for Kalshi prediction market data.
 *
 * <p>Connects to the Kalshi WebSocket API and subscribes to the {@code orderbook_delta} channel
 * for a single market ticker. Maintains a full-depth YES and NO orderbook internally and extracts
 * top-10 levels into Mbp10Schema on each update.
 *
 * <p>YES levels map to bids. NO levels map to asks: a NO bid at price P implies a YES ask at price
 * (100 - P) cents.
 *
 * <p>Authentication uses RSA-PSS (SHA-256, MGF1-SHA-256, salt=32) signatures computed fresh on
 * every connect so that the timestamp header is never stale after a reconnect.
 *
 * <p>Assumes Kalshi sends {@code "type"} before {@code "msg"} within each WebSocket message, which
 * is consistent with observed API behavior. Verify format against current API docs before shipping.
 */
public final class KalshiSocketReader extends JsonWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

    private static final int MAX_LEVEL_DEPTH = 10;
    // Kalshi prices: integer cents 1–99. Index 0 unused, index 100 unused.
    private static final int PRICE_ARRAY_SIZE = 100;
    // Converts integer cents to internal fixed-point price: cents * (PRICE_SCALING_FACTOR / 100)
    private static final long CENTS_TO_PRICE_SCALE = Statics.PRICE_SCALING_FACTOR / 100L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final String WEBSOCKET_PATH = "/trade-api/ws/v2";

    private enum MsgType {
        UNKNOWN,
        SNAPSHOT,
        DELTA
    }

    private final Mbp10Book book;
    private final String apiKey;
    private final PrivateKey privateKey;

    // Full-depth internal book indexed by price in cents (1–99). Zero means no orders at that level.
    private final long[] yesQty = new long[PRICE_ARRAY_SIZE];
    private final long[] noQty = new long[PRICE_ARRAY_SIZE];

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
    }

    @Override
    protected void beforeConnect() throws IOException {
        long timestampSeconds = clock.nanoTime() / NANOS_PER_SECOND;
        String timestamp = Long.toString(timestampSeconds);
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
        // {"id": 1, "cmd": "subscribe", "params": {"channels": ["orderbook_delta"], "market_tickers": ["<ticker>"]}}
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
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeComma();
        jsonEncoder.writeString("market_tickers");
        jsonEncoder.writeColon();
        jsonEncoder.writeArrayStart();
        jsonEncoder.writeString(listing.exchangeSecurityId());
        jsonEncoder.writeArrayEnd();
        jsonEncoder.writeObjectEnd();
        jsonEncoder.writeObjectEnd();

        ((WebSocketWriter) this.socketWriter).writeText(jsonWebSocketWriter.getAndFlipJsonBodyBuffer(), false);
    }

    @Override
    protected void keepAlive() throws IOException {
        // Kalshi server sends heartbeats; no client-side keepalive required
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
                        final var typeStr = key.asString();
                        if (typeStr.equals("orderbook_snapshot")) {
                            type = MsgType.SNAPSHOT;
                        } else if (typeStr.equals("orderbook_delta")) {
                            type = MsgType.DELTA;
                        }
                    } else if (key.getName().equals("msg")) {
                        if (type == MsgType.SNAPSHOT) {
                            parseSnapshot(key);
                        } else if (type == MsgType.DELTA) {
                            parseDelta(key);
                        }
                        // else: auto-consumed on close
                    }
                    // id and other fields: auto-consumed on close
                }
            }
        }
    }

    private void parseSnapshot(final JsonDecoder.JsonNode msgNode) {
        java.util.Arrays.fill(yesQty, 0L);
        java.util.Arrays.fill(noQty, 0L);

        try (var msg = msgNode.asObject()) {
            while (msg.hasNextKey()) {
                try (var key = msg.nextKey()) {
                    if (key.getName().equals("yes")) {
                        parseLevelPairs(key, yesQty);
                    } else if (key.getName().equals("no")) {
                        parseLevelPairs(key, noQty);
                    }
                    // market_ticker: auto-consumed on close
                }
            }
        }

        refreshMbp10Book();
        emitBookUpdate();
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
        long priceCents = 0;
        long qty = 0;
        if (pair.hasNextItem()) {
            try (var priceNode = pair.nextItem()) {
                priceCents = priceNode.asLong();
            }
        }
        if (pair.hasNextItem()) {
            try (var qtyNode = pair.nextItem()) {
                qty = qtyNode.asLong();
            }
        }
        if (priceCents > 0 && priceCents < PRICE_ARRAY_SIZE) {
            qtyArray[(int) priceCents] = qty;
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
                    if (key.getName().equals("price")) {
                        priceCents = (int) key.asLong();
                    } else if (key.getName().equals("delta")) {
                        delta = key.asLong();
                    } else if (key.getName().equals("side")) {
                        isYes = key.asString().equals("yes");
                        sideParsed = true;
                    }
                    // market_ticker: auto-consumed on close
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

    private void refreshMbp10Book() {
        // Bids: YES levels, descending by price (highest = best bid first)
        int bidIdx = 0;
        for (int p = PRICE_ARRAY_SIZE - 1; p >= 1 && bidIdx < MAX_LEVEL_DEPTH; p--) {
            if (yesQty[p] > 0) {
                book.bids[bidIdx].update((long) p * CENTS_TO_PRICE_SCALE, yesQty[p] * Statics.SIZE_SCALING_FACTOR, 1L);
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
                book.asks[askIdx].update(
                        askPriceCents * CENTS_TO_PRICE_SCALE, noQty[p] * Statics.SIZE_SCALING_FACTOR, 1L);
                askIdx++;
            }
        }
        for (; askIdx < MAX_LEVEL_DEPTH; askIdx++) {
            book.asks[askIdx].reset();
        }
    }

    private void emitBookUpdate() {
        prepareEncoder();
        schema.encoder.timestampEvent(Mbp10Encoder.timestampEventNullValue());
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
