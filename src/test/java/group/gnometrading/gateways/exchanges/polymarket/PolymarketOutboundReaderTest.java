package group.gnometrading.gateways.exchanges.polymarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOutboundReader;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketOutboundReaderTest {

    private static final String ORDER_HASH = "0xdeadbeef1234567890abcdef";
    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final long ORIG_QTY = qty("10.0");

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private ManyToOneRingBuffer<OrderContext> completionQueue;
    private WebSocketClient client;
    private WebSocketResponse response;
    private PolymarketOutboundReader reader;
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
                new Exchange(2, "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                "condition-1:token-yes",
                "TEST-YES");

        reader = new PolymarketOutboundReader(
                new NullLogger(),
                execReportBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                "test-key",
                "test-secret",
                "test-passphrase");
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    @Test
    void orderPlacementLiveEmitsNewExecReport() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 1, 1, 2, 3L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_HASH, "1700000000000"));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(OrderStatus.NEW, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(ORIG_QTY, report.decoder.leavesQty());
    }

    @Test
    void orderCancellationEmitsCancelExecReport() throws Exception {
        final long cumFilled = qty("3.0");
        enqueueContext(ORDER_HASH, ORIG_QTY, cumFilled, 1, 2, 3L);
        process(orderEvent("CANCELLATION", "CANCELED", ORDER_HASH, "1700000000000"));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(OrderStatus.CANCELED, report.decoder.orderStatus());
        assertEquals(cumFilled, report.decoder.cumulativeQty());
        assertEquals(0, report.decoder.leavesQty());
    }

    @Test
    void tradeMatchedEmitsFillReport() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0, 1, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.50", "10.0", "0", "1700000000000"));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(OrderStatus.FILLED, report.decoder.orderStatus());
        assertEquals(qty("10.0"), report.decoder.filledQty());
        assertEquals(price("0.50"), report.decoder.fillPrice());
    }

    @Test
    void tradeMatchedPartialFillSetsCorrectStatus() throws Exception {
        enqueueContext(ORDER_HASH, qty("20.0"), 0, 1, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.60", "5.0", "0", "1700000000000"));
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
    void tradeFailedEmitsCancelReport() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0, 1, 2, 3L);
        process(tradeEvent("FAILED", ORDER_HASH, "0.0", "0.0", "0", "1700000000000"));
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(OrderStatus.CANCELED, report.decoder.orderStatus());
    }

    @Test
    void tradeMinedIsNoOp() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0, 1, 2, 3L);
        process(tradeEvent("MINED", ORDER_HASH, "0.0", "0.0", "0", "1700000000000"));

        assertEquals(0, captured.size());
    }

    @Test
    void unknownOrderHashIsIgnored() throws Exception {
        // No order context in queue — reader should silently skip
        process(orderEvent("PLACEMENT", "LIVE", "0xunknownhash", "1700000000000"));
        assertEquals(0, captured.size());
    }

    @Test
    void pongMessageIsIgnored() throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap("PONG".getBytes(StandardCharsets.US_ASCII)));
        reader.doWork();
        assertEquals(0, captured.size());
    }

    @Test
    void rejectQueuePublishesExecReport() throws Exception {
        enqueueReject(ORDER_HASH, ExecType.REJECT, OrderStatus.REJECTED);
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(new byte[0]));
        reader.doWork();
        waitForReports(1);

        assertEquals(1, captured.size());
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(OrderStatus.REJECTED, report.decoder.orderStatus());
    }

    @Test
    void execReportTimestampsArePopulated() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0, 1, 2, 3L);
        final long eventMs = 1700000000000L;
        process(orderEvent("PLACEMENT", "LIVE", ORDER_HASH, Long.toString(eventMs)));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(eventMs * 1_000_000L, report.decoder.timestampEvent());
        assertEquals(FIXED_NANO, report.decoder.timestampRecv());
    }

    @Test
    void execReportHeaderFieldsMatchContext() throws Exception {
        final int exchangeId = 2;
        final long securityId = 3L;
        final long orderId = 42L;
        enqueueContextWithOrderId(ORDER_HASH, ORIG_QTY, 0, orderId, exchangeId, securityId);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_HASH, "1700000000000"));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(exchangeId, report.decoder.exchangeId());
        assertEquals(securityId, report.decoder.securityId());
        assertEquals(orderId, report.decoder.orderId());
    }

    @Test
    void multiplePartialFills_CumulativeQtyAccumulates() throws Exception {
        enqueueContext(ORDER_HASH, qty("20.0"), 0, 1, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.60", "8.0", "0", "1700000000000"));
        waitForReports(1);

        process(tradeEvent("MATCHED", ORDER_HASH, "0.55", "7.0", "0", "1700000000001"));
        waitForReports(2);

        assertEquals(2, captured.size());
        final OrderExecutionReport second = captured.get(1);
        assertEquals(ExecType.PARTIAL_FILL, second.decoder.execType());
        assertEquals(qty("15.0"), second.decoder.cumulativeQty());
        assertEquals(qty("5.0"), second.decoder.leavesQty());
        assertEquals(qty("7.0"), second.decoder.filledQty());
    }

    @Test
    void fullFill_ReleasesContext_SubsequentMessageIgnored() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0, 1, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.50", "10.0", "0", "1700000000000"));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());

        // Context released — a second message for the same hash must be silently ignored
        process(tradeEvent("MATCHED", ORDER_HASH, "0.50", "10.0", "0", "1700000000001"));
        assertEquals(1, captured.size());
    }

    // --- completion queue ---

    @Test
    void fullFill_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_HASH, ORIG_QTY, 0L, 42L, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.60", "10.0", "0", "1700000000000"));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(42L, completions.get(0));
    }

    @Test
    void cancelEvent_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_HASH, ORIG_QTY, 0L, 55L, 2, 3L);
        process(orderEvent("CANCELLATION", "CANCELED", ORDER_HASH, "1700000000000"));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(55L, completions.get(0));
    }

    @Test
    void partialFill_DoesNotEnqueueCompletion() throws Exception {
        enqueueContext(ORDER_HASH, ORIG_QTY, 0L, 1, 2, 3L);
        process(tradeEvent("MATCHED", ORDER_HASH, "0.60", "5.0", "0", "1700000000000"));
        waitForReports(1);

        assertEquals(0, drainCompletionQueue().size());
    }

    @Test
    void tradeFailed_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_HASH, ORIG_QTY, 0L, 77L, 2, 3L);
        process(tradeEvent("FAILED", ORDER_HASH, "0.0", "0.0", "0", "1700000000000"));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(77L, completions.get(0));
    }

    // --- helpers ---

    private List<Long> drainCompletionQueue() {
        final List<Long> result = new java.util.ArrayList<>();
        completionQueue.read(ctx -> result.add(ctx.clientOidCounter), Integer.MAX_VALUE);
        return result;
    }

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

    private void enqueueContext(
            final String hash,
            final long originalQty,
            final long cumulativeFilledQty,
            final int clientOidStrategyId,
            final int exchangeId,
            final long securityId) {
        enqueueContextWithOrderId(hash, originalQty, cumulativeFilledQty, 1L, exchangeId, securityId);
    }

    private void enqueueContextWithOrderId(
            final String hash,
            final long originalQty,
            final long cumulativeFilledQty,
            final long orderId,
            final int exchangeId,
            final long securityId) {
        final int idx = contextQueue.tryClaim();
        final OrderContext ctx = contextQueue.indexAt(idx);
        ctx.reset();
        ctx.orderId = orderId;
        ctx.clientOidCounter = orderId;
        ctx.exchangeId = exchangeId;
        ctx.securityId = securityId;
        ctx.originalQty = originalQty;
        ctx.cumulativeFilledQty = cumulativeFilledQty;
        ctx.leavesQty = originalQty - cumulativeFilledQty;
        final byte[] hashBytes = hash.getBytes(StandardCharsets.UTF_8);
        ctx.exchangeOrderIdLength = Math.min(hashBytes.length, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        System.arraycopy(hashBytes, 0, ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
        contextQueue.commit(idx);
    }

    private void enqueueReject(final String hash, final ExecType execType, final OrderStatus orderStatus) {
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

    private static String orderEvent(
            final String type, final String status, final String hash, final String timestamp) {
        return "{\"event_type\":\"order\",\"type\":\"" + type
                + "\",\"status\":\"" + status
                + "\",\"order_hash\":\"" + hash
                + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    private static String tradeEvent(
            final String status,
            final String hash,
            final String price,
            final String size,
            final String feeRateBps,
            final String timestamp) {
        return "{\"event_type\":\"trade\",\"status\":\"" + status
                + "\",\"hash\":\"" + hash
                + "\",\"price\":\"" + price
                + "\",\"size\":\"" + size
                + "\",\"fee_rate_bps\":\"" + feeRateBps
                + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }
}
