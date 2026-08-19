package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiSocketReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Encoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiSocketReaderTest {

    private static final String MARKET_TICKER = "TEST-TICKER";
    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            TEST_PRIVATE_KEY =
                    KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SequencedRingBuffer<Mbp10Schema> ringBuffer;
    private KalshiSocketReader reader;
    private WebSocketClient client;
    private WebSocketResponse response;
    private List<Mbp10Schema> captured;

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        ringBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        ringBuffer.handleEventsWith((globalSequence, templateId, buffer, length) -> {
            Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        ringBuffer.start();

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        // Use ":yes" suffix to verify it is stripped before subscription
        Listing listing = new Listing(
                1,
                new Exchange(2, "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                MARKET_TICKER + ":yes",
                "TEST-YES");
        reader = new KalshiSocketReader(
                new NullLogger(),
                ringBuffer,
                () -> 9_000_000_000L,
                null,
                listing,
                client,
                new JsonDecoder(),
                "test-api-key",
                TEST_PRIVATE_KEY);
        reader.buffer = false;
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        ringBuffer.shutdown();
    }

    @Test
    void snapshotDoesNotEmitAndBookPopulatedViaFirstDelta() throws Exception {
        processSnapshot();
        assertEquals(0, captured.size());

        // First delta should carry real timestamp and include snapshot levels
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.550","delta_fp":"0.00","side":"yes","ts_ms":1700000000000}}
                """);

        assertEquals(1, captured.size());
        Mbp10Schema schema = captured.get(0);
        // Best bid: highest YES price (55 cents), qty unchanged (delta_fp = 0)
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
        assertEquals(size("100"), schema.decoder.bidSize0());
        // Second bid: 50 cents YES
        assertEquals(price("0.50"), schema.decoder.bidPrice1());
        assertEquals(size("200"), schema.decoder.bidSize1());
        // Best ask: NO at 50 cents → YES ask at 50 cents (100-50), ahead of NO at 46 cents → ask at 54 cents
        assertEquals(price("0.50"), schema.decoder.askPrice0());
        assertEquals(size("75"), schema.decoder.askSize0());
        assertEquals(price("0.54"), schema.decoder.askPrice1());
        assertEquals(size("50"), schema.decoder.askSize1());
        assertEquals(Action.Modify, schema.decoder.action());
        assertEquals(Side.None, schema.decoder.side());
        assertEquals(Mbp10Encoder.sequenceNullValue(), schema.decoder.sequence());
        assertEquals(1700000000000L * 1_000_000L, schema.decoder.timestampEvent());
        assertEquals(Mbp10Encoder.priceNullValue(), schema.decoder.price());
        assertEquals(Mbp10Encoder.sizeNullValue(), schema.decoder.size());
    }

    @Test
    void deltaUpdatesBookAndTracksTimestamp() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.550","delta_fp":"50.00","side":"yes","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        // 55 cents YES: 100 + 50 = 150
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
        assertEquals(size("150"), schema.decoder.bidSize0());
        assertEquals(Mbp10Encoder.sequenceNullValue(), schema.decoder.sequence());
        assertEquals(1700000000000L * 1_000_000L, schema.decoder.timestampEvent());
    }

    @Test
    void negativeDeltaFloorsAtZero() throws Exception {
        processSnapshot();
        // Remove all 100 contracts at 55 cents YES
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.550","delta_fp":"-100.00","side":"yes","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        // 55 cents removed; 50 cents becomes best bid
        assertEquals(price("0.50"), schema.decoder.bidPrice0());

        // Apply a further negative delta that would go below zero — must floor at 0
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":3,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.500","delta_fp":"-999.00","side":"yes","ts_ms":1700000000001}}
                """);

        Mbp10Schema schema2 = captured.get(1);
        // No YES levels remain
        assertEquals(Mbp10Encoder.bidPrice0NullValue(), schema2.decoder.bidPrice0());
    }

    @Test
    void tradeBidSideEmitsCorrectFields() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"trade","sid":2,"seq":3,"msg":{"trade_id":"uuid","market_ticker":"TEST-TICKER",\
                "yes_price_dollars":"0.550","no_price_dollars":"0.450","count_fp":"10.00",\
                "taker_side":"yes","taker_book_side":"bid","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Bid, schema.decoder.side());
        assertEquals(price("0.55"), schema.decoder.price());
        assertEquals(size("10"), schema.decoder.size());
        assertEquals(1700000000000L * 1_000_000L, schema.decoder.timestampEvent());
        // Book levels are included with the trade
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
    }

    @Test
    void tradeAskSideEmitsAskSide() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"trade","sid":2,"seq":3,"msg":{"trade_id":"uuid","market_ticker":"TEST-TICKER",\
                "yes_price_dollars":"0.550","no_price_dollars":"0.450","count_fp":"5.00",\
                "taker_side":"no","taker_book_side":"ask","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Ask, schema.decoder.side());
        assertEquals(size("5"), schema.decoder.size());
    }

    @Test
    void yesLevelsMapsToDescendingBids() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[["0.1000","10.00"],["0.2000","20.00"],["0.3000","30.00"]],\
                "no_dollars_fp":[]}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.100","delta_fp":"0.00","side":"yes","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        // Best bid: highest YES price first
        assertEquals(price("0.30"), schema.decoder.bidPrice0());
        assertEquals(price("0.20"), schema.decoder.bidPrice1());
        assertEquals(price("0.10"), schema.decoder.bidPrice2());
        assertEquals(Mbp10Encoder.bidPrice3NullValue(), schema.decoder.bidPrice3());
    }

    @Test
    void noLevelsMapsToAscendingYesAsks() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[],\
                "no_dollars_fp":[["0.4000","40.00"],["0.5000","50.00"],["0.6000","60.00"]]}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.400","delta_fp":"0.00","side":"no","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        // NO at 60 cents → YES ask at 40 cents (best ask, since lowest YES ask price)
        // NO at 50 cents → YES ask at 50 cents
        // NO at 40 cents → YES ask at 60 cents
        assertEquals(price("0.40"), schema.decoder.askPrice0());
        assertEquals(price("0.50"), schema.decoder.askPrice1());
        assertEquals(price("0.60"), schema.decoder.askPrice2());
        assertEquals(Mbp10Encoder.askPrice3NullValue(), schema.decoder.askPrice3());
    }

    @Test
    void sequenceNumberIsNull() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":42,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.550","delta_fp":"10.00","side":"yes"}}
                """);

        assertEquals(Mbp10Encoder.sequenceNullValue(), captured.get(0).decoder.sequence());
    }

    @Test
    void unknownMessageTypeIsIgnored() throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody())
                .thenReturn(ByteBuffer.wrap(
                        """
                {"type":"subscribed","sid":1,"seq":0,"msg":{"channel":"orderbook_delta","market_tickers":["TEST-TICKER"]}}
                """
                                .getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
        // Brief pause to let the ring buffer flush any pending writes, then verify nothing was emitted
        Thread.sleep(50);
        assertEquals(0, captured.size());
    }

    @Test
    void emptySnapshotDoesNotEmit() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[],"no_dollars_fp":[]}}
                """);
        assertEquals(0, captured.size());

        // After a delta, the book should have no levels except the one added
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.550","delta_fp":"100.00","side":"yes","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
        assertEquals(Mbp10Encoder.bidPrice1NullValue(), schema.decoder.bidPrice1());
        assertEquals(Mbp10Encoder.askPrice0NullValue(), schema.decoder.askPrice0());
        assertEquals(Action.Modify, schema.decoder.action());
    }

    private void processSnapshot() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[["0.5500","100.00"],["0.5000","200.00"]],\
                "no_dollars_fp":[["0.4600","50.00"],["0.5000","75.00"]]}}
                """);
    }

    private void processNoEmit(String message) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
        Thread.sleep(50);
    }

    private void process(String message) throws Exception {
        int before = captured.size();
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();

        long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private long price(String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private long size(String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
