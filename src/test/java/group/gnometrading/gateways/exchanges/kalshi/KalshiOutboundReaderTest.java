package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
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

class KalshiOutboundReaderTest {

    private static final String ORDER_ID = "kalshi-order-uuid-abc123";
    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final long ORIG_QTY = qty("10.0");
    private static final long EVENT_MS = 1_700_000_000_000L;

    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            TEST_PRIVATE_KEY =
                    KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private ManyToOneRingBuffer<OrderContext> completionQueue;
    private WebSocketClient client;
    private WebSocketResponse response;
    private KalshiOutboundReader reader;
    private List<OrderExecutionReport> captured;

    @BeforeEach
    void setUp() throws Exception {
        captured = new CopyOnWriteArrayList<>();
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        execReportBuffer.handleEventsWith((globalSeq, templateId, buffer, length) -> {
            final OrderExecutionReport copy = new OrderExecutionReport();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        execReportBuffer.start();

        contextQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        rejectQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        completionQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        final Listing listing = new Listing(
                7,
                new Exchange(2, "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                "KALSHI-MARKET:yes",
                "KALSHI-YES");

        reader = new KalshiOutboundReader(
                new NullLogger(),
                execReportBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                new KalshiAuthSigner("test-api-key", TEST_PRIVATE_KEY));
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    // ========== Resting / NEW ==========

    @Test
    void resting_WithNoPriorFills_EmitsNewExecReport() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(OrderStatus.NEW, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(ORIG_QTY, report.decoder.leavesQty());
    }

    @Test
    void resting_AfterPartialFill_IsNoOp() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);

        // First: partial fill
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        // Second: another resting event with same fill count — should be a no-op
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS + 1));
        assertEquals(1, captured.size()); // still just the one partial fill
    }

    // ========== Partial fill ==========

    @Test
    void partialFill_EmitsPartialFillReport() throws Exception {
        enqueueContext(ORDER_ID, qty("20.0"), 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.PARTIAL_FILL, report.decoder.execType());
        assertEquals(OrderStatus.PARTIALLY_FILLED, report.decoder.orderStatus());
        assertEquals(qty("5.0"), report.decoder.filledQty());
        assertEquals(qty("5.0"), report.decoder.cumulativeQty());
        assertEquals(qty("15.0"), report.decoder.leavesQty());
    }

    @Test
    void partialFill_FillPriceComputedFromCostDelta() throws Exception {
        // fillCount=5, takerCost=$2.80 → fillPrice = 2_800_000_000 * 1_000_000 / 5_000_000 = 560_000_000 (56¢)
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(price("0.56"), captured.get(0).decoder.fillPrice());
    }

    @Test
    void multipleFills_CostDeltaComputedCorrectly() throws Exception {
        // Fill 1: 5 contracts at cost $2.80 total → 56¢ each
        // Fill 2: 3 more contracts at additional cost $1.50 → 50¢ each
        enqueueContext(ORDER_ID, qty("20.0"), 0);

        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        process(userOrderEvent(ORDER_ID, "resting", "8.00", "12.00", "4.3000", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        assertEquals(2, captured.size());
        final OrderExecutionReport second = captured.get(1);
        assertEquals(ExecType.PARTIAL_FILL, second.decoder.execType());
        assertEquals(qty("3.0"), second.decoder.filledQty());
        assertEquals(qty("8.0"), second.decoder.cumulativeQty());
        assertEquals(qty("12.0"), second.decoder.leavesQty());
        // costDelta = 4_300_000_000 - 2_800_000_000 = 1_500_000_000; fillDelta = 3_000_000
        // fillPrice = 1_500_000_000 * 1_000_000 / 3_000_000 = 500_000_000 (50¢)
        assertEquals(price("0.50"), second.decoder.fillPrice());
    }

    // ========== Full fill ==========

    @Test
    void executed_Status_EmitsFillReport() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(OrderStatus.FILLED, report.decoder.orderStatus());
        assertEquals(qty("10.0"), report.decoder.filledQty());
        assertEquals(qty("10.0"), report.decoder.cumulativeQty());
        assertEquals(0, report.decoder.leavesQty());
    }

    @Test
    void fillCountIncrease_WithZeroRemaining_EmitsFillReport() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        // remaining_count_fp=0 means fully filled even if status is still "resting"
        process(userOrderEvent(ORDER_ID, "resting", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());
        assertEquals(OrderStatus.FILLED, captured.get(0).decoder.orderStatus());
    }

    @Test
    void fullFill_ReleasesContext_SubsequentMessageIgnored() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());

