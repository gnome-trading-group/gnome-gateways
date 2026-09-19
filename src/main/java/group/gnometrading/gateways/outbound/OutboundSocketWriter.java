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

    private static final int DEFAULT_ACTIVE_ORDER_MAP_CAPACITY = 256;

    private final SequencedPoller orderPoller;
    protected final ManyToOneRingBuffer<OrderContext> contextQueue;
    protected final ManyToOneRingBuffer<OrderContext> rejectQueue;

    private final Long2ObjectHashMap<OrderContext> activeOrders;
    private final OrderContext[] writerPool;
    private int writerPoolHead;
    private long nextOrderId = 1;

    private final ManyToOneRingBuffer<OrderContext> completionQueue;

    protected final Order order = new Order();
    protected final CancelOrder cancelOrder = new CancelOrder();
    protected final ModifyOrder modifyOrder = new ModifyOrder();

    protected OutboundSocketWriter(
            SequencedRingBuffer<?> orderOutboundBuffer,
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue) {
        this.orderPoller = orderOutboundBuffer.createPoller(this::onOrder);
        this.contextQueue = contextQueue;
        this.rejectQueue = rejectQueue;
        this.completionQueue = completionQueue;
        this.activeOrders = new Long2ObjectHashMap<>(DEFAULT_ACTIVE_ORDER_MAP_CAPACITY, 0.6f);
        this.writerPool = new OrderContext[DEFAULT_ACTIVE_ORDER_MAP_CAPACITY];
        for (int i = 0; i < DEFAULT_ACTIVE_ORDER_MAP_CAPACITY; i++) {
            this.writerPool[i] = new OrderContext();
        }
        this.writerPoolHead = DEFAULT_ACTIVE_ORDER_MAP_CAPACITY;
    }

    @Override
    public void onStart() {}

    @Override
    public final int doWork() throws Exception {
        this.completionQueue.read(this::consumeCompletion, 16);
        return this.orderPoller.poll();
    }

    private void consumeCompletion(final OrderContext src) {
        final OrderContext ctx = this.activeOrders.remove(src.orderId);
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

        if (submitOrder(ctx)) {
            this.activeOrders.put(ctx.orderId, ctx);
            enqueueContext(ctx);
        } else {
            ctx.execType = ExecType.REJECT;
            ctx.orderStatus = OrderStatus.REJECTED;
            ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
            enqueueReject(ctx);
            returnToPool(ctx);
        }
    }

    private void handleCancelOrder() throws Exception {
        final long orderId = this.cancelOrder.decoder.orderId();
        final OrderContext ctx = this.activeOrders.get(orderId);
        if (ctx == null) {
            return;
        }
        if (!cancelOrder(ctx)) {
            final OrderContext reject = buildCancelReject(ctx);
            enqueueReject(reject);
            returnToPool(reject);
        } else {
            this.activeOrders.remove(orderId);
            returnToPool(ctx);
        }
    }

    /**
     * Subclasses may override to implement native amend instead of cancel-replace.
     * The default implementation cancels the existing order and re-submits via {@link #submitForModify}.
     */
    protected void handleModifyOrder() throws Exception {
        final long orderId = this.modifyOrder.decoder.orderId();
        final OrderContext oldCtx = this.activeOrders.get(orderId);
        if (oldCtx == null) {
            return;
        }

        if (!cancelOrder(oldCtx)) {
            final OrderContext reject = buildCancelReject(oldCtx);
            enqueueReject(reject);
            returnToPool(reject);
            return;
        }

        this.activeOrders.remove(orderId);

        // Save fields needed for the new order before returning the old slot
        final int oldExchangeId = oldCtx.exchangeId;
        final long oldSecurityId = oldCtx.securityId;
        final Side oldSide = oldCtx.side;
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

        if (submitForModify(newCtx)) {
            this.activeOrders.put(newCtx.orderId, newCtx);
            enqueueContext(newCtx);
        } else {
            newCtx.execType = ExecType.REJECT;
            newCtx.orderStatus = OrderStatus.REJECTED;
            newCtx.rejectReason = RejectReason.EXCHANGE_REJECTED;
            enqueueReject(newCtx);
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

    protected final void enqueueContext(final OrderContext ctx) {
        final int idx = this.contextQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("Context queue overflow");
        }
        this.contextQueue.indexAt(idx).copyFrom(ctx);
        this.contextQueue.commit(idx);
    }

    protected final void enqueueReject(final OrderContext ctx) {
        final int idx = this.rejectQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("Reject queue overflow");
        }
        this.rejectQueue.indexAt(idx).copyFrom(ctx);
        this.rejectQueue.commit(idx);
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

    protected final void returnToPool(final OrderContext ctx) {
        ctx.reset();
        this.writerPool[this.writerPoolHead++] = ctx;
    }

    protected final OrderContext getActiveOrder(final long orderId) {
        return this.activeOrders.get(orderId);
    }

    protected final void removeActiveOrder(final long orderId) {
        this.activeOrders.remove(orderId);
    }

    @VisibleForTesting
    protected final int activeOrderCount() {
        return this.activeOrders.size();
    }
}
