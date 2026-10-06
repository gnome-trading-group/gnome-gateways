package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OutboundSocketWriter.SubmitResult;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundSocketWriterTest {

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private TestOutboundSocketWriter writer;

    @BeforeEach
    void setUp() {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 512);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 512);
        releasedOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writer = new TestOutboundSocketWriter(orderBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
    }

    // ========== Submit path ==========

    @Test
    void submitSuccess_EnqueuesContextWithCorrectFields() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);

        writer.doWork();

        final List<OrderContext> contexts = drainQueue(newOrderQueue);
        assertEquals(1, contexts.size());
        final OrderContext ctx = contexts.get(0);
        assertEquals(7L, ctx.clientOidCounter);
        assertEquals(1, ctx.clientOidStrategyId);
        assertEquals(2, ctx.exchangeId);
        assertEquals(3L, ctx.securityId);
        assertEquals(qty("10.0"), ctx.originalQty);
        assertEquals(qty("10.0"), ctx.leavesQty);
        assertEquals(0L, ctx.cumulativeFilledQty);
        assertEquals(Side.Bid, ctx.side);
        assertEquals("hash-7", exchangeOrderId(writer.submittedContexts.get(0)));
    }

    @Test
    void doWork_NothingPending_ReportsNoWork() throws Exception {
        assertEquals(0, writer.doWork());
    }

    @Test
    void doWork_PolledOrderAndReleasedContext_CountAsWork() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        assertEquals(1, writer.doWork());

        enqueueCompletion(releasedOrderQueue, 7L);
        assertEquals(1, writer.doWork());
        assertEquals(0, writer.doWork());
    }

    @Test
    void submit_RegistersWithTheReaderBeforeSending() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        final OrderContext registered = drainQueue(newOrderQueue).get(0);
        assertEquals("corr-1", correlationId(registered));
        assertEquals(0, registered.exchangeOrderIdLength, "registered before the venue answered");
    }

    @Test
    void submitRejected_RegisteredThenRejectedWithItsCorrelationId() throws Exception {
        writer.submitResult = false;
        publishOrder(
                Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        assertEquals(1, drainQueue(newOrderQueue).size());
        final List<OrderContext> rejects = drainQueue(writerReportQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, rejects.get(0).rejectReason);
        assertEquals("corr-1", correlationId(rejects.get(0)), "the reader drops the registration by this id");
        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void submitRejected_ReturnsContextToPool_SubsequentSubmitSucceeds() throws Exception {
        writer.submitResult = false;
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        drainQueue(writerReportQueue);
        drainQueue(newOrderQueue);

        writer.submitResult = true;
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();

        assertEquals(1, drainQueue(newOrderQueue).size());
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void prepareRefused_RejectsWithoutRegisteringOrSending() throws Exception {
        writer.prepareResult = false;
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        assertEquals(0, drainQueue(newOrderQueue).size());
        final OrderContext reject = drainQueue(writerReportQueue).get(0);
        assertEquals(ExecType.REJECT, reject.execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, reject.rejectReason);
        assertEquals(0, writer.submitCallCount);
    }

    // ========== Submits with no clear answer ==========

    @Test
    void unknownSubmit_ResendAccepted_IsAccepted() throws Exception {
        writer.submitScript.add(SubmitResult.UNKNOWN);
        writer.submitScript.add(SubmitResult.ACCEPTED);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        assertEquals(2, writer.submitCallCount);
        assertEquals(0, writer.findCallCount);
        assertEquals(0, drainQueue(writerReportQueue).size());
        assertEquals(1, writer.activeOrderCount());
    }

    @Test
    void unknownSubmit_ResendRefusedButVenueHasIt_IsAcceptedWithItsVenueId() throws Exception {
        writer.submitScript.add(SubmitResult.UNKNOWN);
        writer.submitScript.add(SubmitResult.REJECTED); // e.g. "already exists"
        writer.findScript.add(SubmitResult.ACCEPTED);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);

        writer.doWork();
        publishCancel(7L, 2, 3L);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size());
        assertEquals("hash-7", exchangeOrderId(writer.cancelledContexts.get(0)), "cancels route by the found id");
    }

    @Test
    void unknownSubmit_ResendRefusedAndVenueLacksIt_IsRejected() throws Exception {
        writer.submitScript.add(SubmitResult.UNKNOWN);
        writer.submitScript.add(SubmitResult.REJECTED);
        writer.findScript.add(SubmitResult.REJECTED);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        assertEquals(ExecType.REJECT, drainQueue(writerReportQueue).get(0).execType);
        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void networkErrorOnSubmit_IsTreatedAsUnknownNotFatal() throws Exception {
        writer.submitScript.add(new IOException("connection reset"));
        writer.submitScript.add(SubmitResult.ACCEPTED);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        assertDoesNotThrow(() -> writer.doWork());

        assertEquals(1, writer.activeOrderCount());
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void neverAnswered_StaysRegisteredWithoutAReport_AfterBoundedRetries() throws Exception {
        for (int i = 0; i < 10; i++) {
            writer.submitScript.add(SubmitResult.UNKNOWN);
        }
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        writer.doWork();

        assertEquals(3, writer.submitCallCount, "the first send plus two resends");
        assertEquals(1, drainQueue(newOrderQueue).size());
        assertEquals(0, drainQueue(writerReportQueue).size(), "it may be live, so it is not reported rejected");
        assertEquals(1, writer.activeOrderCount());
    }

    // ========== Cancel path ==========

    @Test
    void cancelExistingOrder_Success_CallsCancelAndRemovesFromActiveOrders() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();

        assertEquals(1, writer.cancelCallCount);
        assertEquals(0, drainQueue(writerReportQueue).size());

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
        final OrderContext submitted = drainQueue(newOrderQueue).get(0);

        writer.cancelResult = false;
        publishCancel(submitted.clientOidCounter, 2, 3L);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(writerReportQueue);
        assertEquals(1, rejects.size());
        final OrderContext reject = rejects.get(0);
        assertEquals(ExecType.CANCEL_REJECT, reject.execType);
        assertEquals(OrderStatus.CANCELED, reject.orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, reject.rejectReason);
        assertEquals(submitted.clientOidCounter, reject.clientOidCounter);
        assertEquals(submitted.exchangeId, reject.exchangeId);
        assertEquals(submitted.securityId, reject.securityId);
    }

    @Test
    void cancelUnknownOrderId_IsNoOp() throws Exception {
        publishCancel(999L, 2, 3L);
        writer.doWork();

        assertEquals(0, writer.cancelCallCount);
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    // ========== Modify path ==========

    @Test
    void modifyUnknownOrderId_IsNoOp() throws Exception {
        publishModify(999L, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        assertEquals(0, writer.cancelCallCount);
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void modifyOnVenueWithoutAmend_IsRefused_OrderStaysWorking() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final OrderContext original = drainQueue(newOrderQueue).get(0);

        publishModify(original.clientOidCounter, price("0.60"), qty("5.0"), 2, 3L);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(0).rejectReason, "refused here, not by the venue");
        assertEquals(original.clientOidCounter, reports.get(0).clientOidCounter);
        assertEquals(0, writer.cancelCallCount, "refusing a modify must not touch the venue");
        assertEquals(0, drainQueue(newOrderQueue).size());

        publishCancel(original.clientOidCounter, 2, 3L);
        writer.doWork();
        assertEquals(1, writer.cancelCallCount, "the order is still working, so a cancel is routed");
    }

    @Test
    void refusalsNeedNoPoolSlot() throws Exception {
        for (int i = 0; i < OrderContext.MAX_IN_FLIGHT_ORDERS; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            writer.doWork();
            drainQueue(newOrderQueue);
        }

        publishModify(1L, price("0.60"), qty("5.0"), 2, 3L);
        assertDoesNotThrow(() -> writer.doWork());
        writer.cancelResult = false;
        publishCancel(1L, 2, 3L);
        assertDoesNotThrow(() -> writer.doWork());

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(2, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(ExecType.CANCEL_REJECT, reports.get(1).execType);
    }

    // ========== Pool management ==========

    @Test
    void poolExhaustion_RejectsWithoutSending() throws Exception {
        for (int i = 0; i < 256; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, i, 1);
            writer.doWork();
            drainQueue(newOrderQueue);
        }
        final int sent = writer.submitCallCount;

        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 999L, 1);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
        assertEquals(999L, reports.get(0).clientOidCounter);
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(0).rejectReason);
        assertEquals(sent, writer.submitCallCount, "an order the gateway can't track is never sent");
    }

    @Test
    void poolRecycledAfterCancel_CanBeReused() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        publishCancel(clientOidCounter, 2, 3L);
        writer.doWork();

        // Should succeed — pool slot was returned on cancel
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        assertEquals(1, drainQueue(newOrderQueue).size());
    }

    @Test
    void newOrderQueueOverflow_RejectsWithoutSending() throws Exception {
        final ManyToOneRingBuffer<OrderContext> smallContextQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2);
        final TestOutboundSocketWriter smallWriter =
                new TestOutboundSocketWriter(orderBuffer, smallContextQueue, writerReportQueue, releasedOrderQueue);

        for (int i = 0; i < 2; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, i, 1);
            smallWriter.doWork();
        }

        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        smallWriter.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
        assertEquals(7L, reports.get(0).clientOidCounter);
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(0).rejectReason);
        assertEquals(2, smallWriter.submitCallCount, "an order the reader doesn't know is never sent");
    }

    @Test
    void reportQueueFull_WaitsForTheReaderToDrainIt() throws Exception {
        final ManyToOneRingBuffer<OrderContext> smallReportQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2);
        final TestOutboundSocketWriter failWriter =
                new TestOutboundSocketWriter(orderBuffer, newOrderQueue, smallReportQueue, releasedOrderQueue);
        failWriter.submitResult = false;

        for (int i = 0; i < 2; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, i, 1);
            failWriter.doWork();
        }
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 2L, 1);

        final Thread writerThread = new Thread(() -> {
            try {
                failWriter.doWork();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writerThread.start();
        writerThread.join(200);
        assertTrue(writerThread.isAlive(), "the third reject waits for room");

        final List<OrderContext> reports = new ArrayList<>(drainQueue(smallReportQueue));
        writerThread.join(5_000);
        assertFalse(writerThread.isAlive());
        reports.addAll(drainQueue(smallReportQueue));
        assertEquals(
                List.of(0L, 1L, 2L),
                reports.stream().map(r -> r.clientOidCounter).toList());
    }

    // ========== Failures inside a message ==========

    @Test
    void prepareThrows_RejectsOnceAndIsNotRetried() throws Exception {
        writer.prepareScript.add(new IllegalStateException("signer broke"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        assertThrows(IllegalStateException.class, () -> writer.doWork(), "the bug still reaches the error handler");
        writer.doWork();

        assertEquals(1, writer.prepareCallCount, "the message is settled, not delivered again");
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(0).rejectReason);
        assertEquals(0, drainQueue(newOrderQueue).size(), "never registered, so never sent");
        assertEquals(0, writer.submitCallCount);
    }

    @Test
    void prepareThrows_ReturnsItsPoolSlot() throws Exception {
        writer.prepareScript.add(new IllegalStateException("signer broke"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertThrows(IllegalStateException.class, () -> writer.doWork());
        drainQueue(writerReportQueue);

        for (int i = 0; i < OrderContext.MAX_IN_FLIGHT_ORDERS; i++) {
            publishOrder(
                    Side.Bid,
                    price("0.50"),
                    qty("1.0"),
                    OrderType.LIMIT,
                    TimeInForce.GOOD_TILL_CANCELED,
                    2,
                    3L,
                    100L + i,
                    1);
            writer.doWork();
            drainQueue(newOrderQueue);
        }

        assertEquals(0, drainQueue(writerReportQueue).size(), "every slot is still available");
    }

    @Test
    void submitThrowsUnexpectedly_StaysRegisteredAndIsNotResent() throws Exception {
        writer.submitScript.add(new IllegalStateException("venue assigned a different id"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        assertThrows(IllegalStateException.class, () -> writer.doWork());
        writer.doWork();

        assertEquals(1, writer.submitCallCount, "a resend could place a second live order");
        assertEquals(1, writer.prepareCallCount);
        assertEquals(1, drainQueue(newOrderQueue).size());
        assertEquals(0, drainQueue(writerReportQueue).size(), "it may be live, so it is not reported rejected");
        assertEquals(1, writer.activeOrderCount());
    }

    @Test
    void findThrowsUnexpectedly_StaysRegisteredAndIsNotRetried() throws Exception {
        writer.submitScript.add(SubmitResult.UNKNOWN);
        writer.submitScript.add(SubmitResult.REJECTED);
        writer.findScript.add(new IllegalStateException("unreadable lookup"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);

        assertThrows(IllegalStateException.class, () -> writer.doWork());
        writer.doWork();

        assertEquals(2, writer.submitCallCount);
        assertEquals(1, writer.findCallCount);
        assertEquals(0, drainQueue(writerReportQueue).size());
        assertEquals(1, writer.activeOrderCount());
    }

    @Test
    void cancelNetworkError_IsCancelRejectedWithoutReachingTheErrorHandler() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        writer.doWork();
        writer.cancelScript.add(new IOException("connection reset"));

        publishCancel(7L, 2, 3L);
        assertDoesNotThrow(() -> writer.doWork());
        writer.doWork();

        assertEquals(1, writer.cancelCallCount, "not retried behind the OMS's back");
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.UNKNOWN, reports.get(0).rejectReason, "the venue never answered");
        assertEquals(1, writer.activeOrderCount(), "the order may still be working");

        publishCancel(7L, 2, 3L);
        writer.doWork();
        assertEquals(2, writer.cancelCallCount, "a later cancel is still routed");
    }

    @Test
    void cancelThrowsUnexpectedly_IsCancelRejectedAndNotRetried() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        writer.doWork();
        writer.cancelScript.add(new IllegalStateException("bad response"));

        publishCancel(7L, 2, 3L);
        assertThrows(IllegalStateException.class, () -> writer.doWork());
        writer.doWork();

        assertEquals(1, writer.cancelCallCount);
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(1, writer.activeOrderCount());
    }

    @Test
    void modifyNetworkError_IsCancelRejectedWithoutReachingTheErrorHandler() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 7L, 1);
        writer.doWork();
        writer.modifyException = new IOException("connection reset");

        publishModify(7L, price("0.60"), qty("5.0"), 2, 3L);
        assertDoesNotThrow(() -> writer.doWork());
        writer.doWork();

        assertEquals(1, writer.modifyCallCount);
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.UNKNOWN, reports.get(0).rejectReason);
        assertEquals(7L, reports.get(0).clientOidCounter);
    }

    @Test
    void failedMessage_DoesNotBlockTheOnesBehindIt() throws Exception {
        writer.prepareScript.add(new IllegalStateException("signer broke"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 2L, 1);

        assertThrows(IllegalStateException.class, () -> writer.doWork());

        assertEquals(1, writer.submitCallCount);
        assertEquals(2L, drainQueue(newOrderQueue).get(0).clientOidCounter);
    }

    @Test
    void severalFailuresInOnePoll_AllReachTheErrorHandler() throws Exception {
        writer.prepareScript.add(new IllegalStateException("first"));
        writer.prepareScript.add(new IllegalStateException("second"));
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 2L, 1);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> writer.doWork());

        assertEquals("first", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("second", thrown.getSuppressed()[0].getMessage());
        assertEquals(0, writer.doWork());
    }

    @Test
    void reportQueueFull_InterruptStopsTheWait() throws Exception {
        final ManyToOneRingBuffer<OrderContext> smallReportQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2);
        final TestOutboundSocketWriter failWriter =
                new TestOutboundSocketWriter(orderBuffer, newOrderQueue, smallReportQueue, releasedOrderQueue);
        failWriter.submitResult = false;
        for (int i = 0; i < 3; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, i, 1);
        }

        final Thread writerThread = new Thread(() -> {
            try {
                failWriter.doWork();
            } catch (final Exception e) { // the interrupt surfaces as a failure; only the exit matters here
            }
        });
        writerThread.start();
        writerThread.join(200);
        assertTrue(writerThread.isAlive(), "the third reject waits for room");

        writerThread.interrupt();
        writerThread.join(5_000);
        assertFalse(writerThread.isAlive(), "shutdown is not held up by a stalled reader");
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

        final List<OrderContext> contexts = drainQueue(newOrderQueue);
        assertEquals(1, contexts.size());
        assertEquals((short) 1, contexts.get(0).flags);
    }

    @Test
    void completionQueue_DrainedBeforeOrderPoll_PoolSlotReclaimed() throws Exception {
        // Fill all 256 pool slots with active orders
        for (int i = 0; i < 256; i++) {
            publishOrder(
                    Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
            writer.doWork();
            drainQueue(newOrderQueue);
        }

        // Enqueue a completion for clientOidCounter=1 (pool full, would throw without reclaim)
        enqueueCompletion(releasedOrderQueue, 1L);

        // Publish a new order — this doWork() should drain completion first, then process the order
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        assertDoesNotThrow(() -> writer.doWork());
        assertEquals(1, drainQueue(newOrderQueue).size());
    }

    @Test
    void releasedOrders_AllDrainedInOneLoop_NotSixteenAtATime() throws Exception {
        // A writer that falls behind the reader must catch up in one pass, or completions pile up.
        final int orders = 40;
        for (long oid = 1; oid <= orders; oid++) {
            publishOrder(
                    Side.Bid,
                    price("0.50"),
                    qty("1.0"),
                    OrderType.LIMIT,
                    TimeInForce.GOOD_TILL_CANCELED,
                    2,
                    3L,
                    oid,
                    1);
            writer.doWork();
        }
        drainQueue(newOrderQueue);
        assertEquals(orders, writer.activeOrderCount());

        for (long oid = 1; oid <= orders; oid++) {
            enqueueCompletion(releasedOrderQueue, oid);
        }
        writer.doWork();

        assertEquals(0, writer.activeOrderCount());
    }

    @Test
    void completionQueue_UnknownOrderId_IsNoOp() throws Exception {
        enqueueCompletion(releasedOrderQueue, 9999L);
        assertDoesNotThrow(() -> writer.doWork());
    }

    @Test
    void completionQueue_DuplicateCompletion_IsIdempotent() throws Exception {
        publishOrder(
                Side.Bid, price("0.50"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, 2, 3L, 1L, 1);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        enqueueCompletion(releasedOrderQueue, clientOidCounter);
        enqueueCompletion(releasedOrderQueue, clientOidCounter);

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

    private static String correlationId(final OrderContext ctx) {
        return new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.UTF_8);
    }

    private static String exchangeOrderId(final OrderContext ctx) {
        return new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.UTF_8);
    }

    static class TestOutboundSocketWriter extends OutboundSocketWriter {

        boolean prepareResult = true;
        boolean submitResult = true;
        boolean cancelResult = true;
        Exception modifyException;
        // Scripted answers, consumed in order before falling back to the defaults. An exception is thrown.
        final Deque<Exception> prepareScript = new ArrayDeque<>();
        final Deque<Object> submitScript = new ArrayDeque<>();
        final Deque<Object> findScript = new ArrayDeque<>();
        final Deque<Exception> cancelScript = new ArrayDeque<>();
        int prepareCallCount = 0;
        int submitCallCount = 0;
        int findCallCount = 0;
        int cancelCallCount = 0;
        int modifyCallCount = 0;
        final List<OrderContext> submittedContexts = new ArrayList<>();
        final List<OrderContext> cancelledContexts = new ArrayList<>();

        TestOutboundSocketWriter(
                SequencedRingBuffer<Order> orderBuffer,
                ManyToOneRingBuffer<OrderContext> newOrderQueue,
                ManyToOneRingBuffer<OrderContext> writerReportQueue,
                ManyToOneRingBuffer<OrderContext> releasedOrderQueue) {
            super(orderBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        }

        @Override
        protected boolean prepareOrder(final OrderContext ctx) throws Exception {
            prepareCallCount++;
            final Exception scripted = prepareScript.poll();
            if (scripted != null) {
                throw scripted;
            }
            final byte[] correlation = ("corr-" + ctx.clientOidCounter).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(correlation, 0, ctx.correlationIdBytes, 0, correlation.length);
            ctx.correlationIdLength = correlation.length;
            return prepareResult;
        }

        @Override
        protected SubmitResult submitOrder(final OrderContext ctx) throws Exception {
            submitCallCount++;
            final Object scripted = submitScript.poll();
            if (scripted instanceof Exception e) {
                throw e;
            }
            final SubmitResult result = scripted != null
                    ? (SubmitResult) scripted
                    : submitResult ? SubmitResult.ACCEPTED : SubmitResult.REJECTED;
            if (result == SubmitResult.ACCEPTED) {
                setVenueId(ctx);
            }
            final OrderContext copy = new OrderContext();
            copy.copyFrom(ctx);
            submittedContexts.add(copy);
            return result;
        }

        @Override
        protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
            findCallCount++;
            final Object scripted = findScript.poll();
            if (scripted instanceof Exception e) {
                throw e;
            }
            final SubmitResult result = scripted == null ? SubmitResult.REJECTED : (SubmitResult) scripted;
            if (result == SubmitResult.ACCEPTED) {
                setVenueId(ctx);
            }
            return result;
        }

        @Override
        protected boolean cancelOrder(final OrderContext ctx) throws Exception {
            cancelCallCount++;
            final OrderContext copy = new OrderContext();
            copy.copyFrom(ctx);
            cancelledContexts.add(copy);
            final Exception scripted = cancelScript.poll();
            if (scripted != null) {
                throw scripted;
            }
            return cancelResult;
        }

        @Override
        protected void handleModifyOrder() throws Exception {
            modifyCallCount++;
            if (modifyException != null) {
                throw modifyException;
            }
            super.handleModifyOrder();
        }

        private static void setVenueId(final OrderContext ctx) {
            final byte[] hash = ("hash-" + ctx.clientOidCounter).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(hash, 0, ctx.exchangeOrderIdBytes, 0, hash.length);
            ctx.exchangeOrderIdLength = hash.length;
        }
    }
}
