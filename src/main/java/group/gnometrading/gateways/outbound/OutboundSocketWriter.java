package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

public abstract class OutboundSocketWriter implements GnomeAgent {

    private final SequencedPoller orderPoller;
    protected final ManyToOneRingBuffer<OrderContext> newOrderQueue;
    protected final ManyToOneRingBuffer<OrderContext> writerReportQueue;

    private final Long2ObjectHashMap<OrderContext> activeOrders;
    private final OrderContext[] writerPool;
    private int writerPoolHead;
    private long nextOrderId = 1;

    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;

    protected final Order order = new Order();
    protected final CancelOrder cancelOrder = new CancelOrder();
    protected final ModifyOrder modifyOrder = new ModifyOrder();

    protected OutboundSocketWriter(
            SequencedRingBuffer<?> orderOutboundBuffer,
            ManyToOneRingBuffer<OrderContext> newOrderQueue,
            ManyToOneRingBuffer<OrderContext> writerReportQueue,
            ManyToOneRingBuffer<OrderContext> releasedOrderQueue) {
        this.orderPoller = orderOutboundBuffer.createPoller(this::onOrder);
        this.newOrderQueue = newOrderQueue;
        this.writerReportQueue = writerReportQueue;
        this.releasedOrderQueue = releasedOrderQueue;
        this.activeOrders = new Long2ObjectHashMap<>(OrderContext.MAX_IN_FLIGHT_ORDERS, 0.6f);
        this.writerPool = new OrderContext[OrderContext.MAX_IN_FLIGHT_ORDERS];
        for (int i = 0; i < OrderContext.MAX_IN_FLIGHT_ORDERS; i++) {
            this.writerPool[i] = new OrderContext();
        }
        this.writerPoolHead = OrderContext.MAX_IN_FLIGHT_ORDERS;
    }

    @Override
    public void onStart() {}

    @Override
    public final void onClose() {
        try {
            this.orderPoller.poll();
        } catch (Exception e) { // best-effort drain on shutdown
        }
    }

    @Override
    public final int doWork() throws Exception {
        this.releasedOrderQueue.read(this::consumeReleasedOrder, OrderContext.HANDOFF_QUEUE_CAPACITY);
        return this.orderPoller.poll();
    }

    private void consumeReleasedOrder(final OrderContext src) {
        final OrderContext ctx = this.activeOrders.remove(src.clientOidCounter);
        if (ctx != null) {
            returnToPool(ctx);
        }
    }

