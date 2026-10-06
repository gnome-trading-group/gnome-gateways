package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiInboundReader;
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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiInboundReaderTest {

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
    private KalshiInboundReader reader;
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
                new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                MARKET_TICKER + ":yes",
                "TEST-YES");
        reader = new KalshiInboundReader(
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
        reader.pauseControl.release();
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
        // Best ask: NO level at 50 cents (YES-leg priced) ahead of the NO level at 54 cents
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
    void subCentSnapshotLevelsStayDistinct() throws Exception {
        // tapered_deci_cent markets tick in tenths of a cent near 0 and 1
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[["0.0010","10.00"],["0.0900","20.00"],["0.0950","30.00"]],\
                "no_dollars_fp":[["0.9910","40.00"],["0.9990","50.00"]]}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.0950","delta_fp":"0.00","side":"yes","ts_ms":1700000000000}}
                """);

        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.095"), schema.decoder.bidPrice0());
        assertEquals(size("30"), schema.decoder.bidSize0());
        assertEquals(price("0.09"), schema.decoder.bidPrice1());
        assertEquals(size("20"), schema.decoder.bidSize1());
        assertEquals(price("0.001"), schema.decoder.bidPrice2());
        assertEquals(size("10"), schema.decoder.bidSize2());
        assertEquals(price("0.991"), schema.decoder.askPrice0());
        assertEquals(size("40"), schema.decoder.askSize0());
        assertEquals(price("0.999"), schema.decoder.askPrice1());
        assertEquals(size("50"), schema.decoder.askSize1());
    }

    @Test
    void coarserTicksAndShortDecimalsLandOnTheirOwnLevels() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[["0.1","10.00"],["0.20","20.00"],["0.0100","30.00"],["0.0","99.00"]],\
                "no_dollars_fp":[["0.9","40.00"],["0.990","50.00"],["1.0","99.00"]]}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.2","delta_fp":"5.00","side":"yes","ts_ms":1700000000000}}
                """);

        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.20"), schema.decoder.bidPrice0());
        assertEquals(size("25"), schema.decoder.bidSize0(), "0.2 and 0.20 are the same level");
        assertEquals(price("0.10"), schema.decoder.bidPrice1());
        assertEquals(price("0.01"), schema.decoder.bidPrice2());
        assertEquals(Mbp10Encoder.bidPrice3NullValue(), schema.decoder.bidPrice3(), "0 is not a tradable price");
        assertEquals(price("0.90"), schema.decoder.askPrice0());
        assertEquals(price("0.99"), schema.decoder.askPrice1());
        assertEquals(Mbp10Encoder.askPrice2NullValue(), schema.decoder.askPrice2(), "nor is 1");
    }

    @Test
    void subCentDeltaUpdatesItsOwnLevel() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.5550","delta_fp":"25.00","side":"yes","ts_ms":1700000000000}}
                """);

        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.555"), schema.decoder.bidPrice0());
        assertEquals(size("25"), schema.decoder.bidSize0());
        assertEquals(price("0.55"), schema.decoder.bidPrice1());
        assertEquals(size("100"), schema.decoder.bidSize1(), "the whole-cent level is untouched");
    }

    @Test
    void priceFinerThanAnyKalshiTick_IsDroppedNotMergedIntoANeighbour() throws Exception {
        processSnapshot();
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.5505","delta_fp":"25.00","side":"yes","ts_ms":1700000000000}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":3,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.5500","delta_fp":"0.00","side":"yes","ts_ms":1700000000001}}
                """);

        final Mbp10Schema schema = captured.get(captured.size() - 1);
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
        assertEquals(size("100"), schema.decoder.bidSize0());
        assertEquals(price("0.50"), schema.decoder.bidPrice1());
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
    void subscribeOptsIntoYesLegPricing() throws Exception {
        final List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
                    final ByteBuffer payload = inv.getArgument(2);
                    sent.add(StandardCharsets.UTF_8.decode(payload.duplicate()).toString());
                    return null;
                })
                .when(client)
                .wrapMessage(any(), eq(Opcode.TEXT), any());
        final KalshiInboundReader subscriber = new KalshiInboundReader(
                new NullLogger(),
                ringBuffer,
                () -> 9_000_000_000L,
                new InboundJsonWebSocketWriter(client, new JsonEncoder()),
                new Listing(
                        1,
                        new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                        new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                        MARKET_TICKER + ":no",
                        "TEST-NO"),
                client,
                new JsonDecoder(),
                "test-api-key",
                TEST_PRIVATE_KEY);

        final Method subscribe = KalshiInboundReader.class.getDeclaredMethod("subscribe");
        subscribe.setAccessible(true);
        subscribe.invoke(subscriber);

        assertEquals(
                List.of("{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"orderbook_delta\",\"trade\"],"
                        + "\"market_tickers\":[\"TEST-TICKER\"],\"use_yes_price\":true}}"),
                sent);
    }

    @Test
    void yesPricedNoLevelsMapToAscendingAsks() throws Exception {
        processNoEmit(
                """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{"market_ticker":"TEST-TICKER",\
                "yes_dollars_fp":[],\
                "no_dollars_fp":[["0.6000","40.00"],["0.5000","50.00"],["0.4000","60.00"]]}}
                """);
        process(
                """
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{"market_ticker":"TEST-TICKER",\
                "price_dollars":"0.600","delta_fp":"0.00","side":"no","ts_ms":1700000000000}}
                """);

        Mbp10Schema schema = captured.get(0);
        // use_yes_price: NO levels are already YES-priced asks, sorted ascending
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
                "no_dollars_fp":[["0.5400","50.00"],["0.5000","75.00"]]}}
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
