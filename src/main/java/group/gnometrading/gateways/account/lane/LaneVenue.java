package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.RejectReason;

/**
 * What an {@link AccountLane} needs from a venue: building, signing and reading its requests, on top of the venue's
 * codec, for any market on the account. Every call is on the lane's thread and may not allocate.
 *
 * @param <M> the venue's market
 */
public interface LaneVenue<M> {

    /** The market an order's listing trades, or null if it isn't loaded. */
    M market(int exchangeId, long securityId);

    /**
     * Prepares a new order: its correlation id and venue fields on {@code ctx}, its submit body into {@code body}.
     *
     * @return the body's length, or -1 to refuse it unsent, with the reason in {@code ctx.rejectReason}
     */
    int prepare(LaneCommand command, M market, OrderContext ctx, byte[] body, long nowMillis);

    /** Whether the venue deduplicates a resent submit; if not, a submit with no clear answer is only looked up. */
    boolean resendsAreSafe();

    /** Whether the venue's id for the order is known, which cancels and amends need. */
    boolean venueIdKnown(OrderContext ctx);

    /**
     * Whether the venue can amend this working order in place to {@code price} and {@code size} (OMS terms); if not,
     * the amend is refused and the session cancels and resubmits.
     */
    boolean canAmend(OrderContext ctx, M market, long price, long size);

    /** Whether the venue can look this order up; one it can't is left to its events, and given up on sooner. */
    default boolean canLookUp(final OrderContext ctx) {
        return true;
    }

    /** A submit's method and path. */
    void submitShape(OrderContext ctx, M market, RequestShape shape);

    /** A lookup's method and path; false if the order can't be looked up (it is then left to the venue's events). */
    boolean lookupShape(OrderContext ctx, M market, RequestShape shape);

    void cancelShape(OrderContext ctx, M market, RequestShape shape);

    void amendShape(OrderContext ctx, M market, RequestShape shape);

    /** Writes a submit's headers, signed now, and its prepared body. */
    void writeSubmit(
            OrderContext ctx,
            RequestShape shape,
            byte[] body,
            int bodyLength,
            HTTPRequestWriter request,
            long nowMillis);

    void writeLookup(OrderContext ctx, M market, RequestShape shape, HTTPRequestWriter request, long nowMillis);

    void writeCancel(OrderContext ctx, M market, RequestShape shape, HTTPRequestWriter request, long nowMillis);

    void writeAmend(
            OrderContext ctx,
            M market,
            long price,
            long size,
            boolean postOnly,
            RequestShape shape,
            HTTPRequestWriter request,
            long nowMillis);

    /** A submit's answer; a refusal's reason into {@code ctx.rejectReason}, an acceptance's venue id onto {@code ctx}. */
    SubmitResult readSubmit(OrderContext ctx, HTTPResponse response);

    /** A lookup's answer; a found order's venue id onto {@code ctx}. */
    LookupResult readLookup(OrderContext ctx, HTTPResponse response);

    CancelResult readCancel(OrderContext ctx, HTTPResponse response);

    /**
     * An amend's answer: whether it was accepted, and if so what the venue says filled and rests, into {@code out}.
     *
     * @param newSize the size the amend asked for
     * @return the refusal's reason, or null if accepted
     */
    RejectReason readAmend(OrderContext ctx, long newSize, HTTPResponse response, AmendAnswer out);

    /** For an order that ends on the venue's word: all it will ever fill, as its accepted submit said. */
    default long finalFillQty(final OrderContext ctx) {
        return OrderContext.QTY_ABSENT;
    }

    /** Whether the account reader matches the venue's events by the venue's id, so needs it once known. */
    default boolean readerNeedsVenueId() {
        return false;
    }

    /** The order has ended: anything the venue kept for it can go. */
    default void onReleased(final OrderContext ctx) {}

    // ----- batches: several submits, or several cancels, in one request -----

    /** Returned by the batch readers when every entry has its own result. */
    int NO_FAILED_ENTRY = -1;

    /** The most submits, or cancels, one request may carry; 1 if the venue takes them only one at a time. */
    default int maxBatch() {
        return 1;
    }

    /** A batch of submits' method and path. */
    default void batchSubmitShape(final RequestShape shape) {
        throw new UnsupportedOperationException("The venue takes submits one at a time");
    }

    /** Writes a batch of submits' headers, signed now, and a body carrying each order's prepared submit in order. */
    default void writeBatchSubmit(
            final OrderContext[] ctxs,
            final byte[][] bodies,
            final int[] bodyLengths,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        throw new UnsupportedOperationException("The venue takes submits one at a time");
    }

    /**
     * A batch of submits' answer: each entry's result into {@code results}, as {@link #readSubmit} would give it, with an
     * accepted entry's venue id onto its context.
     *
     * @return {@link #NO_FAILED_ENTRY}; or the index of the one entry the venue refused the whole batch for, its reason
     *     in that context's {@code rejectReason}: nothing else in the batch was created, and the rest may go again
     */
    default int readBatchSubmit(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final SubmitResult[] results) {
        throw new UnsupportedOperationException("The venue takes submits one at a time");
    }

    /** A batch of cancels' method and path. */
    default void batchCancelShape(final RequestShape shape) {
        throw new UnsupportedOperationException("The venue takes cancels one at a time");
    }

    /** Writes a batch of cancels, of orders whose venue ids are known, signed now. */
    default void writeBatchCancel(
            final OrderContext[] ctxs,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        throw new UnsupportedOperationException("The venue takes cancels one at a time");
    }

    /**
     * A batch of cancels' answer, each entry's result into {@code results}.
     *
     * @return as {@link #readBatchSubmit}: the one entry the whole batch was refused for, or {@link #NO_FAILED_ENTRY}
     */
    default int readBatchCancel(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final CancelResult[] results) {
        throw new UnsupportedOperationException("The venue takes cancels one at a time");
    }

    /** What an accepted amend's answer says, in the venue's numbers. */
    final class AmendAnswer {
        public long fillCount;
        public long remaining;
    }
}