    private void onOrder(final long globalSeq, final int templateId, final UnsafeBuffer buf, final int len)
            throws Exception {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            order.wrap(buf);
            handleSubmitOrder();
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
            cancelOrder.wrap(buf);
            handleCancelOrder();
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            modifyOrder.wrap(buf);
            handleModifyOrder();
        }
    }

    private void handleSubmitOrder() throws Exception {
        if (this.writerPoolHead <= 0) {
            throw new RuntimeException("Writer order context pool exhausted");
        }
        final OrderContext ctx = this.writerPool[--this.writerPoolHead];
        ctx.reset();
        ctx.orderId = this.nextOrderId++;
        ctx.clientOidCounter = this.order.getClientOidCounter();
        ctx.clientOidStrategyId = this.order.getClientOidStrategyId();
        ctx.exchangeId = this.order.decoder.exchangeId();
        ctx.securityId = this.order.decoder.securityId();
        ctx.originalQty = this.order.decoder.size();
        ctx.leavesQty = ctx.originalQty;
        ctx.side = this.order.decoder.side();
        ctx.flags = (short) this.order.decoder.flags().getRaw();

        if (submitOrder(ctx)) {
            this.activeOrders.put(ctx.clientOidCounter, ctx);
            enqueueNewOrder(ctx);
        } else {
            ctx.execType = ExecType.REJECT;
            ctx.orderStatus = OrderStatus.REJECTED;
            ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
            enqueueWriterReport(ctx);
            returnToPool(ctx);
        }
    }

    private void handleCancelOrder() throws Exception {
        final long clientOidCounter = this.cancelOrder.getClientOidCounter();
        final OrderContext ctx = this.activeOrders.get(clientOidCounter);
        if (ctx == null) {
            return;
        }
        if (!cancelOrder(ctx)) {
            final OrderContext reject = buildCancelReject(ctx);
            enqueueWriterReport(reject);
            returnToPool(reject);
        } else {
            this.activeOrders.remove(clientOidCounter);
            returnToPool(ctx);
        }
    }

    /**
     * Subclasses may override to implement native amend instead of cancel-replace.
     * The default implementation cancels the existing order and re-submits via {@link #submitForModify}.
     */
    protected void handleModifyOrder() throws Exception {
        final long clientOidCounter = this.modifyOrder.getClientOidCounter();
        final OrderContext oldCtx = this.activeOrders.get(clientOidCounter);
        if (oldCtx == null) {
            return;
        }

        if (!cancelOrder(oldCtx)) {
            final OrderContext reject = buildCancelReject(oldCtx);
            enqueueWriterReport(reject);
            returnToPool(reject);
            return;
        }

        this.activeOrders.remove(clientOidCounter);

        // Save fields needed for the new order before returning the old slot
        final int oldExchangeId = oldCtx.exchangeId;
        final long oldSecurityId = oldCtx.securityId;
        final Side oldSide = oldCtx.side;
        final short oldFlags = oldCtx.flags;
        returnToPool(oldCtx);

        if (this.writerPoolHead <= 0) {
            throw new RuntimeException("Writer order context pool exhausted");
        }
        final OrderContext newCtx = this.writerPool[--this.writerPoolHead];
        newCtx.reset();
        newCtx.orderId = this.nextOrderId++;
        newCtx.clientOidCounter = this.modifyOrder.getClientOidCounter();
        newCtx.clientOidStrategyId = this.modifyOrder.getClientOidStrategyId();
        newCtx.exchangeId = oldExchangeId;
        newCtx.securityId = oldSecurityId;
        newCtx.side = oldSide;
        newCtx.originalQty = this.modifyOrder.decoder.size();
        newCtx.leavesQty = newCtx.originalQty;
        final short modifyFlags = (short) this.modifyOrder.decoder.flags().getRaw();
        newCtx.flags = modifyFlags != 0 ? modifyFlags : oldFlags;

        if (submitForModify(newCtx)) {
            this.activeOrders.put(newCtx.clientOidCounter, newCtx);
            enqueueNewOrder(newCtx);
        } else {
            newCtx.execType = ExecType.REJECT;
            newCtx.orderStatus = OrderStatus.REJECTED;
            newCtx.rejectReason = RejectReason.EXCHANGE_REJECTED;
            enqueueWriterReport(newCtx);
            returnToPool(newCtx);
        }
    }

    /**
     * Submit a new order to the exchange.
     * Implementations must populate {@code ctx.exchangeOrderIdBytes} and {@code ctx.exchangeOrderIdLength}
     * on success.
     *
     * @return true if the order was successfully submitted
     */
    protected abstract boolean submitOrder(OrderContext ctx) throws Exception;

    /**
     * Cancel an existing order on the exchange.
     *
     * @return true if the cancel request was accepted
     */
    protected abstract boolean cancelOrder(OrderContext ctx) throws Exception;

    /**
     * Submit the replacement order for a cancel-replace modify.
     * Implementations must populate {@code ctx.exchangeOrderIdBytes} and {@code ctx.exchangeOrderIdLength}
     * on success. Called by the base class only after the original order has been successfully cancelled.
     *
     * @return true if the replacement order was successfully submitted
     */
    protected abstract boolean submitForModify(OrderContext ctx) throws Exception;

    protected final void enqueueNewOrder(final OrderContext ctx) {
        final int idx = this.newOrderQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("New order queue overflow");
        }
        this.newOrderQueue.indexAt(idx).copyFrom(ctx);
        this.newOrderQueue.commit(idx);
    }

    protected final void enqueueWriterReport(final OrderContext ctx) {
        final int idx = this.writerReportQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("Writer report queue overflow");
        }
        this.writerReportQueue.indexAt(idx).copyFrom(ctx);
        this.writerReportQueue.commit(idx);
    }

    protected final OrderContext buildCancelReject(final OrderContext existing) {
        if (this.writerPoolHead <= 0) {
            throw new RuntimeException("Writer order context pool exhausted");
        }
        final OrderContext reject = this.writerPool[--this.writerPoolHead];
        reject.reset();
        reject.orderId = existing.orderId;
        reject.clientOidCounter = existing.clientOidCounter;
        reject.clientOidStrategyId = existing.clientOidStrategyId;
        reject.exchangeId = existing.exchangeId;
        reject.securityId = existing.securityId;
        reject.execType = ExecType.CANCEL_REJECT;
        reject.orderStatus = OrderStatus.CANCELED;
        reject.rejectReason = RejectReason.EXCHANGE_REJECTED;
        return reject;
    }

    /**
     * Builds a notice that the venue accepted an amend, for the reader to apply.
     *
     * <p>The writer sees the amend's result but not the order's fills, which arrive on the reader's
     * stream. Only the reader can therefore produce a correct acknowledgement, so the writer hands it
     * the venue's numbers instead of reporting directly. Pass {@link OrderContext#QTY_ABSENT} for any
     * quantity the venue did not report. The returned context must be passed to
     * {@link #enqueueWriterReport} and then {@link #returnToPool}.
     */
    protected final OrderContext buildAmendAccepted(
            final OrderContext existing, final long newSize, final long venueFillCount, final long venueRemaining) {
        if (this.writerPoolHead <= 0) {
            throw new RuntimeException("Writer order context pool exhausted");
        }
        final OrderContext notice = this.writerPool[--this.writerPoolHead];
        notice.reset();
        notice.amendAccepted = true;
        notice.clientOidCounter = existing.clientOidCounter;
        notice.clientOidStrategyId = existing.clientOidStrategyId;
        notice.originalQty = newSize;
        notice.cumulativeFilledQty = venueFillCount;
        notice.leavesQty = venueRemaining;
        notice.exchangeOrderIdLength = existing.exchangeOrderIdLength;
        System.arraycopy(
                existing.exchangeOrderIdBytes, 0, notice.exchangeOrderIdBytes, 0, existing.exchangeOrderIdLength);
        return notice;
    }

    protected final void returnToPool(final OrderContext ctx) {
        ctx.reset();
        this.writerPool[this.writerPoolHead++] = ctx;
    }

    protected final OrderContext getActiveOrder(final long clientOidCounter) {
        return this.activeOrders.get(clientOidCounter);
    }

    protected final void removeActiveOrder(final long clientOidCounter) {
        this.activeOrders.remove(clientOidCounter);
    }

    @VisibleForTesting
    protected final int activeOrderCount() {
        return this.activeOrders.size();
    }
}
