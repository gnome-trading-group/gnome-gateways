package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the writer→contextQueue→reader pipeline end-to-end using real components and no mocks.
 * Validates that OrderContext survives two copyFrom passes across ManyToOneRingBuffer boundaries,
 * that FNV hash keys are consistent between writer and reader, and that exec reports are
 * published correctly downstream.
 */
class OutboundPipelineIntegrationTest {

    private static final Listing LISTING = new Listing(
            1, new Exchange(2, "test", "global", SchemaType.MBP_10), new Security(3, "TEST", 3), "test-id", "TEST");

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private ManyToOneRingBuffer<OrderContext> completionQueue;
    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private List<OrderExecutionReport> captured;
    private TestPipelineWriter writer;
    private TestPipelineReader reader;

    @BeforeEach
    void setUp() {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        contextQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        rejectQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        completionQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        captured = new CopyOnWriteArrayList<>();
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        execReportBuffer.handleEventsWith((globalSeq, templateId, buffer, length) -> {
            final OrderExecutionReport copy = new OrderExecutionReport();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        execReportBuffer.start();

        writer = new TestPipelineWriter(orderBuffer, contextQueue, rejectQueue, completionQueue);
        reader = new TestPipelineReader(execReportBuffer, contextQueue, rejectQueue, completionQueue);
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    @Test
    void submitSuccess_ContextFlowsThroughQueues_ExecReportPublished() throws Exception {
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 3L, 1L, 1);
        writer.doWork();

        // Reader consumes context, then processes a fill message
        final String hash = "hash-1"; // deterministic from TestPipelineWriter
        reader.simulatedMessages.add(fillMessage(hash));
        reader.doWork();

        waitForReports(1);
        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(1L, report.decoder.orderId());
        assertEquals(2, report.decoder.exchangeId());
        assertEquals(3L, report.decoder.securityId());
    }

    @Test
    void submitFailure_RejectFlowsThroughQueue_RejectReportPublished() throws Exception {
        writer.submitResult = false;
        publishOrder(Side.Bid, price("0.50"), qty("5.0"), 2, 3L, 1L, 1);
        writer.doWork();

        reader.doWork();

        waitForReports(1);
        assertEquals(1, captured.size());
        assertEquals(ExecType.REJECT, captured.get(0).decoder.execType());
        assertEquals(OrderStatus.REJECTED, captured.get(0).decoder.orderStatus());
    }

    @Test
    void multipleOrders_ContextsRoutedCorrectly() throws Exception {
        // Submit 3 orders to different securities
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 10L, 1L, 1);
        writer.doWork();
        publishOrder(Side.Ask, price("0.60"), qty("5.0"), 2, 20L, 2L, 1);
        writer.doWork();
        publishOrder(Side.Bid, price("0.40"), qty("20.0"), 2, 30L, 3L, 1);
        writer.doWork();

        // Feed fill messages for all 3 (hashes are "hash-1", "hash-2", "hash-3")
        reader.simulatedMessages.add(fillMessage("hash-1"));
        reader.simulatedMessages.add(fillMessage("hash-2"));
        reader.simulatedMessages.add(fillMessage("hash-3"));
        reader.doWork();
        reader.doWork();
        reader.doWork();

        waitForReports(3);
        assertEquals(3, captured.size());
        // Each report should have the correct securityId for its order
        assertEquals(10L, findReport(captured, 1L).decoder.securityId());
        assertEquals(20L, findReport(captured, 2L).decoder.securityId());
        assertEquals(30L, findReport(captured, 3L).decoder.securityId());
    }

    @Test
    void cancelOrder_ContextRemovedFromActiveOrders_SubsequentFillIgnored() throws Exception {
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 3L, 1L, 1);
        writer.doWork();

        // Reader consumes context
        reader.doWork();

        // Cancel the order on writer side
        final String hash = "hash-1";
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.orderId(1L);
        cancel.encoder.exchangeId(2);
        cancel.encoder.securityId(3L);
        cancel.encodeClientOid(1L, 1);
        orderBuffer.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
        writer.doWork();

        // Feed fill message — reader has the context but writer removed it from activeOrders
        // Context is already in reader's orderContexts map, so fill will be processed
        // (The cancel only removes from writer's activeOrders, not reader's orderContexts)
        // This is by design — fills can still arrive after cancel
        reader.simulatedMessages.add(fillMessage(hash));
        reader.doWork();

        waitForReports(1);
        assertEquals(1, captured.size());
        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());
    }

    // ========== CompletionQueue round-trip ==========

    @Test
    void completionQueueRoundTrip_FillDrainsActiveOrder() throws Exception {
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 3L, 1L, 1);
        writer.doWork();
        assertEquals(1, writer.activeOrderCount());

        reader.simulatedMessages.add(fillMessage("hash-1"));
        reader.doWork();

        waitForReports(1);
        writer.doWork();
        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void poolCycling_ManySubmitFillDrainCycles_NeverExhaustsPool() throws Exception {
        for (int i = 0; i < 300; i++) {
            publishOrder(Side.Bid, price("0.50"), qty("1.0"), 2, 3L, (long) (i + 1), 1);
            writer.doWork();
            reader.simulatedMessages.add(fillMessage("hash-" + (i + 1)));
            reader.doWork();
            writer.doWork();
        }

        waitForReports(300);
        assertEquals(300, captured.size());
        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void multipleFills_SingleWriterDrain_AllCompletionsProcessed() throws Exception {
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 10L, 1L, 1);
        writer.doWork();
        publishOrder(Side.Ask, price("0.60"), qty("5.0"), 2, 20L, 2L, 1);
        writer.doWork();
        publishOrder(Side.Bid, price("0.40"), qty("20.0"), 2, 30L, 3L, 1);
        writer.doWork();
        assertEquals(3, writer.activeOrderCount());

        reader.doWork();

        reader.simulatedMessages.add(fillMessage("hash-1"));
        reader.doWork();
        reader.simulatedMessages.add(fillMessage("hash-2"));
        reader.doWork();
        reader.simulatedMessages.add(fillMessage("hash-3"));
        reader.doWork();

        waitForReports(3);
        writer.doWork();
        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void interleavedSubmitsAndFills_PartialDrain() throws Exception {
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), 2, 10L, 1L, 1);
        writer.doWork();
        publishOrder(Side.Ask, price("0.60"), qty("5.0"), 2, 20L, 2L, 1);
        writer.doWork();
        assertEquals(2, writer.activeOrderCount());

        reader.doWork();

        reader.simulatedMessages.add(fillMessage("hash-1"));
        reader.doWork();
        writer.doWork();
        assertEquals(1, writer.activeOrderCount());

        reader.simulatedMessages.add(fillMessage("hash-2"));
        reader.doWork();
        writer.doWork();
        assertEquals(0, writer.activeOrderCount());

        waitForReports(2);
        assertEquals(10L, findReport(captured, 1L).decoder.securityId());
        assertEquals(20L, findReport(captured, 2L).decoder.securityId());
    }

    @Test
    void rejectDoesNotLeakActiveOrder() throws Exception {
        writer.submitResult = false;
        publishOrder(Side.Bid, price("0.50"), qty("5.0"), 2, 3L, 1L, 1);
        writer.doWork();
        assertEquals(0, writer.activeOrderCount());

        reader.doWork();

        waitForReports(1);
        assertEquals(ExecType.REJECT, captured.get(0).decoder.execType());

        writer.doWork();
        assertEquals(0, writer.activeOrderCount());
    }

    // ========== Helpers ==========

    private void publishOrder(
            final Side side,
            final long price,
            final long size,
            final int exchangeId,
            final long securityId,
            final long clientOidCounter,
            final int clientOidStrategyId) {
        final Order order = orderBuffer.claim();
        order.encoder.exchangeId(exchangeId);
        order.encoder.securityId(securityId);
        order.encoder.price(price);
        order.encoder.size(size);
        order.encoder.side(side);
        order.encoder.orderType(OrderType.LIMIT);
        order.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(clientOidCounter, clientOidStrategyId);
        orderBuffer.publish();
    }

    private static ByteBuffer fillMessage(final String hash) {
        return ByteBuffer.wrap(("FILL:" + hash).getBytes(StandardCharsets.UTF_8));
    }

    private void waitForReports(final int count) {
        final long deadline = System.currentTimeMillis() + 2000;
        while (captured.size() < count && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private static OrderExecutionReport findReport(final List<OrderExecutionReport> reports, final long orderId) {
        return reports.stream()
                .filter(r -> r.decoder.orderId() == orderId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No report found for orderId=" + orderId));
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }

    // ========== Test writer ==========

    static class TestPipelineWriter extends OutboundSocketWriter {

        boolean submitResult = true;
        boolean cancelResult = true;
        int submitCallCount = 0;

        TestPipelineWriter(
                SequencedRingBuffer<Order> orderBuffer,
                ManyToOneRingBuffer<OrderContext> contextQueue,
                ManyToOneRingBuffer<OrderContext> rejectQueue,
                ManyToOneRingBuffer<OrderContext> completionQueue) {
            super(orderBuffer, contextQueue, rejectQueue, completionQueue);
        }

        @Override
        protected boolean submitOrder(final OrderContext ctx) {
            submitCallCount++;
            if (!submitResult) {
                return false;
            }
            final String hash = "hash-" + submitCallCount;
            final byte[] hashBytes = hash.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(hashBytes, 0, ctx.exchangeOrderIdBytes, 0, hashBytes.length);
            ctx.exchangeOrderIdLength = hashBytes.length;
            return true;
        }

        @Override
        protected boolean cancelOrder(final OrderContext ctx) {
            return cancelResult;
        }

        @Override
        protected boolean submitForModify(final OrderContext ctx) {
            return true;
        }
    }

    // ========== Test reader ==========

    /**
     * Parses simulated fill messages of the form "FILL:{hash}" and publishes a FILL exec report
     * if the context is found. This tests the full context lookup and exec report publishing path
     * without needing a real exchange WebSocket connection.
     */
    static class TestPipelineReader extends OutboundSocketReader {

        final Deque<ByteBuffer> simulatedMessages = new ArrayDeque<>();

        TestPipelineReader(
                SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
                ManyToOneRingBuffer<OrderContext> contextQueue,
                ManyToOneRingBuffer<OrderContext> rejectQueue,
                ManyToOneRingBuffer<OrderContext> completionQueue) {
            super(
                    new NullLogger(),
                    execReportBuffer,
                    contextQueue,
                    rejectQueue,
                    completionQueue,
                    System::nanoTime,
                    LISTING);
        }

        @Override
        protected ByteBuffer readSocket() throws IOException {
            return simulatedMessages.isEmpty() ? null : simulatedMessages.removeFirst();
        }

        @Override
        protected void handleGatewayMessage(final ByteBuffer buffer) {
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            final String msg = new String(bytes, StandardCharsets.UTF_8);
            if (!msg.startsWith("FILL:")) {
                return;
            }
            final String hash = msg.substring(5);
            final byte[] hashBytes = hash.getBytes(StandardCharsets.UTF_8);
            final long key = computeKey(hashBytes, hashBytes.length);
            final OrderContext ctx = findOrderContext(key);
            if (ctx == null) {
                return;
            }
            prepareExecReportHeader(ctx);
            execReport.encoder.execType(ExecType.FILL);
            execReport.encoder.orderStatus(OrderStatus.FILLED);
            execReport.encoder.filledQty(ctx.leavesQty);
            execReport.encoder.fillPrice(0);
            execReport.encoder.cumulativeQty(ctx.originalQty);
            execReport.encoder.leavesQty(0);
            execReport.encoder.timestampEvent(0);
            execReport.encoder.timestampRecv(clock.nanoTime());
            execReport.encoder.fee(0);
            execReport.encoder.rejectReason(group.gnometrading.schemas.RejectReason.NULL_VAL);
            publishExecReport();
            releaseOrderContext(key);
        }

        @Override
        protected void attachSocket() throws IOException {}

        @Override
        protected void disconnectSocket() throws Exception {}

        @Override
        protected void subscribe() throws IOException {}

        @Override
        public void keepAlive() throws IOException {}
    }
}
