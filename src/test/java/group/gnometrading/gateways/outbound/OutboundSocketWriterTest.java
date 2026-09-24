package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundSocketWriterTest {

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private ManyToOneRingBuffer<OrderContext> completionQueue;
    private TestOutboundSocketWriter writer;

    @BeforeEach
    void setUp() {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        contextQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 512);
        rejectQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 512);
        completionQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writer = new TestOutboundSocketWriter(orderBuffer, contextQueue, rejectQueue, completionQueue);
    }

    // ========== Submit path ==========

    @Test
    void submitSuccess_EnqueuesContextWithCorrectFields() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);

        writer.doWork();

        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
        final OrderContext ctx = contexts.get(0);
        assertEquals(1L, ctx.orderId);
        assertEquals(7L, ctx.clientOidCounter);
        assertEquals(1, ctx.clientOidStrategyId);
        assertEquals(2, ctx.exchangeId);
        assertEquals(3L, ctx.securityId);
        assertEquals(qty("10.0"), ctx.originalQty);
        assertEquals(qty("10.0"), ctx.leavesQty);
        assertEquals(0L, ctx.cumulativeFilledQty);
        assertEquals(Side.Bid, ctx.side);
        assertTrue(ctx.exchangeOrderIdLength > 0);
    }

    @Test
    void submitSuccess_AssignsIncrementingOrderIds() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();
        writer.doWork();
        writer.doWork();

        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(3, contexts.size());
        assertEquals(1L, contexts.get(0).orderId);
        assertEquals(2L, contexts.get(1).orderId);
        assertEquals(3L, contexts.get(2).orderId);
    }

    @Test
    void submitFailure_EnqueuesReject_NothingInContextQueue() throws Exception {
        writer.submitResult = false;
        publishOrder(
                Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, rejects.get(0).rejectReason);
        assertEquals(0, drainQueue(contextQueue).size());
    }

    @Test
    void submitFailure_ReturnsContextToPool_SubsequentSubmitSucceeds() throws Exception {
        writer.submitResult = false;
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        drainQueue(rejectQueue);

        writer.submitResult = true;
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();

        assertEquals(1, drainQueue(contextQueue).size());
    }

    // ========== Cancel path ==========

    @Test
    void cancelExistingOrder_Success_CallsCancelAndRemovesFromActiveOrders() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();

        assertEquals(1, writer.cancelCallCount);
        assertEquals(0, drainQueue(rejectQueue).size());

        // Cancelled order removed from active — second cancel is a no-op
        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();
        assertEquals(1, writer.cancelCallCount);
    }

    @Test
    void cancelExistingOrder_Failure_EnqueuesCancelReject() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        writer.doWork();
        final OrderContext submitted = drainQueue(contextQueue).get(0);

        writer.cancelResult = false;
        publishCancel(submitted.clientOidCounter, 2, 3L);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        final OrderContext reject = rejects.get(0);
        assertEquals(ExecType.CANCEL_REJECT, reject.execType);
        assertEquals(OrderStatus.CANCELED, reject.orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, reject.rejectReason);
        assertEquals(submitted.orderId, reject.orderId);
        assertEquals(submitted.exchangeId, reject.exchangeId);
        assertEquals(submitted.securityId, reject.securityId);
    }

    @Test
    void cancelUnknownOrderId_IsNoOp() throws Exception {
        publishCancel(999L, 2, 3L);
        writer.doWork();

        assertEquals(0, writer.cancelCallCount);
        assertEquals(0, drainQueue(rejectQueue).size());
    }

    // ========== Modify path ==========

    @Test
    void modifyUnknownOrderId_IsNoOp() throws Exception {
        publishModify(999L, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        assertEquals(0, writer.cancelCallCount);
        assertEquals(0, writer.submitForModifyCallCount);
    }

    @Test
    void modifyCancelFails_EnqueuesCancelReject_OldCtxRemainsInActiveOrders() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        writer.cancelResult = false;
        publishModify(clientOidCounter, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.CANCEL_REJECT, rejects.get(0).execType);
        assertEquals(0, writer.submitForModifyCallCount);

        // Order still in activeOrders after failed modify cancel — a subsequent cancel is routed
        writer.cancelResult = true;
        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();
        assertEquals(2, writer.cancelCallCount); // cancel called twice total
    }

    @Test
    void modifyCancelSucceeds_SubmitFails_OldCtxRemoved_RejectEnqueued() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        writer.submitForModifyResult = false;
        publishModify(clientOidCounter, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);
        assertEquals(1, writer.submitForModifyCallCount);

        // Order removed from activeOrders after modify cancel — subsequent cancel is a no-op
        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();
        assertEquals(1, writer.cancelCallCount); // cancel not called again
    }

    @Test
    void modifyCancelSucceeds_SubmitSucceeds_NewCtxInActiveOrders_OldRemoved() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final OrderContext original = drainQueue(contextQueue).get(0);
        final long oldInternalOrderId = original.orderId;
        final long clientOidCounter = original.clientOidCounter;

        publishModify(clientOidCounter, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        // Context for replacement order enqueued; new writer-internal orderId, same clientOidCounter
        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
        assertNotEquals(oldInternalOrderId, contexts.get(0).orderId);
        assertEquals(clientOidCounter, contexts.get(0).clientOidCounter);
        assertEquals(qty("5.0"), contexts.get(0).originalQty);
        assertEquals(1, writer.cancelCallCount); // cancel for the old order

        // New order is at the same clientOidCounter key — cancel is routed
        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();
        assertEquals(2, writer.cancelCallCount);
    }

    @Test
    void modifyDoesNotLeakPoolSlots() throws Exception {
        // Fill all 256 pool slots with active orders
        for (int i = 0; i < 256; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            writer.doWork();
            drainQueue(contextQueue);
        }

        // Pool is now empty. Modify one of the orders: the old slot must be returned
        // before the new slot is claimed, so this should not throw.
        publishModify(1L, price("0.60"), qty("5.0"), 2, 3L);
        assertDoesNotThrow(() -> writer.doWork());

        // New context enqueued for the replacement order
        assertEquals(1, drainQueue(contextQueue).size());
    }

    // ========== Pool management ==========

    @Test
    void poolExhaustion_ThrowsOnExceedingCapacity() throws Exception {
        // Fill all 256 pool slots with active orders
        for (int i = 0; i < 256; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            writer.doWork();
            drainQueue(contextQueue);
        }

        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertThrows(RuntimeException.class, () -> writer.doWork());
    }

    @Test
    void poolRecycledAfterCancel_CanBeReused() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();

        // Should succeed — pool slot was returned on cancel
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        assertEquals(1, drainQueue(contextQueue).size());
    }

    @Test
    void contextQueueOverflow_ThrowsRuntimeException() throws Exception {
        final ManyToOneRingBuffer<OrderContext> smallContextQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2);
        final TestOutboundSocketWriter smallWriter =
                new TestOutboundSocketWriter(orderBuffer, smallContextQueue, rejectQueue, completionQueue);

        // Fill the context queue (capacity 2) without draining
        for (int i = 0; i < 2; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            smallWriter.doWork();
        }

        // 3rd submit should overflow context queue
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertThrows(RuntimeException.class, () -> smallWriter.doWork());
    }

    @Test
    void rejectQueueOverflow_ThrowsRuntimeException() throws Exception {
        final ManyToOneRingBuffer<OrderContext> smallRejectQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2);
        final TestOutboundSocketWriter failWriter =
                new TestOutboundSocketWriter(orderBuffer, contextQueue, smallRejectQueue, completionQueue);
        failWriter.submitResult = false;

        for (int i = 0; i < 2; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            failWriter.doWork();
        }

        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertThrows(RuntimeException.class, () -> failWriter.doWork());
    }

    // ========== Flags propagation ==========

    @Test
    void submitOrder_FlagsCopiedToContext() throws Exception {
        publishOrderWithFlags(
                Side.Bid,
                price("0.50"),
                qty("10.0"),
                OrderType.LIMIT,
                TimeInForce.GOOD_TILL_CANCELED,
                2,
                3L,
                1L,
                1,
                (short) 1);

        writer.doWork();

        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
        assertEquals((short) 1, contexts.get(0).flags);
    }

    @Test
    void modifyOrder_FlagsCopiedToNewContext() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        publishModifyWithFlags(clientOidCounter, price("0.60"), qty("5.0"), 2, 3L, (short) 1);
        writer.doWork();

        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
        assertEquals((short) 1, contexts.get(0).flags);
    }

    // ========== Completion queue ==========

    @Test
    void completionQueue_DrainedBeforeOrderPoll_PoolSlotReclaimed() throws Exception {
        // Fill all 256 pool slots with active orders
        for (int i = 0; i < 256; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            writer.doWork();
            drainQueue(contextQueue);
        }

        // Enqueue a completion for clientOidCounter=1 (pool full, would throw without reclaim)
        enqueueCompletion(completionQueue, 1L);

        // Publish a new order — this doWork() should drain completion first, then process the order
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertDoesNotThrow(() -> writer.doWork());
        assertEquals(1, drainQueue(contextQueue).size());
    }

    @Test
    void completionQueue_UnknownOrderId_IsNoOp() throws Exception {
        enqueueCompletion(completionQueue, 9999L);
        assertDoesNotThrow(() -> writer.doWork());
    }

    @Test
    void completionQueue_DuplicateCompletion_IsIdempotent() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(contextQueue).get(0).clientOidCounter;

        enqueueCompletion(completionQueue, clientOidCounter);
        enqueueCompletion(completionQueue, clientOidCounter);

        assertDoesNotThrow(() -> writer.doWork());
        assertDoesNotThrow(() -> writer.doWork());
    }

    // ========== Helpers ==========

    private static void enqueueCompletion(final ManyToOneRingBuffer<OrderContext> queue, final long clientOidCounter) {
        final int idx = queue.tryClaim();
        assertTrue(idx >= 0);
        queue.indexAt(idx).clientOidCounter = clientOidCounter;
        queue.commit(idx);
    }

    private void publishOrder(
            final Side side,
            final long price,
            final long size,
            final OrderType orderType,
            final TimeInForce tif,
            final int exchangeId,
            final long securityId,
            final long clientOidCounter,
            final int clientOidStrategyId) {
        publishOrderWithFlags(
                side,
                price,
                size,
                orderType,
                tif,
                exchangeId,
                securityId,
                clientOidCounter,
                clientOidStrategyId,
                (short) 0);
    }

    private void publishOrderWithFlags(
            final Side side,
            final long price,
            final long size,
            final OrderType orderType,
            final TimeInForce tif,
            final int exchangeId,
            final long securityId,
            final long clientOidCounter,
            final int clientOidStrategyId,
            final short flags) {
        final Order order = orderBuffer.claim();
        order.encoder.exchangeId(exchangeId);
        order.encoder.securityId(securityId);
        order.encoder.price(price);
        order.encoder.size(size);
        order.encoder.side(side);
        order.encoder.orderType(orderType);
        order.encoder.timeInForce(tif);
        order.encoder.flags().clear();
        if ((flags & 1) != 0) {
            order.encoder.flags().postOnly(true);
        }
        order.encodeClientOid(clientOidCounter, clientOidStrategyId);
        orderBuffer.publish();
    }

    private void publishCancel(final long clientOidCounter, final int exchangeId, final long securityId) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(exchangeId);
        cancel.encoder.securityId(securityId);
        cancel.encodeClientOid(clientOidCounter, 1);
        orderBuffer.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    private void publishModify(
            final long clientOidCounter,
            final long price,
            final long size,
            final int exchangeId,
            final long securityId) {
        publishModifyWithFlags(clientOidCounter, price, size, exchangeId, securityId, (short) 0);
    }

    private void publishModifyWithFlags(
            final long clientOidCounter,
            final long price,
            final long size,
            final int exchangeId,
            final long securityId,
            final short flags) {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder.price(price);
        modify.encoder.size(size);
        modify.encoder.exchangeId(exchangeId);
        modify.encoder.securityId(securityId);
        modify.encoder.orderType(OrderType.LIMIT);
        modify.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        modify.encoder.flags().clear();
        if ((flags & 1) != 0) {
            modify.encoder.flags().postOnly(true);
        }
        modify.encodeClientOid(clientOidCounter, 1);
        orderBuffer.publishRaw(modify.buffer, ModifyOrderDecoder.TEMPLATE_ID, modify.totalMessageSize());
    }

    private static List<OrderContext> drainQueue(final ManyToOneRingBuffer<OrderContext> queue) {
        final List<OrderContext> result = new ArrayList<>();
        queue.read(
                ctx -> {
                    final OrderContext copy = new OrderContext();
                    copy.copyFrom(ctx);
                    result.add(copy);
                },
                Integer.MAX_VALUE);
        return result;
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }

    // ========== Test subclass ==========

    static class TestOutboundSocketWriter extends OutboundSocketWriter {

        boolean submitResult = true;
        boolean cancelResult = true;
        boolean submitForModifyResult = true;
        int submitCallCount = 0;
        int cancelCallCount = 0;
        int submitForModifyCallCount = 0;
        final List<OrderContext> submittedContexts = new ArrayList<>();
        final List<OrderContext> cancelledContexts = new ArrayList<>();

        TestOutboundSocketWriter(
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
            final byte[] hash = ("hash-" + submitCallCount).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(hash, 0, ctx.exchangeOrderIdBytes, 0, hash.length);
            ctx.exchangeOrderIdLength = hash.length;
            final OrderContext copy = new OrderContext();
            copy.copyFrom(ctx);
            submittedContexts.add(copy);
            return true;
        }

        @Override
        protected boolean cancelOrder(final OrderContext ctx) {
            cancelCallCount++;
            final OrderContext copy = new OrderContext();
            copy.copyFrom(ctx);
            cancelledContexts.add(copy);
            return cancelResult;
        }

        @Override
        protected boolean submitForModify(final OrderContext ctx) {
            submitForModifyCallCount++;
            if (!submitForModifyResult) {
                return false;
            }
            final byte[] hash = ("modify-hash-" + submitForModifyCallCount).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(hash, 0, ctx.exchangeOrderIdBytes, 0, hash.length);
            ctx.exchangeOrderIdLength = hash.length;
            return true;
        }
    }
}