        // Context released — subsequent message is silently ignored
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS + 1));
        assertEquals(1, captured.size());
    }

    @Test
    void fullFill_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 42L);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(42L, completions.get(0));
    }

    // ========== Cancel ==========

    @Test
    void canceled_Status_EmitsCancelReport() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "canceled", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(OrderStatus.CANCELED, report.decoder.orderStatus());
        assertEquals(0, report.decoder.leavesQty());
    }

    @Test
    void canceled_AfterPartialFill_CumulativeQtyPreserved() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        process(userOrderEvent(ORDER_ID, "canceled", "3.00", "0.00", "1.8000", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        final OrderExecutionReport cancel = captured.get(1);
        assertEquals(ExecType.CANCEL, cancel.decoder.execType());
        assertEquals(qty("3.0"), cancel.decoder.cumulativeQty());
        assertEquals(0, cancel.decoder.leavesQty());
    }

    @Test
    void canceled_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 55L);
        process(userOrderEvent(ORDER_ID, "canceled", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(55L, completions.get(0));
    }

    // ========== Edge cases ==========

    @Test
    void unknownOrderId_IsIgnored() throws Exception {
        // No context enqueued — reader silently skips
        process(userOrderEvent("unknown-uuid-xyz", "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        assertEquals(0, captured.size());
    }

    @Test
    void partialFill_DoesNotEnqueueCompletion() throws Exception {
        enqueueContext(ORDER_ID, qty("20.0"), 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(0, drainCompletionQueue().size());
    }

    @Test
    void rejectQueue_PublishesExecReport() throws Exception {
        enqueueReject(ExecType.REJECT, OrderStatus.REJECTED);
        process("");
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(OrderStatus.REJECTED, report.decoder.orderStatus());
    }

    @Test
    void execReport_TimestampsPopulated() throws Exception {
        enqueueContext(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(EVENT_MS * 1_000_000L, report.decoder.timestampEvent());
        assertEquals(FIXED_NANO, report.decoder.timestampRecv());
    }

    @Test
    void execReport_HeaderFieldsMatchContext() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 42L);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(2, report.decoder.exchangeId());
        assertEquals(3L, report.decoder.securityId());
        assertEquals(42L, report.decoder.orderId());
    }

    // ========== Helpers ==========

    private void process(final String json) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
    }

    private void waitForReports(final int count) {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (captured.size() < count && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private void enqueueContext(final String orderId, final long originalQty, final long cumulativeFilledQty) {
        enqueueContextWithOrderId(orderId, originalQty, cumulativeFilledQty, 1L);
    }

    private void enqueueContextWithOrderId(
            final String orderId, final long originalQty, final long cumulativeFilledQty, final long internalOrderId) {
        final int idx = contextQueue.tryClaim();
        final OrderContext ctx = contextQueue.indexAt(idx);
        ctx.reset();
        ctx.orderId = internalOrderId;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.originalQty = originalQty;
        ctx.cumulativeFilledQty = cumulativeFilledQty;
        ctx.leavesQty = originalQty - cumulativeFilledQty;
        final byte[] idBytes = orderId.getBytes(StandardCharsets.UTF_8);
        ctx.exchangeOrderIdLength = Math.min(idBytes.length, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        System.arraycopy(idBytes, 0, ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
        contextQueue.commit(idx);
    }

    private void enqueueReject(final ExecType execType, final OrderStatus orderStatus) {
        final int idx = rejectQueue.tryClaim();
        final OrderContext ctx = rejectQueue.indexAt(idx);
        ctx.reset();
        ctx.execType = execType;
        ctx.orderStatus = orderStatus;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.orderId = 99L;
        rejectQueue.commit(idx);
    }

    private List<Long> drainCompletionQueue() {
        final List<Long> result = new ArrayList<>();
        completionQueue.read(ctx -> result.add(ctx.orderId), Integer.MAX_VALUE);
        return result;
    }

    private static String userOrderEvent(
            final String orderId,
            final String status,
            final String fillCountFp,
            final String remainingCountFp,
            final String takerFillCost,
            final String makerFillCost,
            final long timestampMs) {
        return "{\"type\":\"user_order\",\"msg\":{"
                + "\"order_id\":\"" + orderId + "\","
                + "\"status\":\"" + status + "\","
                + "\"fill_count_fp\":\"" + fillCountFp + "\","
                + "\"remaining_count_fp\":\"" + remainingCountFp + "\","
                + "\"taker_fill_cost_dollars\":\"" + takerFillCost + "\","
                + "\"maker_fill_cost_dollars\":\"" + makerFillCost + "\","
                + "\"last_updated_ts_ms\":" + timestampMs
                + "}}";
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }
}
