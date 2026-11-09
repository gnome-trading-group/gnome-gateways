package group.gnometrading.gateways.orders;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;

/**
 * What the thread sending orders tells the thread reading their events, which alone reports to the OMS: it sees the
 * venue's answers, the reader the fills, so every report is built where both are known. Each notice is written
 * straight into the queue, needing no order slot of its own. A full queue is waited out: the reader drains it on
 * every pass, so it stays full only while the reader is stalled, and a notice must not be lost, or the OMS waits on
 * its order forever.
 */
public final class ReaderNotices {

    private final ManyToOneRingBuffer<OrderContext> queue;
    private int claimed;

    public ReaderNotices(final ManyToOneRingBuffer<OrderContext> queue) {
        this.queue = queue;
    }

    /**
     * Hands the reader a new order to hold, before it is sent. On the queue every later notice of the order follows,
     * so none can reach the reader before the order does.
     */
    public void register(final OrderContext ctx) {
        final OrderContext notice = claim();
        notice.copyFrom(ctx);
        notice.registration = true;
        commit();
    }

    /** Ends an order the reader may hold with a {@code REJECT}: the reader drops its copy and reports it. */
    public void reject(final OrderContext ctx, final RejectReason reason) {
        final OrderContext notice = claim();
        notice.copyFrom(ctx);
        notice.execType = ExecType.REJECT;
        notice.orderStatus = OrderStatus.REJECTED;
        notice.rejectReason = reason;
        commit();
    }

    /**
     * Ends with a {@code REJECT} an order the reader was {@linkplain #register handed}. If the reader has already
     * ended it on the venue's word, the notice is dropped: the order must not end twice.
     */
    public void rejectRegistered(final OrderContext ctx, final RejectReason reason) {
        final OrderContext notice = claim();
        notice.copyFrom(ctx);
        notice.registration = false;
        notice.endsRegistered = true;
        notice.execType = ExecType.REJECT;
        notice.orderStatus = OrderStatus.REJECTED;
        notice.rejectReason = reason;
        commit();
    }

    /**
     * Ends an order cancelled before it was ever sent: the reader drops its copy and reports it {@code CANCELED}, as
     * nothing reached the venue.
     */
    public void canceledUnsent(final OrderContext ctx) {
        final OrderContext notice = claim();
        notice.copyFrom(ctx);
        notice.registration = false;
        notice.endsRegistered = true;
        notice.execType = ExecType.CANCEL;
        notice.orderStatus = OrderStatus.CANCELED;
        notice.rejectReason = RejectReason.NULL_VAL;
        commit();
    }

    /** Refuses an order the reader was never given: no slot was free to hold it, so nothing was sent. */
    public void rejectUnheld(
            final long sessionSlot,
            final long clientOidCounter,
            final int clientOidStrategyId,
            final int exchangeId,
            final long securityId,
            final RejectReason reason) {
        final OrderContext notice = claim();
        notice.reset();
        notice.sessionSlot = sessionSlot;
        notice.clientOidCounter = clientOidCounter;
        notice.clientOidStrategyId = clientOidStrategyId;
        notice.exchangeId = exchangeId;
        notice.securityId = securityId;
        notice.execType = ExecType.REJECT;
        notice.orderStatus = OrderStatus.REJECTED;
        notice.rejectReason = reason;
        commit();
    }

    /**
     * Gives up on an order whose outcome the sender could never learn, and nothing has heard of for long: the reader
     * ends it {@code CANCELED} with the fills its events brought, so that it doesn't hold its slots for good.
     */
    public void abandon(final OrderContext ctx) {
        canceledUnsent(ctx);
    }

    /** Reports {@code CANCELED} an order cancelled before it was handed to the reader, so before it was sent. */
    public void canceledUnheld(
            final long sessionSlot,
            final long clientOidCounter,
            final int clientOidStrategyId,
            final int exchangeId,
            final long securityId) {
        final OrderContext notice = claim();
        notice.reset();
        notice.sessionSlot = sessionSlot;
        notice.clientOidCounter = clientOidCounter;
        notice.clientOidStrategyId = clientOidStrategyId;
        notice.exchangeId = exchangeId;
        notice.securityId = securityId;
        notice.execType = ExecType.CANCEL;
        notice.orderStatus = OrderStatus.CANCELED;
        notice.rejectReason = RejectReason.NULL_VAL;
        commit();
    }

