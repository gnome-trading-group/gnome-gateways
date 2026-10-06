package group.gnometrading.gateways.exchanges.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.polymarket.us.PolymarketUsInboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
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
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketUsInboundReaderTest {

    private static final String SLUG = "tec-mlb-nlchamp-2026-09-27-atl";
    private static final long NOW_NANOS = 1_700_000_000_123_000_000L;

    private SequencedRingBuffer<Mbp10Schema> ringBuffer;
    private PolymarketUsInboundReader reader;
    private WebSocketClient client;
    private WebSocketResponse response;
    private List<Mbp10Schema> captured;

    @BeforeEach
    void setUp() throws Exception {
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

        final byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate())
                .getBytes()
                .orElseThrow();
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "test-key",
                PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(seed)));

        // ":short" listings share the long-priced book; the suffix is stripped before subscribing.
        final Listing listing = new Listing(
                1,
                new Exchange(6, "POLYMARKET_US", "POLYMARKET_US", "us-east-1", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                SLUG + ":short",
                "PM_US-TEST-SHORT");
        reader = new PolymarketUsInboundReader(
                new NullLogger(),
                ringBuffer,
                () -> NOW_NANOS,
                new InboundJsonWebSocketWriter(client, new JsonEncoder()),
                listing,
                client,
                new JsonDecoder(),
                signer);
        reader.buffer = false;
        reader.pauseControl.release();
    }

    @AfterEach
    void tearDown() {
        ringBuffer.shutdown();
    }

    @Test
    void marketSlugStripsLegSuffix() {
        assertEquals("abc-def", PolymarketUsInboundReader.marketSlug("abc-def:long"));
        assertEquals("abc-def", PolymarketUsInboundReader.marketSlug("abc-def:short"));
        assertEquals("abc-def", PolymarketUsInboundReader.marketSlug("abc-def"));
    }

    @Test
    void beforeConnectSetsSignedHeaders() throws Exception {
        invoke("beforeConnect");

        verify(client).setHeader("X-PM-Access-Key", "test-key");
        verify(client).setHeader("X-PM-Timestamp", "1700000000123");
        verify(client).setHeader(eq("X-PM-Signature"), any());
    }

    @Test
    void subscribesToMarketDataAndTrades() throws Exception {
        final List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
                    final ByteBuffer payload = inv.getArgument(2);
                    sent.add(StandardCharsets.UTF_8.decode(payload.duplicate()).toString());
                    return null;
                })
                .when(client)
                .wrapMessage(any(), eq(Opcode.TEXT), any());

        invoke("subscribe");

        assertEquals(
                List.of(
                        "{\"subscribe\":{\"requestId\":\"md\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_MARKET_DATA\","
                                + "\"marketSlugs\":[\"" + SLUG + "\"]}}",
                        "{\"subscribe\":{\"requestId\":\"tr\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_TRADE\","
                                + "\"marketSlugs\":[\"" + SLUG + "\"]}}"),
                sent);
    }

    @Test
    void keepAliveSendsWebSocketPing() throws Exception {
        invoke("keepAlive");

        verify(client).wrapPingMessage(any());
    }

    @Test
    void marketDataRebuildsBook() throws Exception {
        process(
                """
                {"requestId":"md","subscriptionType":"SUBSCRIPTION_TYPE_MARKET_DATA","marketData":{\
                "marketSlug":"tec-mlb-nlchamp-2026-09-27-atl",\
                "bids":[{"px":{"value":"0.1510","currency":"USD"},"qty":"14.0000"},\
                {"px":{"value":"0.1420","currency":"USD"},"qty":"55.5"}],\
                "offers":[{"px":{"value":"0.1520","currency":"USD"},"qty":"1381.0000"}],\
                "state":"MARKET_STATE_OPEN",\
                "stats":{"lastTradePx":{"value":"0.15","currency":"USD"},"sharesTraded":"150000"},\
                "transactTime":"2026-10-02T14:22:56.542213551Z"}}
                """);

        assertEquals(1, captured.size());
        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.151"), schema.decoder.bidPrice0());
        assertEquals(size("14"), schema.decoder.bidSize0());
        assertEquals(price("0.142"), schema.decoder.bidPrice1());
        assertEquals(size("55.5"), schema.decoder.bidSize1());
        assertEquals(Mbp10Encoder.bidPrice2NullValue(), schema.decoder.bidPrice2());
        assertEquals(price("0.152"), schema.decoder.askPrice0());
        assertEquals(size("1381"), schema.decoder.askSize0());
        assertEquals(Mbp10Encoder.askPrice1NullValue(), schema.decoder.askPrice1());
        assertEquals(Action.Modify, schema.decoder.action());
        assertEquals(Side.None, schema.decoder.side());
        assertEquals(nanos("2026-10-02T14:22:56.542213551Z"), schema.decoder.timestampEvent());
        assertEquals(6, schema.decoder.exchangeId());
        assertEquals(3, schema.decoder.securityId());
    }

    @Test
    void laterSnapshotReplacesEarlierLevels() throws Exception {
        process(
                """
                {"marketData":{"bids":[{"px":{"value":"0.50","currency":"USD"},"qty":"10"},\
                {"px":{"value":"0.49","currency":"USD"},"qty":"20"}],\
                "offers":[{"px":{"value":"0.52","currency":"USD"},"qty":"5"}],"transactTime":"2026-10-02T14:00:00Z"}}
                """);
        process(
                """
                {"marketData":{"bids":[{"px":{"value":"0.48","currency":"USD"},"qty":"7"}],\
                "transactTime":"2026-10-02T14:00:01Z"}}
                """);

        final Mbp10Schema schema = captured.get(1);
        assertEquals(price("0.48"), schema.decoder.bidPrice0());
        assertEquals(size("7"), schema.decoder.bidSize0());
        assertEquals(Mbp10Encoder.bidPrice1NullValue(), schema.decoder.bidPrice1());
        // An omitted side means that side of the book is empty.
        assertEquals(Mbp10Encoder.askPrice0NullValue(), schema.decoder.askPrice0());
    }

    @Test
    void bookIsTruncatedToTenLevels() throws Exception {
        final StringBuilder bids = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                bids.append(',');
            }
            bids.append("{\"px\":{\"value\":\"0.")
                    .append(80 - i)
                    .append("\",\"currency\":\"USD\"},\"qty\":\"")
                    .append(i + 1)
                    .append("\"}");
        }
        process("{\"marketData\":{\"bids\":[" + bids + "],\"offers\":[]}}");

        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.80"), schema.decoder.bidPrice0());
        assertEquals(price("0.71"), schema.decoder.bidPrice9());
        assertEquals(size("10"), schema.decoder.bidSize9());
        assertEquals(Mbp10Encoder.timestampEventNullValue(), schema.decoder.timestampEvent());
    }

    @Test
    void tradeUsesTakerSideAndBookState() throws Exception {
        process(
                """
                {"marketData":{"bids":[{"px":{"value":"0.55","currency":"USD"},"qty":"3"}],\
                "offers":[{"px":{"value":"0.56","currency":"USD"},"qty":"4"}],"transactTime":"2026-10-02T14:00:00Z"}}
                """);
        process(
                """
                {"requestId":"tr","subscriptionType":"SUBSCRIPTION_TYPE_TRADE","trade":{\
                "marketSlug":"tec-mlb-nlchamp-2026-09-27-atl",\
                "price":{"value":"0.555","currency":"USD"},\
                "quantity":{"value":"0.50","currency":"USD"},\
                "tradeTime":"2024-01-15T10:30:00Z",\
                "maker":{"side":"ORDER_SIDE_BUY","intent":"ORDER_INTENT_BUY_LONG"},\
                "taker":{"side":"ORDER_SIDE_SELL","intent":"ORDER_INTENT_SELL_LONG"}}}
                """);

        final Mbp10Schema schema = captured.get(1);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Ask, schema.decoder.side());
        assertEquals(price("0.555"), schema.decoder.price());
        assertEquals(size("0.5"), schema.decoder.size());
        assertEquals(nanos("2024-01-15T10:30:00Z"), schema.decoder.timestampEvent());
        assertEquals(price("0.55"), schema.decoder.bidPrice0());
        assertEquals(price("0.56"), schema.decoder.askPrice0());
    }

    @Test
    void liveTradeMessageParses() throws Exception {
        // Captured from aec-dota2-lgd-xtreme-2026-10-02 on 2026-10-02.
        process(
                """
                {"requestId":"tr","subscriptionType":"SUBSCRIPTION_TYPE_TRADE","trade":{\
                "marketSlug":"aec-dota2-lgd-xtreme-2026-10-02","price":{"value":"0.6200","currency":"USD"},\
                "quantity":{"value":"53.4800","currency":"USD"},"tradeTime":"2026-10-02T15:17:29.394621421Z",\
                "maker":{"side":"ORDER_SIDE_SELL","intent":"ORDER_INTENT_UNDEFINED",\
                "outcomeSide":"OUTCOME_SIDE_UNSPECIFIED","action":"ORDER_ACTION_UNSPECIFIED"},\
                "taker":{"side":"ORDER_SIDE_BUY","intent":"ORDER_INTENT_BUY_LONG",\
                "outcomeSide":"OUTCOME_SIDE_YES","action":"ORDER_ACTION_BUY"},\
                "id":"CVR3KXG46YHR","state":"TRADE_STATE_NEW"}}
                """);

        final Mbp10Schema schema = captured.get(0);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Bid, schema.decoder.side());
        assertEquals(price("0.62"), schema.decoder.price());
        assertEquals(size("53.48"), schema.decoder.size());
        assertEquals(nanos("2026-10-02T15:17:29.394621421Z"), schema.decoder.timestampEvent());
    }

    @Test
    void takerBuyingShortHitsTheBids() throws Exception {
        process(
                """
                {"trade":{"price":{"value":"0.6","currency":"USD"},"quantity":{"value":"2","currency":"USD"},\
                "tradeTime":"2024-01-15T10:30:00Z","taker":{"side":"ORDER_SIDE_BUY","intent":"ORDER_INTENT_BUY_SHORT"}}}
                """);
        assertEquals(Side.Ask, captured.get(0).decoder.side());
    }

    @Test
    void takerSellingShortLiftsTheOffers() throws Exception {
        process(
                """
                {"trade":{"price":{"value":"0.6","currency":"USD"},"quantity":{"value":"2","currency":"USD"},\
                "tradeTime":"2024-01-15T10:30:00Z","taker":{"side":"ORDER_SIDE_SELL","intent":"ORDER_INTENT_SELL_SHORT"}}}
                """);
        assertEquals(Side.Bid, captured.get(0).decoder.side());
    }

    @Test
    void undefinedIntentFallsBackToOrderSide() throws Exception {
        process(
                """
                {"trade":{"price":{"value":"0.6","currency":"USD"},"quantity":{"value":"2","currency":"USD"},\
                "tradeTime":"2024-01-15T10:30:00Z","taker":{"side":"ORDER_SIDE_SELL","intent":"ORDER_INTENT_UNDEFINED"}}}
                """);
        assertEquals(Side.Ask, captured.get(0).decoder.side());
    }

    @Test
    void takerBuyIsBidAggressor() throws Exception {
        process(
                """
                {"trade":{"price":{"value":"0.6","currency":"USD"},"quantity":{"value":"2","currency":"USD"},\
                "tradeTime":"2024-01-15T10:30:00Z","taker":{"side":"ORDER_SIDE_BUY","intent":"ORDER_INTENT_BUY_LONG"}}}
                """);
        assertEquals(Side.Bid, captured.get(0).decoder.side());
    }

    @Test
    void heartbeatAndErrorsEmitNothing() throws Exception {
        processNoEmit("{\"heartbeat\":{}}");
        processNoEmit("{\"requestId\":\"md\",\"error\":\"invalid market slug\"}");
        assertEquals(0, captured.size());
    }

    private void invoke(final String methodName) throws Exception {
        Method method = null;
        for (Class<?> c = reader.getClass(); method == null && c != null; c = c.getSuperclass()) {
            try {
                method = c.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        method.setAccessible(true);
        method.invoke(reader);
    }

    private void processNoEmit(final String message) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
        Thread.sleep(50);
    }

    private void process(final String message) throws Exception {
        final int before = captured.size();
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();

        final long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private static long nanos(final String rfc3339) {
        final Instant instant = Instant.parse(rfc3339);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private static long price(final String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long size(final String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
