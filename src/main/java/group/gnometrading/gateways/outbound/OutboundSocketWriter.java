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
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

public abstract class OutboundSocketWriter implements GnomeAgent {

    private static final int MAX_RESOLVE_ATTEMPTS = 2;

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

        if (!prepareOrder(ctx)) {
            rejectSubmit(ctx);
            return;
        }

        // Registered with the reader before anything is sent, so venue events that beat the submit's
        // response, or arrive when that response is lost, still find the order.
        this.activeOrders.put(ctx.clientOidCounter, ctx);
        enqueueNewOrder(ctx);

        SubmitResult result = send(ctx);
        if (result == SubmitResult.UNKNOWN) {
            result = resolveUnknownSubmit(ctx);
        }
        if (result == SubmitResult.REJECTED) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx);
        }
        // Still UNKNOWN: the order may be live, so it stays registered and the venue's events settle it.
    }

    /**
     * Settles a submit that got no clear answer. The prepared request is sent again first: both venues
     * deduplicate it, so it cannot place a second order, and looking the order up straight away could
     * miss one the venue is still processing. Only a refusal, which may mean "already exists", is
     * checked against the venue's records.
     */
    private SubmitResult resolveUnknownSubmit(final OrderContext ctx) throws Exception {
        for (int attempt = 0; attempt < MAX_RESOLVE_ATTEMPTS; attempt++) {
            final SubmitResult resent = send(ctx);
            if (resent == SubmitResult.ACCEPTED) {
                return resent;
            }
            if (resent == SubmitResult.REJECTED) {
                final SubmitResult found = find(ctx);
                if (found != SubmitResult.UNKNOWN) {
                    return found;
                }
            }
        }
        return SubmitResult.UNKNOWN;
    }

    private SubmitResult send(final OrderContext ctx) throws Exception {
        try {
            return submitOrder(ctx);
        } catch (final IOException e) {
            return SubmitResult.UNKNOWN;
        }
    }

    private SubmitResult find(final OrderContext ctx) throws Exception {
        try {
            return findOrder(ctx);
        } catch (final IOException e) {
            return SubmitResult.UNKNOWN;
        }
    }

    private void rejectSubmit(final OrderContext ctx) {
        ctx.execType = ExecType.REJECT;
        ctx.orderStatus = OrderStatus.REJECTED;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        enqueueWriterReport(ctx);
        returnToPool(ctx);
    }

    private void handleCancelOrder() throws Exception {
        final long clientOidCounter = this.cancelOrder.getClientOidCounter();
        final OrderContext ctx = this.activeOrders.get(clientOidCounter);
        if (ctx == null) {
            return;
        }
        if (!cancelOrder(ctx)) {
            enqueueCancelReject(ctx);
        } else {
            this.activeOrders.remove(clientOidCounter);
            returnToPool(ctx);
        }
    }

    /**
     * Venues that can amend a working order override this. The OMS cancels and resubmits instead of
     * modifying on every other venue ({@code VenueCapabilities}), so a modify reaching one of those
     * means the capability table and the gateway disagree, and it is refused.
     */
    protected void handleModifyOrder() throws Exception {
        final OrderContext ctx = this.activeOrders.get(this.modifyOrder.getClientOidCounter());
        if (ctx != null) {
            enqueueCancelReject(ctx);
        }
    }

    /** How a venue answered an order submission. */
    protected enum SubmitResult {
        ACCEPTED,
        REJECTED,
        /** No clear answer (timeout, dropped connection, 5xx, unreadable reply): it may be live. */
        UNKNOWN
    }

    /**
     * Builds the order's request and sets {@code ctx}'s correlation id, without sending anything.
     *
     * @return false to reject the order without sending it
     */
    protected abstract boolean prepareOrder(OrderContext ctx) throws Exception;

    /**
     * Sends the request {@link #prepareOrder} built. May be called again with the same request when
     * an earlier send got no clear answer, so the request must be one the venue deduplicates. On
     * acceptance, sets {@code ctx.exchangeOrderIdBytes} for later cancels.
     */
    protected abstract SubmitResult submitOrder(OrderContext ctx) throws Exception;

    /** Whether the venue has the order: ACCEPTED if so, REJECTED if not, UNKNOWN if it could not tell. */
    protected abstract SubmitResult findOrder(OrderContext ctx) throws Exception;

    /** The usual reading of an HTTP status: a 4xx refused the request; anything else unclear is unknown. */
    protected static SubmitResult classifyFailure(final int statusCode) {
        return statusCode >= 400 && statusCode < 500 ? SubmitResult.REJECTED : SubmitResult.UNKNOWN;
    }

    /**
     * Cancel an existing order on the exchange.
     *
     * @return true if the cancel request was accepted
     */
    protected abstract boolean cancelOrder(OrderContext ctx) throws Exception;

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

    /**
     * Reports that a cancel or modify of {@code existing} was refused. Written straight into the queue
     * so it needs no pool slot, which matters when every slot is held by a working order.
     */
    protected final void enqueueCancelReject(final OrderContext existing) {
        final int idx = this.writerReportQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("Writer report queue overflow");
        }
        final OrderContext reject = this.writerReportQueue.indexAt(idx);
        reject.reset();
        reject.orderId = existing.orderId;
        reject.clientOidCounter = existing.clientOidCounter;
        reject.clientOidStrategyId = existing.clientOidStrategyId;
        reject.exchangeId = existing.exchangeId;
        reject.securityId = existing.securityId;
        reject.execType = ExecType.CANCEL_REJECT;
        reject.orderStatus = OrderStatus.CANCELED;
        reject.rejectReason = RejectReason.EXCHANGE_REJECTED;
        this.writerReportQueue.commit(idx);
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
        notice.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, notice.correlationIdBytes, 0, existing.correlationIdLength);
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
