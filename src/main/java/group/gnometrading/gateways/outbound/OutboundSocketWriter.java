package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
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

    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    // The queue read doesn't report how many entries it consumed; a back-off idle strategy needs the count.
    private int releasedThisPass;

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
        final int released = this.releasedThisPass;
        this.releasedThisPass = 0;
        return released + this.orderPoller.poll();
    }

    @Override
    public final ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    private void consumeReleasedOrder(final OrderContext src) {
        this.releasedThisPass++;
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
            rejectUnpooledSubmit();
            return;
        }
        final OrderContext ctx = this.writerPool[--this.writerPoolHead];
        ctx.reset();
        ctx.clientOidCounter = this.order.getClientOidCounter();
        ctx.clientOidStrategyId = this.order.getClientOidStrategyId();
        ctx.exchangeId = this.order.decoder.exchangeId();
        ctx.securityId = this.order.decoder.securityId();
        ctx.originalQty = this.order.decoder.size();
        ctx.leavesQty = ctx.originalQty;
        ctx.side = this.order.decoder.side();
        ctx.flags = (short) this.order.decoder.flags().getRaw();

        if (!prepareOrder(ctx)) {
            rejectSubmit(ctx, RejectReason.GATEWAY_REJECTED);
            return;
        }

        // Registered with the reader before anything is sent, so venue events that beat the submit's
        // response, or arrive when that response is lost, still find the order. If the reader can't take
        // it, nothing is sent: an order the reader doesn't know would fill unseen.
        if (!tryEnqueueNewOrder(ctx)) {
            rejectSubmit(ctx, RejectReason.GATEWAY_REJECTED);
            return;
        }
        this.activeOrders.put(ctx.clientOidCounter, ctx);

        SubmitResult result = send(ctx);
        if (result == SubmitResult.UNKNOWN) {
            result = resolveUnknownSubmit(ctx);
        }
        if (result == SubmitResult.REJECTED) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx, RejectReason.EXCHANGE_REJECTED);
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

    private void rejectSubmit(final OrderContext ctx, final RejectReason reason) {
        ctx.execType = ExecType.REJECT;
        ctx.orderStatus = OrderStatus.REJECTED;
        ctx.rejectReason = reason;
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
            enqueueCancelReject(ctx, RejectReason.EXCHANGE_REJECTED);
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
            enqueueCancelReject(ctx, RejectReason.GATEWAY_REJECTED);
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

    private boolean tryEnqueueNewOrder(final OrderContext ctx) {
        final int idx = this.newOrderQueue.tryClaim();
        if (idx < 0) {
            return false;
        }
        this.newOrderQueue.indexAt(idx).copyFrom(ctx);
        this.newOrderQueue.commit(idx);
        return true;
    }

    private void enqueueWriterReport(final OrderContext ctx) {
        final int idx = claimWriterReport();
        this.writerReportQueue.indexAt(idx).copyFrom(ctx);
        this.writerReportQueue.commit(idx);
    }

    /**
     * A report must reach the OMS, or it waits on the order forever. The reader drains this queue on
     * every pass, so it stays full only while the reader is stalled; waiting is the backpressure, and
     * a dead reader fails the gateway on its own.
     */
    private int claimWriterReport() {
        int idx;
        while ((idx = this.writerReportQueue.tryClaim()) < 0) {
            Thread.onSpinWait();
        }
        return idx;
    }

    /**
     * Refuses a submit when every pool slot is held by a working order. Written straight into the queue,
     * as there is no context to build it in.
     */
    private void rejectUnpooledSubmit() {
        final int idx = claimWriterReport();
        final OrderContext reject = this.writerReportQueue.indexAt(idx);
        reject.reset();
        reject.clientOidCounter = this.order.getClientOidCounter();
        reject.clientOidStrategyId = this.order.getClientOidStrategyId();
        reject.exchangeId = this.order.decoder.exchangeId();
        reject.securityId = this.order.decoder.securityId();
        reject.execType = ExecType.REJECT;
        reject.orderStatus = OrderStatus.REJECTED;
        reject.rejectReason = RejectReason.GATEWAY_REJECTED;
        this.writerReportQueue.commit(idx);
    }

    /**
     * Reports that a cancel or modify of {@code existing} was refused. Written straight into the queue
     * so it needs no pool slot, which matters when every slot is held by a working order.
     */
    protected final void enqueueCancelReject(final OrderContext existing, final RejectReason reason) {
        final int idx = claimWriterReport();
        final OrderContext reject = this.writerReportQueue.indexAt(idx);
        reject.reset();
        reject.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, reject.correlationIdBytes, 0, existing.correlationIdLength);
        reject.clientOidCounter = existing.clientOidCounter;
        reject.clientOidStrategyId = existing.clientOidStrategyId;
        reject.exchangeId = existing.exchangeId;
        reject.securityId = existing.securityId;
        reject.execType = ExecType.CANCEL_REJECT;
        reject.orderStatus = OrderStatus.CANCELED;
        reject.rejectReason = reason;
        this.writerReportQueue.commit(idx);
    }

    /**
     * Tells the reader the venue accepted an amend.
     *
     * <p>The writer sees the amend's result but not the order's fills, which arrive on the reader's
     * stream. Only the reader can therefore produce a correct acknowledgement, so the writer hands it
     * the venue's numbers instead of reporting directly. Pass {@link OrderContext#QTY_ABSENT} for any
     * quantity the venue did not report. Written straight into the queue, so it needs no pool slot.
     */
    protected final void enqueueAmendAccepted(
            final OrderContext existing, final long newSize, final long venueFillCount, final long venueRemaining) {
        final int idx = claimWriterReport();
        final OrderContext notice = this.writerReportQueue.indexAt(idx);
        notice.reset();
        notice.amendAccepted = true;
        notice.clientOidCounter = existing.clientOidCounter;
        notice.clientOidStrategyId = existing.clientOidStrategyId;
        notice.originalQty = newSize;
        notice.cumulativeFilledQty = venueFillCount;
        notice.leavesQty = venueRemaining;
        notice.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, notice.correlationIdBytes, 0, existing.correlationIdLength);
        this.writerReportQueue.commit(idx);
    }

    private void returnToPool(final OrderContext ctx) {
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
