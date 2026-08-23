package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OutboundSocketReaderTest {

    private static final Listing LISTING = new Listing(
            1, new Exchange(2, "test", "global", SchemaType.MBP_10), new Security(3, "TEST", 3), "test-id", "TEST");

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private ManyToOneRingBuffer<OrderContext> completionQueue;
    private List<OrderExecutionReport> captured;
    private TestOutboundSocketReader reader;

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
        contextQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 256);
        rejectQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 256);
        completionQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        reader = new TestOutboundSocketReader(execReportBuffer, contextQueue, rejectQueue, completionQueue);
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    // ========== doWork — basic ==========

    @Test
    void doWork_DataReceived_CallsHandleGatewayMessage() throws Exception {
        reader.readResults.add(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        reader.doWork();

        assertEquals(1, reader.handleMessageCallCount.get());
    }

    @Test
    void doWork_NullBuffer_DoesNotCallHandleGatewayMessage() throws Exception {
        reader.doWork();

        assertEquals(0, reader.handleMessageCallCount.get());
    }

    @Test
    void doWork_EmptyBuffer_DoesNotCallHandleGatewayMessage() throws Exception {
        reader.readResults.add(ByteBuffer.allocate(0));
        reader.doWork();

        assertEquals(0, reader.handleMessageCallCount.get());
    }

    @Test
    void doWork_DataReceived_SetsRecvTimestamp() throws Exception {
        final long before = System.nanoTime();
        reader.readResults.add(ByteBuffer.wrap(new byte[] {1}));
        reader.doWork();
        final long after = System.nanoTime();

        assertTrue(reader.recvTimestamp >= before);
        assertTrue(reader.recvTimestamp <= after);
    }

    @Test
    void doWork_NoData_DoesNotUpdateRecvTimestamp() throws Exception {
        reader.doWork();
        assertEquals(0L, reader.recvTimestamp);
    }

    // ========== doWork — queue consumption ==========

    @Test
    void doWork_ConsumesContextQueue_ContextFindableByHash() throws Exception {
        final String hash = "test-order-hash";
        enqueueContext(hash, 1L, 2, 3L, 10L);

        reader.doWork();

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());
        assertNotNull(reader.testFindOrderContext(key));
    }

    @Test
    void doWork_ConsumesContextQueue_FieldsPreserved() throws Exception {
        final String hash = "order-abc";
        enqueueContext(hash, 42L, 5, 99L, 200L);

        reader.doWork();

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());
        final OrderContext ctx = reader.testFindOrderContext(key);
        assertNotNull(ctx);
        assertEquals(42L, ctx.orderId);
        assertEquals(5, ctx.exchangeId);
        assertEquals(99L, ctx.securityId);
        assertEquals(200L, ctx.originalQty);
    }

    @Test
    void doWork_ConsumesRejectQueue_PublishesExecReport() throws Exception {
        enqueueReject(77L, 2, 3L, ExecType.REJECT, OrderStatus.REJECTED);

        reader.doWork();

        waitForReports(1);
        assertEquals(1, captured.size());
        assertEquals(ExecType.REJECT, captured.get(0).decoder.execType());
        assertEquals(OrderStatus.REJECTED, captured.get(0).decoder.orderStatus());
        assertEquals(RejectReason.EXCHANGE_REJECTED, captured.get(0).decoder.rejectReason());
        assertEquals(77L, captured.get(0).decoder.orderId());
    }

    @Test
    void doWork_ContextConsumedBeforeReadSocket() throws Exception {
        final String hash = "before-socket";
        enqueueContext(hash, 1L, 2, 3L, 100L);

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());

        // Verify context is consumed in the same doWork call, before handleGatewayMessage
        reader.readResults.add(ByteBuffer.wrap(new byte[] {1}));
        reader.doWork();

        // handleGatewayMessage was called, and context should be present
        assertEquals(1, reader.handleMessageCallCount.get());
        assertNotNull(reader.testFindOrderContext(key));
    }

    // ========== connect/disconnect lifecycle ==========

    @Test
    @Timeout(5)
    void connect_CallsAttachSocket() throws Exception {
        startReaderOnThread();

        reader.connect();

        assertTrue(reader.attachSocketCalled.get());
    }

    @Test
    @Timeout(5)
    void connect_PreservesExistingContexts() throws Exception {
        final String hash = "active-order-hash";
        enqueueContext(hash, 1L, 2, 3L, 100L);
        reader.doWork(); // consume context into orderContexts map

        startReaderOnThread();
        reader.connect();
        reader.pause = false;

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());
        assertNotNull(reader.testFindOrderContext(key));
    }

    @Test
    @Timeout(5)
    void connect_DoesNotDrainPendingQueueEntries() throws Exception {
        // Enqueue a context without consuming — simulates writer posting during disconnect window
        enqueueContext("hash1", 1L, 2, 3L, 100L);

        startReaderOnThread();
        reader.connect();
        reader.pause = false;

        // After reconnect, pending context should still be consumable
        reader.doWork();
        final long key =
                TestOutboundSocketReader.testComputeKey("hash1".getBytes(StandardCharsets.UTF_8), "hash1".length());
        assertNotNull(reader.testFindOrderContext(key));
    }

    @Test
    @Timeout(5)
    void reconnect_ContextSurvives_FillCanBeMatchedAfterReconnect() throws Exception {
        final String hash = "0xdeadbeef";
        enqueueContext(hash, 42L, 2, 99L, 500L);
        reader.doWork();

        // Simulate reconnect: disconnect then connect
        startReaderOnThread();
        reader.disconnect();
        reader.connect();
        reader.pause = false;

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());
        final OrderContext ctx = reader.testFindOrderContext(key);
        assertNotNull(ctx);
        assertEquals(42L, ctx.orderId);
        assertEquals(99L, ctx.securityId);
    }

    @Test
    @Timeout(5)
    void disconnect_CallsDisconnectSocket() throws Exception {
        startReaderOnThread();
        reader.pause = false;
        waitForUnpaused();

        reader.disconnect();

        assertTrue(reader.disconnectSocketCalled.get());
    }

    // ========== Concurrency ==========

    @Test
    @Timeout(5)
    void pauseIsPausedSynchronization_WorksCorrectly() throws Exception {
        reader.pause = true;
        startReaderOnThread();

        waitForPaused();
        assertTrue(reader.isPaused);

        reader.pause = false;
        waitForUnpaused();
        assertFalse(reader.isPaused);
    }

    @Test
    @Timeout(10)
    void multipleConnectDisconnectCycles_NoExceptions() throws Exception {
        startReaderOnThread();

        for (int i = 0; i < 5; i++) {
            reader.connect();
            reader.pause = false;
            waitForUnpaused();
            reader.disconnect();
        }
    }

    // ========== computeKey ==========

    @Test
    void computeKey_SameInputProducesSameKey() {
        final byte[] bytes = "test-hash".getBytes(StandardCharsets.UTF_8);
        final long key1 = TestOutboundSocketReader.testComputeKey(bytes, bytes.length);
        final long key2 = TestOutboundSocketReader.testComputeKey(bytes, bytes.length);
        assertEquals(key1, key2);
    }

    @Test
    void computeKey_DifferentInputsProduceDifferentKeys() {
        final byte[] bytes1 = "hash-001".getBytes(StandardCharsets.UTF_8);
        final byte[] bytes2 = "hash-002".getBytes(StandardCharsets.UTF_8);
        final long key1 = TestOutboundSocketReader.testComputeKey(bytes1, bytes1.length);
        final long key2 = TestOutboundSocketReader.testComputeKey(bytes2, bytes2.length);
        assertNotEquals(key1, key2);
    }

    // ========== onSocketClose ==========

    @Test
    void onSocketClose_SetsPauseAndThrowsRuntimeException() {
        assertThrows(RuntimeException.class, () -> reader.testOnSocketClose());
        assertTrue(reader.pause);
    }

    // ========== Pool management ==========

    @Test
    void contextPoolExhaustion_ThrowsOnConsume() throws Exception {
        // Exhaust all 128 reader pool slots by enqueuing 128 contexts
        for (int i = 0; i < 128; i++) {
            enqueueContext("hash-" + i, (long) i, 2, 3L, 100L);
        }
        // Consume first 128 — all go into orderContexts map
        for (int i = 0; i < 8; i++) {
            reader.doWork(); // reads up to 16 per doWork call
        }

        // 129th enqueue: pool should be exhausted on next consume
        enqueueContext("hash-overflow", 999L, 2, 3L, 100L);
        assertThrows(RuntimeException.class, () -> reader.doWork());
    }

    @Test
    void releaseOrderContext_ReturnsToPool() throws Exception {
        // Exhaust pool
        for (int i = 0; i < 128; i++) {
            enqueueContext("hash-" + i, (long) i, 2, 3L, 100L);
        }
        for (int i = 0; i < 8; i++) {
            reader.doWork();
        }

        // Release one context back to pool
        final byte[] firstHash = "hash-0".getBytes(StandardCharsets.UTF_8);
        final long key = TestOutboundSocketReader.testComputeKey(firstHash, firstHash.length);
        reader.testReleaseOrderContext(key);

        // Now there's one slot free — next consume should succeed
        enqueueContext("hash-new", 999L, 2, 3L, 100L);
        assertDoesNotThrow(() -> reader.doWork());
    }

    // ========== Completion queue ==========

    @Test
    void releaseOrderContext_EnqueuesCompletionWithOrderId() throws Exception {
        final String hash = "order-for-completion";
        enqueueContext(hash, 42L, 2, 3L, 100L);
        reader.doWork();

        final long key = TestOutboundSocketReader.testComputeKey(hash.getBytes(StandardCharsets.UTF_8), hash.length());
        reader.testReleaseOrderContext(key);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(42L, completions.get(0));
    }

    @Test
    void releaseOrderContext_UnknownKey_DoesNotEnqueueCompletion() {
        reader.testReleaseOrderContext(0xdeadbeefL);

        assertEquals(0, drainCompletionQueue().size());
    }

    // ========== Helpers ==========

    private List<Long> drainCompletionQueue() {
        final List<Long> result = new ArrayList<>();
        completionQueue.read(ctx -> result.add(ctx.orderId), Integer.MAX_VALUE);
        return result;
    }

    private void enqueueContext(
            final String hash,
            final long orderId,
            final int exchangeId,
            final long securityId,
            final long originalQty) {
        final int idx = contextQueue.tryClaim();
        assertTrue(idx >= 0, "Context queue full");
        final OrderContext ctx = contextQueue.indexAt(idx);
        ctx.reset();
        ctx.orderId = orderId;
        ctx.exchangeId = exchangeId;
        ctx.securityId = securityId;
        ctx.originalQty = originalQty;
        ctx.leavesQty = originalQty;
        final byte[] hashBytes = hash.getBytes(StandardCharsets.UTF_8);
        ctx.exchangeOrderIdLength = Math.min(hashBytes.length, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        System.arraycopy(hashBytes, 0, ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
        contextQueue.commit(idx);
    }

    private void enqueueReject(
            final long orderId,
            final int exchangeId,
            final long securityId,
            final ExecType execType,
            final OrderStatus orderStatus) {
        final int idx = rejectQueue.tryClaim();
        assertTrue(idx >= 0, "Reject queue full");
        final OrderContext ctx = rejectQueue.indexAt(idx);
        ctx.reset();
        ctx.orderId = orderId;
        ctx.exchangeId = exchangeId;
        ctx.securityId = securityId;
        ctx.execType = execType;
        ctx.orderStatus = orderStatus;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        rejectQueue.commit(idx);
    }

    private void startReaderOnThread() {
        reader.pause = true;
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(reader, null));
        waitForPaused();
    }

    private void waitForPaused() {
        final long deadline = System.currentTimeMillis() + 5000;
        while (!reader.isPaused && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(reader.isPaused, "Reader did not enter paused state");
    }

    private void waitForUnpaused() {
        final long deadline = System.currentTimeMillis() + 5000;
        while (reader.isPaused && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private void waitForReports(final int count) {
        final long deadline = System.currentTimeMillis() + 2000;
        while (captured.size() < count && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    // ========== Test subclass ==========

    static class TestOutboundSocketReader extends OutboundSocketReader {

        final Deque<ByteBuffer> readResults = new ArrayDeque<>();
        final AtomicInteger readSocketCallCount = new AtomicInteger(0);
        final AtomicInteger handleMessageCallCount = new AtomicInteger(0);
        final AtomicBoolean attachSocketCalled = new AtomicBoolean(false);
        final AtomicBoolean disconnectSocketCalled = new AtomicBoolean(false);

        TestOutboundSocketReader(
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
            readSocketCallCount.incrementAndGet();
            return readResults.isEmpty() ? null : readResults.removeFirst();
        }

        @Override
        protected void handleGatewayMessage(final ByteBuffer buffer) {
            handleMessageCallCount.incrementAndGet();
        }

        @Override
        protected void attachSocket() throws IOException {
            attachSocketCalled.set(true);
        }

        @Override
        protected void disconnectSocket() throws Exception {
            disconnectSocketCalled.set(true);
        }

        @Override
        protected void subscribe() throws IOException {}

        @Override
        public void keepAlive() throws IOException {}

        OrderContext testFindOrderContext(final long key) {
            return findOrderContext(key);
        }

        void testReleaseOrderContext(final long key) {
            releaseOrderContext(key);
        }

        void testOnSocketClose() {
            onSocketClose();
        }

        static long testComputeKey(final byte[] bytes, final int length) {
            return computeKey(bytes, length);
        }
    }
}
