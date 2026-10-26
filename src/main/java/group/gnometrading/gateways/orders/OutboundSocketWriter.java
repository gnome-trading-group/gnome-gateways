package group.gnometrading.gateways.orders;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.networking.http.HTTPResponse;
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
import java.util.concurrent.TimeUnit;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

public abstract class OutboundSocketWriter implements GnomeAgent {

    private static final int MAX_RESOLVE_ATTEMPTS = 2;
    private static final long KEEP_WARM_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final SequencedPoller orderPoller;
    protected final ManyToOneRingBuffer<OrderContext> newOrderQueue;
    protected final ManyToOneRingBuffer<OrderContext> writerReportQueue;

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

        SubmitResult result = send(ctx);
        if (result == SubmitResult.UNKNOWN) {
            result = resendsAreSafe() ? resolveUnknownSubmit(ctx) : lookUpUnknownSubmit(ctx);
        }
        // A first send can't be a duplicate of our own: the id is new, so the venue is refusing it for its own reasons.
        if (result == SubmitResult.REJECTED || result == SubmitResult.DUPLICATE) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx, venueRejectReason(ctx));
        } else if (result == SubmitResult.RATE_LIMITED) {
            this.activeOrders.remove(ctx.clientOidCounter);
            rejectSubmit(ctx, RejectReason.RATE_LIMITED);
        }
        // Still UNKNOWN: the order may be live, so it stays registered and the venue's events settle it.
        // Any other failure from here on leaves it the same way, as the request may have reached the venue.
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
            if (resent == SubmitResult.DUPLICATE) {
                // The earlier send landed. Only the lookup can give its venue id, and a miss means the venue's
                // records lag, not that it's gone: it stays registered and the venue's events settle it.
                return find(ctx) == SubmitResult.ACCEPTED ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
            }
            // A resend refused for the rate limit says nothing of the first send, which may have landed.
            if (resent == SubmitResult.REJECTED || resent == SubmitResult.RATE_LIMITED) {
                final SubmitResult found = find(ctx);
                if (found != SubmitResult.UNKNOWN) {
                    return found;
                }
            }
        }
        return SubmitResult.UNKNOWN;
    }

    /**
     * Settles a submit that got no clear answer on a venue that would place a resent request a second time: the
     * order is only looked for. A miss can't be taken as a refusal, since the venue may still be processing it, so
     * it stays registered and the venue's events settle it.
     */
    private SubmitResult lookUpUnknownSubmit(final OrderContext ctx) throws Exception {
        return find(ctx) == SubmitResult.ACCEPTED ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
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
        if (ctx != null) {
            enqueueCancelReject(ctx, RejectReason.GATEWAY_REJECTED);
        }
    }

    /** How a venue answered an order submission. */
    protected enum SubmitResult {
        ACCEPTED,
        REJECTED,
        /** No clear answer (timeout, dropped connection, 5xx, unreadable reply): it may be live. */
        UNKNOWN,
        /** Refused because the venue already has an order with this id. */
        DUPLICATE,
        /** Refused for the rate limit, by the venue or before it was sent: nothing was placed. */
        RATE_LIMITED
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

    /** Whether the venue has the order: ACCEPTED if so, REJECTED if not, UNKNOWN if it could not tell. */
    protected abstract SubmitResult findOrder(OrderContext ctx) throws Exception;

    /**
     * The usual reading of an HTTP status: a 429 refused it for the rate limit, any other 4xx refused it, and
     * anything else unclear is unknown.
     */
    protected static SubmitResult classifyFailure(final int statusCode) {
        if (statusCode == HTTPResponse.STATUS_TOO_MANY_REQUESTS) {
            return SubmitResult.RATE_LIMITED;
        }
        return statusCode >= 400 && statusCode < 500 ? SubmitResult.REJECTED : SubmitResult.UNKNOWN;
    }

    /**
     * Why the OMS is told a refused amend failed: the rate limit for a 429, else the reason the venue's text gives,
     * else the venue's refusal.
     */
    protected static RejectReason rejectReasonFor(final HTTPResponse response, final VenueRejectText rejectText) {
        if (response.getStatusCode() == HTTPResponse.STATUS_TOO_MANY_REQUESTS) {
            return RejectReason.RATE_LIMITED;
        }
        final RejectReason reason = rejectText.classify(response.getBody());
        return reason != null ? reason : RejectReason.EXCHANGE_REJECTED;
    }

    /** How a venue answered a cancel. One with no clear answer throws an {@link IOException} instead. */
    protected enum CancelResult {
        ACCEPTED,
        REJECTED,
        /** Refused for the rate limit, by the venue or before it was sent: the order is still working. */
        RATE_LIMITED
    }

    /** The reading of a refused cancel's response: the rate limit for a 429, else the venue's refusal. */
    protected static CancelResult classifyCancelFailure(final HTTPResponse response) {
        return response.getStatusCode() == HTTPResponse.STATUS_TOO_MANY_REQUESTS
                ? CancelResult.RATE_LIMITED
                : CancelResult.REJECTED;
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
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted waiting for room in the writer report queue");
            }
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

    /**
     * Tells the reader all an {@link OrderContext#awaitsFinalFill} order will ever fill, as the venue's answer to its
     * submit put it. The fills themselves arrive on the reader's stream, before or after this.
     */
    protected final void enqueueFinalFill(final OrderContext existing, final long finalFillQty) {
        final int idx = claimWriterReport();
        final OrderContext notice = this.writerReportQueue.indexAt(idx);
        notice.reset();
        notice.finalFillNotice = true;
        notice.finalFillQty = finalFillQty;
        notice.clientOidCounter = existing.clientOidCounter;
        notice.clientOidStrategyId = existing.clientOidStrategyId;
        notice.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, notice.correlationIdBytes, 0, existing.correlationIdLength);
        this.writerReportQueue.commit(idx);
    }

    /**
     * Tells the reader the venue's id for {@code existing}, from the submit's answer, on a venue whose events can't
     * all be matched by a correlation id: the reader can then look the order up after a reconnect. Written straight
     * into the queue, so it needs no pool slot.
     */
    protected final void enqueueVenueOrderId(final OrderContext existing) {
        final int idx = claimWriterReport();
        final OrderContext notice = this.writerReportQueue.indexAt(idx);
        notice.reset();
        notice.venueOrderIdNotice = true;
        notice.clientOidCounter = existing.clientOidCounter;
        notice.clientOidStrategyId = existing.clientOidStrategyId;
        notice.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, notice.correlationIdBytes, 0, existing.correlationIdLength);
        notice.exchangeOrderIdLength = existing.exchangeOrderIdLength;
        System.arraycopy(
                existing.exchangeOrderIdBytes, 0, notice.exchangeOrderIdBytes, 0, existing.exchangeOrderIdLength);
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