    /** Refuses a cancel or modify of an order the sender doesn't hold (it ended, or was never sent). */
    public void rejectUnknownOrder(
            final long sessionSlot,
            final long clientOidCounter,
            final int clientOidStrategyId,
            final int exchangeId,
            final long securityId) {
        final OrderContext notice = claim();
        notice.reset();
        notice.sessionSlot = sessionSlot;
        notice.clientOidCounter = clientOidCounter;
        notice.clientOidStrategyId = clientOidStrategyId;
        notice.exchangeId = exchangeId;
        notice.securityId = securityId;
        notice.execType = ExecType.CANCEL_REJECT;
        notice.orderStatus = OrderStatus.CANCELED;
        notice.rejectReason = RejectReason.UNKNOWN_ORDER;
        commit();
    }

    /** Reports that a cancel or modify of {@code existing} was refused; the order keeps working. */
    public void cancelReject(final OrderContext existing, final RejectReason reason) {
        final OrderContext notice = claim();
        notice.reset();
        identify(existing, notice);
        notice.exchangeId = existing.exchangeId;
        notice.securityId = existing.securityId;
        notice.execType = ExecType.CANCEL_REJECT;
        notice.orderStatus = OrderStatus.CANCELED;
        notice.rejectReason = reason;
        commit();
    }

    /**
     * Tells the reader the venue accepted an amend. The sender sees the amend's result but not the order's fills,
     * which arrive on the reader's stream, so only the reader can acknowledge it correctly; it is handed the venue's
     * numbers, {@link OrderContext#QTY_ABSENT} for any the venue didn't report.
     */
    public void amendAccepted(
            final OrderContext existing, final long newSize, final long venueFillCount, final long venueRemaining) {
        final OrderContext notice = claim();
        notice.reset();
        identify(existing, notice);
        notice.amendAccepted = true;
        notice.originalQty = newSize;
        notice.cumulativeFilledQty = venueFillCount;
        notice.leavesQty = venueRemaining;
        commit();
    }

    /**
     * Tells the reader all an {@link OrderContext#awaitsFinalFill} order will ever fill, as the venue's answer to its
     * submit put it. The fills themselves arrive on the reader's stream, before or after this.
     */
    public void finalFill(final OrderContext existing, final long finalFillQty) {
        final OrderContext notice = claim();
        notice.reset();
        identify(existing, notice);
        notice.finalFillNotice = true;
        notice.finalFillQty = finalFillQty;
        commit();
    }

    /**
     * Tells the reader the venue's id for {@code existing}, from the submit's answer, on a venue whose events can't
     * all be matched by a correlation id: the reader can then look the order up after a reconnect.
     */
    public void venueOrderId(final OrderContext existing) {
        final OrderContext notice = claim();
        notice.reset();
        identify(existing, notice);
        notice.venueOrderIdNotice = true;
        notice.exchangeOrderIdLength = existing.exchangeOrderIdLength;
        System.arraycopy(
                existing.exchangeOrderIdBytes, 0, notice.exchangeOrderIdBytes, 0, existing.exchangeOrderIdLength);
        commit();
    }

    private static void identify(final OrderContext existing, final OrderContext notice) {
        notice.sessionSlot = existing.sessionSlot;
        notice.clientOidCounter = existing.clientOidCounter;
        notice.clientOidStrategyId = existing.clientOidStrategyId;
        notice.correlationIdLength = existing.correlationIdLength;
        System.arraycopy(existing.correlationIdBytes, 0, notice.correlationIdBytes, 0, existing.correlationIdLength);
    }

    private OrderContext claim() {
        int idx;
        while ((idx = this.queue.tryClaim()) < 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted waiting for room in the reader's notice queue");
            }
            Thread.onSpinWait();
        }
        this.claimed = idx;
        return this.queue.indexAt(idx);
    }

    private void commit() {
        this.queue.commit(this.claimed);
    }
}
