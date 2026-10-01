package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Payloads follow the user-channel shapes in Polymarket's docs (order and trade events). */
class PolymarketIntlOutboundReaderTest {

    private static final String ORDER_A = "0x" + "aa".repeat(32);
    private static final String ORDER_B = "0x" + "bb".repeat(32);
    private static final String SOMEONE_ELSE = "0x" + "ee".repeat(32);
    private static final String SENTINEL = "0x" + "99".repeat(32);
    private static final long SENTINEL_ORDER_ID = 99L;

    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final long ORIG_QTY = qty("10");
    private static final PolymarketIntlMarketInfo TAKER_ONLY_FEES = new PolymarketIntlMarketInfo(
            Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.05, 1.0, true, false, 0);

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private WebSocketClient client;
    private WebSocketResponse response;
    private PolymarketIntlOutboundReader reader;
    private List<OrderExecutionReport> captured;

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        execReportBuffer.handleEventsWith((globalSeq, templateId, buffer, length) -> {
            final OrderExecutionReport copy = new OrderExecutionReport();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        execReportBuffer.start();

        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        releasedOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        reader = buildReader(TAKER_ONLY_FEES);
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    // ========== Order events ==========

    @Test
    void placementLive_PublishesNew() throws Exception {
        enqueueOrder(ORDER_A, 1L);

        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(OrderStatus.NEW, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(ORIG_QTY, report.decoder.leavesQty());
        assertEquals(1L, report.decoder.orderId());
        assertEquals(FIXED_NANO, report.decoder.timestampRecv());
    }

    @Test
    void repeatedPlacement_IsNotAcknowledgedTwice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        awaitReport(0);

        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        assertNoFurtherReports();
    }

    @Test
    void cancellation_PublishesCancelWithFillsSoFar_AndReleasesTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("MATCHED", ORDER_A, "0.55", "3", "[]"));

        process(orderEvent("CANCELLATION", "CANCELED", ORDER_A));

        final OrderExecutionReport cancel = awaitReport(2);
        assertEquals(ExecType.CANCEL, cancel.decoder.execType());
        assertEquals(qty("3"), cancel.decoder.cumulativeQty());
        assertEquals(0, cancel.decoder.leavesQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void eventsForOrdersWeDoNotHaveAreIgnored() throws Exception {
        process(orderEvent("PLACEMENT", "LIVE", SOMEONE_ELSE));
        process(trade("MATCHED", SOMEONE_ELSE, "0.55", "3", "[]"));

        assertNoFurtherReports();
    }

    // ========== Trades ==========

    @Test
    void takerFill_UsesTopLevelSizeAndPrice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.55", "4", makerLeg(SOMEONE_ELSE, "4", "0.55")));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(OrderStatus.PARTIALLY_FILLED, fill.decoder.orderStatus());
        assertEquals(qty("4"), fill.decoder.filledQty());
        assertEquals(price("0.55"), fill.decoder.fillPrice());
        assertEquals(qty("4"), fill.decoder.cumulativeQty());
        assertEquals(qty("6"), fill.decoder.leavesQty());
        assertEquals(Liquidity.TAKER, fill.decoder.liquidity());
        assertEquals(RejectReason.NULL_VAL, fill.decoder.rejectReason());
    }

    @Test
    void makerFill_UsesOurLegNotTheTakersSizeAndPrice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade(
                "MATCHED",
                SOMEONE_ELSE,
                "0.57",
                "10",
                makerLeg(SOMEONE_ELSE, "6", "0.57") + "," + makerLeg(ORDER_A, "4", "0.55")));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(qty("4"), fill.decoder.filledQty());
        assertEquals(price("0.55"), fill.decoder.fillPrice());
        assertEquals(Liquidity.MAKER, fill.decoder.liquidity());
        assertEquals(0L, fill.decoder.fee(), "makers pay no fee on a taker-only market");
        assertNoFurtherReports();
    }

    @Test
    void oneTradeFillingTwoOfOurOrders_ReportsEach() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        enqueueOrder(ORDER_B, 2L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(orderEvent("PLACEMENT", "LIVE", ORDER_B));

        process(trade(
                "MATCHED",
                SOMEONE_ELSE,
                "0.55",
                "13",
                makerLeg(ORDER_A, "10", "0.55") + "," + makerLeg(ORDER_B, "3", "0.55")));

        final OrderExecutionReport first = awaitReport(2);
        final OrderExecutionReport second = awaitReport(3);
        assertEquals(1L, first.decoder.orderId());
        assertEquals(ExecType.FILL, first.decoder.execType());
        assertEquals(2L, second.decoder.orderId());
        assertEquals(ExecType.PARTIAL_FILL, second.decoder.execType());
        assertEquals(qty("3"), second.decoder.filledQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void fullFill_PublishesFillAndReleasesTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.55", "10", "[]"));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.FILL, fill.decoder.execType());
        assertEquals(OrderStatus.FILLED, fill.decoder.orderStatus());
        assertEquals(0, fill.decoder.leavesQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void fillBeforeAnyPlacement_IsAcknowledgedFirst() throws Exception {
        enqueueOrder(ORDER_A, 1L);

        process(trade("MATCHED", ORDER_A, "0.55", "10", "[]"));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        assertEquals(ExecType.FILL, awaitReport(1).decoder.execType());
    }

    @Test
    void settlementStages_AreNotFillsAgain() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("MATCHED", ORDER_A, "0.55", "4", "[]"));
        awaitReport(1);

        process(trade("MINED", ORDER_A, "0.55", "4", "[]"));
        process(trade("CONFIRMED", ORDER_A, "0.55", "4", "[]"));

        assertNoFurtherReports();
    }

    @Test
    void failedSettlement_DoesNotCancelTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        awaitReport(0);

        process(trade("FAILED", ORDER_A, "0.55", "4", "[]"));

        assertNoFurtherReports();
        assertEquals(List.of(), drainReleased());
    }

    // ========== Fees ==========

    @Test
    void takerFee_FollowsTheMarketsCurve() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.50", "10", "[]"));

        // 10 × 0.05 × (0.5 × 0.5) = 0.125
        assertEquals(125_000_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_AppliesTheExponent() throws Exception {
        reader = buildReader(new PolymarketIntlMarketInfo(
                Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.02, 2.0, true, false, 0));
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.50", "10", "[]"));

        // 10 × 0.02 × (0.5 × 0.5)^2 = 0.0125
        assertEquals(12_500_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void makerFee_ChargedWhenTheMarketIsNotTakerOnly() throws Exception {
        reader = buildReader(new PolymarketIntlMarketInfo(
                Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.05, 1.0, false, false, 0));
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", SOMEONE_ELSE, "0.50", "10", makerLeg(ORDER_A, "10", "0.50")));

        assertEquals(125_000_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_RoundsToTheVenuesFiveDecimals() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.97", "1.37", "[]"));

        // 1.37 × 0.05 × (0.97 × 0.03) = 0.00199335 → 0.00199
        assertEquals(1_990_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_BelowTheSmallestUnitIsFree() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.999", "0.01", "[]"));

        // 0.01 × 0.05 × (0.999 × 0.001) ≈ 0.0000005 → rounds to zero
        assertEquals(0L, awaitReport(1).decoder.fee());
    }

    // ========== Framing ==========

    @Test
    void batchedFrame_HandlesEveryEvent() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        enqueueOrder(ORDER_B, 2L);

        process("[" + orderEvent("PLACEMENT", "LIVE", ORDER_A) + "," + orderEvent("PLACEMENT", "LIVE", ORDER_B) + "]");

        assertEquals(1L, awaitReport(0).decoder.orderId());
        assertEquals(2L, awaitReport(1).decoder.orderId());
    }

    @Test
    void pong_IsIgnored() throws Exception {
        process("PONG");

        assertNoFurtherReports();
    }

    @Test
    void writerReject_IsPublished() throws Exception {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext ctx = writerReportQueue.indexAt(idx);
        ctx.reset();
        ctx.execType = ExecType.REJECT;
        ctx.orderStatus = OrderStatus.REJECTED;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        ctx.orderId = 5L;
        writerReportQueue.commit(idx);

        process("PONG");

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(RejectReason.EXCHANGE_REJECTED, report.decoder.rejectReason());
    }

    // ========== helpers ==========

    private PolymarketIntlOutboundReader buildReader(final PolymarketIntlMarketInfo marketInfo) {
        final Listing listing = new Listing(
                7,
                new Exchange(2, "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                "condition-1:token-yes",
                "TEST-YES");
        final PolymarketIntlOutboundReader built = new PolymarketIntlOutboundReader(
                new NullLogger(),
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                "test-key",
                "test-secret",
                "test-passphrase",
                marketInfo);
        built.pause = false;
        return built;
    }

    /** Publishes a sentinel order's NEW and checks it is the only report after the ones already seen. */
    private void assertNoFurtherReports() throws Exception {
        final int seen = captured.size();
        enqueueOrder(SENTINEL, SENTINEL_ORDER_ID);
        process(orderEvent("PLACEMENT", "LIVE", SENTINEL));
        assertEquals(SENTINEL_ORDER_ID, awaitReport(seen).decoder.orderId());
        assertEquals(seen + 1, captured.size());
    }

    private void process(final String json) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
    }

    private OrderExecutionReport awaitReport(final int index) {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (captured.size() <= index && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(captured.size() > index, "expected report #" + index + ", got " + captured.size());
        return captured.get(index);
    }

    private void enqueueOrder(final String orderHash, final long orderId) {
        final int idx = newOrderQueue.tryClaim();
        final OrderContext ctx = newOrderQueue.indexAt(idx);
        ctx.reset();
        ctx.orderId = orderId;
        ctx.clientOidCounter = orderId;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.originalQty = ORIG_QTY;
        ctx.leavesQty = ORIG_QTY;
        final byte[] hashBytes = orderHash.getBytes(StandardCharsets.US_ASCII);
        ctx.exchangeOrderIdLength = hashBytes.length;
        System.arraycopy(hashBytes, 0, ctx.exchangeOrderIdBytes, 0, hashBytes.length);
        ctx.correlationIdLength = hashBytes.length;
        System.arraycopy(hashBytes, 0, ctx.correlationIdBytes, 0, hashBytes.length);
        newOrderQueue.commit(idx);
    }

    private List<Long> drainReleased() {
        final List<Long> result = new ArrayList<>();
        releasedOrderQueue.read(ctx -> result.add(ctx.clientOidCounter), Integer.MAX_VALUE);
        return result;
    }

    private static String orderEvent(final String type, final String status, final String orderHash) {
        return "{\"asset_id\":\"token-yes\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\""
                + orderHash + "\",\"market\":\"condition-1\",\"original_size\":\"10\",\"outcome\":\"YES\","
                + "\"owner\":\"test-key\",\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":\"0\","
                + "\"status\":\"" + status + "\",\"timestamp\":\"1700000000000\",\"type\":\"" + type + "\"}";
    }

    private static String trade(
            final String status, final String takerOrderId, final String price, final String size, String makers) {
        if (!makers.startsWith("[")) {
            makers = "[" + makers + "]";
        }
        return "{\"asset_id\":\"token-yes\",\"event_type\":\"trade\",\"fee_rate_bps\":\"0\",\"id\":\"trade-1\","
                + "\"maker_orders\":" + makers + ",\"market\":\"condition-1\",\"outcome\":\"YES\","
                + "\"owner\":\"test-key\",\"price\":\"" + price + "\",\"side\":\"BUY\",\"size\":\"" + size + "\","
                + "\"status\":\"" + status + "\",\"taker_order_id\":\"" + takerOrderId + "\","
                + "\"timestamp\":\"1700000000000\",\"trader_side\":\"TAKER\",\"type\":\"TRADE\"}";
    }

    private static String makerLeg(final String orderId, final String matchedAmount, final String price) {
        return "{\"asset_id\":\"token-yes\",\"matched_amount\":\"" + matchedAmount + "\",\"order_id\":\"" + orderId
                + "\",\"outcome\":\"YES\",\"owner\":\"other\",\"price\":\"" + price + "\"}";
    }

    private static long price(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long qty(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
