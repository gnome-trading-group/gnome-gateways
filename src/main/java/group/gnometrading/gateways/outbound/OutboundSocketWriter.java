package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

public abstract class OutboundSocketWriter implements GnomeAgent {

    private static final long KEEP_WARM_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    // Kalshi's reads were measured lagging its writes by about a second; the Polymarket venues aren't measured yet.
    private static final long DEFAULT_READ_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final SequencedPoller orderPoller;
    protected final ManyToOneRingBuffer<OrderContext> newOrderQueue;
    protected final ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private final ReaderNotices notices;

    private final Long2ObjectHashMap<OrderContext> activeOrders;
    private final OrderContext[] writerPool;
    private int writerPoolHead;

    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    // Held once, so the hot loop doesn't create a method reference on every pass.
    private final MessageConsumer<OrderContext> releasedOrderHandler = this::consumeReleasedOrder;
    // The queue read doesn't report how many entries it consumed; a back-off idle strategy needs the count.
    private int releasedThisPass;
    // Thrown from doWork once the poll has moved past the message: thrown inside it, the poller would
    // deliver the same message again forever.
    private Exception orderFailure;
    private long lastKeepWarmNanos = System.nanoTime();
    private long readSettleNanos = DEFAULT_READ_SETTLE_NANOS;
    private SubmitPolicy submitPolicy;

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
        this.notices = new ReaderNotices(writerReportQueue);
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

    /**
     * How long after a send a lookup's miss is believed, as the venue's reads can lag its writes; two seconds unless
     * set. Set before the writer starts.
     */
    public final void readSettleNanos(final long nanos) {
        this.readSettleNanos = nanos;
        this.submitPolicy = null;
    }

    private SubmitPolicy submitPolicy() {
        if (this.submitPolicy == null) {
            this.submitPolicy = new SubmitPolicy(this.readSettleNanos);
        }
        return this.submitPolicy;
    }

    @Override
    public final void onClose() {
        try {
            this.orderPoller.poll();
        } catch (Exception e) { // best-effort drain on shutdown
        }
    }

    @Override
    public final int doWork() throws Exception {
        this.releasedOrderQueue.read(this.releasedOrderHandler, OrderContext.HANDOFF_QUEUE_CAPACITY);
        final int released = this.releasedThisPass;
        this.releasedThisPass = 0;
        final int polled = this.orderPoller.poll();
        if (this.orderFailure != null) {
            final Exception failure = this.orderFailure;
            this.orderFailure = null;
            throw failure;
        }
        if (released + polled == 0) {
            maybeKeepConnectionsWarm();
        }
        return released + polled;
    }

    /** Only on a pass with nothing to do, so it never holds up an order. */
    private void maybeKeepConnectionsWarm() {
        final long now = System.nanoTime();
        if (now - this.lastKeepWarmNanos >= keepWarmIntervalNanos()) {
            this.lastKeepWarmNanos = now;
            keepConnectionsWarm();
        }
    }

    /**
     * Called on the writer's thread, about once a {@link #keepWarmIntervalNanos} while no orders are arriving, for the
     * venue to replace connections that are closed or have been idle long enough to be dropped, so the next order
     * finds one ready. Must not throw: a failure only means the next request connects for itself.
     */
    protected void keepConnectionsWarm() {}

    /**
     * How often {@link #keepConnectionsWarm} is called while idle; a second by default, well inside the shortest idle
     * timeout measured (Kalshi's minute). Override to change it, e.g. to run it on every idle pass in a test.
     */
    protected long keepWarmIntervalNanos() {
        return KEEP_WARM_INTERVAL_NANOS;
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

    /**
     * Every message is settled here, whatever goes wrong: each path answers the OMS before a failure is
     * passed on, so a message is never left without a report and never handled twice.
     */
    private void onOrder(final long globalSeq, final int templateId, final UnsafeBuffer buf, final int len) {
        try {
            if (templateId == OrderDecoder.TEMPLATE_ID) {
                order.wrap(buf);
                handleSubmitOrder();
            } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
                cancelOrder.wrap(buf);
                handleCancelOrder();
            } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
                modifyOrder.wrap(buf);
                routeModifyOrder();
            }
        } catch (final Exception e) {
            recordFailure(e);
        }
    }

    private void recordFailure(final Exception failure) {
        if (this.orderFailure == null) {
            this.orderFailure = failure;
        } else {
            this.orderFailure.addSuppressed(failure);
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

        final boolean prepared;
        try {
            prepared = prepareOrder(ctx);
        } catch (final Exception e) {
            rejectSubmit(ctx, RejectReason.GATEWAY_REJECTED);
            throw e;
        }
        if (!prepared) {
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

        final SubmitPolicy policy = submitPolicy();
        SubmitPolicy.Action action = policy.afterSend(ctx, send(ctx), resendsAreSafe());
        while (action == SubmitPolicy.Action.RESEND || action == SubmitPolicy.Action.LOOK_UP) {
            if (action == SubmitPolicy.Action.RESEND) {
                action = policy.afterSend(ctx, send(ctx), resendsAreSafe());
            } else {
                waitUntil(ctx.lookUpNotBeforeNanos);
                action = policy.afterLookUp(ctx, find(ctx), nanoTime());
            }
        }
        if (action == SubmitPolicy.Action.REJECT) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx, venueRejectReason(ctx));
        } else if (action == SubmitPolicy.Action.REJECT_RATE_LIMITED) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx, RejectReason.RATE_LIMITED);
        }
        // Still unknown: the order may be live, so it stays registered and the venue's events settle it.
        // Any other failure from here on leaves it the same way, as the request may have reached the venue.
    }

    /**
     * Waits on this thread for a lookup that would come too soon after a send to be believed. Only after a submit
     * with no clear answer whose resend was refused, so rare; the whole listing waits with it.
     */
    private void waitUntil(final long notBeforeNanos) {
        long remaining;
        while (notBeforeNanos != 0 && (remaining = notBeforeNanos - nanoTime()) > 0) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            parkNanos(remaining);
        }
    }

    private static RejectReason venueRejectReason(final OrderContext ctx) {
        return ctx.rejectReason != null ? ctx.rejectReason : RejectReason.EXCHANGE_REJECTED;
    }

    /**
     * Each send starts with no reason, so a refusal is only ever explained by its own answer: never by an earlier
     * send's, nor by a lookup, which can't say why an order is missing.
     */
    private SubmitResult send(final OrderContext ctx) throws Exception {
        ctx.rejectReason = null;
        ctx.lastSendNanos = nanoTime();
        try {
            return submitOrder(ctx);
        } catch (final IOException e) {
            return SubmitResult.UNKNOWN;
        }
    }

    private LookupResult find(final OrderContext ctx) throws Exception {
        try {
            return findOrder(ctx);
        } catch (final IOException e) {
            return LookupResult.UNKNOWN;
        }
    }

    private void rejectSubmit(final OrderContext ctx, final RejectReason reason) {
        this.notices.reject(ctx, reason);
        returnToPool(ctx);
    }

    private void handleCancelOrder() throws Exception {
        final long clientOidCounter = this.cancelOrder.getClientOidCounter();
        final OrderContext ctx = this.activeOrders.get(clientOidCounter);
        if (ctx == null) {
            rejectUnknownOrder(
                    clientOidCounter,
                    this.cancelOrder.getClientOidStrategyId(),
                    this.cancelOrder.decoder.exchangeId(),
                    this.cancelOrder.decoder.securityId());
            return;
        }
        final CancelResult result;
        try {
            result = cancelOrder(ctx);
        } catch (final IOException e) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            return;
        } catch (final Exception e) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            throw e;
        }
        // An accepted cancel keeps the order's slot until the reader releases it on the venue's cancel event.
        // Freed here, the writer would count slots the reader still holds, and could send an order the reader
        // has no room to track.
        if (result == CancelResult.REJECTED) {
            enqueueCancelReject(ctx, RejectReason.EXCHANGE_REJECTED);
        } else if (result == CancelResult.RATE_LIMITED) {
            enqueueCancelReject(ctx, RejectReason.RATE_LIMITED);
        }
    }

    /**
     * A modify that fails without the venue's answer is refused: the OMS keeps the order's last known
     * terms, and the venue's own events correct them if the amend did land.
     */
    private void routeModifyOrder() throws Exception {
        try {
            handleModifyOrder();
        } catch (final Exception e) {
            final OrderContext ctx = this.activeOrders.get(this.modifyOrder.getClientOidCounter());
            if (ctx != null) {
                enqueueCancelReject(ctx, RejectReason.UNKNOWN);
            }
            if (!(e instanceof IOException)) {
                throw e;
            }
        }
    }

    /**
     * Venues that can amend a working order override this. The OMS cancels and resubmits instead of
     * modifying on every other venue ({@code VenueCapabilities}), so a modify reaching one of those
     * means the capability table and the gateway disagree, and it is refused.
     */
    protected void handleModifyOrder() throws Exception {
        final OrderContext ctx = this.activeOrders.get(this.modifyOrder.getClientOidCounter());
        if (ctx == null) {
            rejectUnknownModify();
        } else {
            enqueueCancelReject(ctx, RejectReason.GATEWAY_REJECTED);
        }
    }

    /**
     * Refuses the modify being handled when the writer doesn't hold its order (it ended, or was never sent), so the OMS
     * isn't left waiting on it.
     */
    protected final void rejectUnknownModify() {
        rejectUnknownOrder(
                this.modifyOrder.getClientOidCounter(),
                this.modifyOrder.getClientOidStrategyId(),
                this.modifyOrder.decoder.exchangeId(),
                this.modifyOrder.decoder.securityId());
    }

    /**
     * Builds the order's request and sets {@code ctx}'s correlation id, without sending anything.
     *
     * @return false to reject the order without sending it
     */
    protected abstract boolean prepareOrder(OrderContext ctx) throws Exception;

    /**
     * Sends the request {@link #prepareOrder} built. Unless {@link #resendsAreSafe} says otherwise, it may be called
     * again with the same request when an earlier send got no clear answer, so the request must be one the venue
     * deduplicates. On acceptance, sets {@code ctx.exchangeOrderIdBytes} for later cancels. A refusal may set
     * {@code ctx.rejectReason} to say why before returning {@link SubmitResult#REJECTED}; left unset, the OMS is told
     * EXCHANGE_REJECTED.
     */
    protected abstract SubmitResult submitOrder(OrderContext ctx) throws Exception;

    /**
     * Whether the venue deduplicates a resent submit. A venue without a client order id would place it twice, so a
     * submit with no clear answer is then only looked up, never sent again.
     */
    protected boolean resendsAreSafe() {
        return true;
    }

    /**
     * Whether the venue has the order. NOT_FOUND only from a lookup that sees orders in every state; one that sees only
     * open orders answers UNKNOWN for a miss, as a filled or cancelled order is missing from it too.
     */
    protected abstract LookupResult findOrder(OrderContext ctx) throws Exception;

    /** The clock {@link #readSettleNanos} is measured on; overridden only by tests, to control time. */
    protected long nanoTime() {
        return System.nanoTime();
    }

    /** Waits on this thread until a lookup may be believed; overridden only by tests, to advance their clock. */
    protected void parkNanos(final long nanos) {
        LockSupport.parkNanos(nanos);
    }

    /** Cancels an existing order on the exchange. */
    protected abstract CancelResult cancelOrder(OrderContext ctx) throws Exception;

    private boolean tryEnqueueNewOrder(final OrderContext ctx) {
        final int idx = this.newOrderQueue.tryClaim();
        if (idx < 0) {
            return false;
        }
        this.newOrderQueue.indexAt(idx).copyFrom(ctx);
        this.newOrderQueue.commit(idx);
        return true;
    }

    /** Refuses a submit when every pool slot is held by a working order: there is no context to build it in. */
    private void rejectUnpooledSubmit() {
        this.notices.rejectUnheld(
                0,
                this.order.getClientOidCounter(),
                this.order.getClientOidStrategyId(),
                this.order.decoder.exchangeId(),
                this.order.decoder.securityId(),
                RejectReason.GATEWAY_REJECTED);
    }

    /** Refuses a cancel or modify of an order the writer doesn't hold. */
    private void rejectUnknownOrder(
            final long clientOidCounter, final int clientOidStrategyId, final int exchangeId, final long securityId) {
        this.notices.rejectUnknownOrder(0, clientOidCounter, clientOidStrategyId, exchangeId, securityId);
    }

    /** Reports that a cancel or modify of {@code existing} was refused. */
    protected final void enqueueCancelReject(final OrderContext existing, final RejectReason reason) {
        this.notices.cancelReject(existing, reason);
    }

    /** See {@link ReaderNotices#amendAccepted}. */
    protected final void enqueueAmendAccepted(
            final OrderContext existing, final long newSize, final long venueFillCount, final long venueRemaining) {
        this.notices.amendAccepted(existing, newSize, venueFillCount, venueRemaining);
    }

    /** See {@link ReaderNotices#finalFill}. */
    protected final void enqueueFinalFill(final OrderContext existing, final long finalFillQty) {
        this.notices.finalFill(existing, finalFillQty);
    }

    /** See {@link ReaderNotices#venueOrderId}. */
    protected final void enqueueVenueOrderId(final OrderContext existing) {
        this.notices.venueOrderId(existing);
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
