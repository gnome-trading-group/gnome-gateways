package group.gnometrading.gateways.orders;

import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.RejectReason;

/**
 * Where a venue's order events land once a venue's parser has worked out what they mean for an order: the orders the
 * events are for, and the reports they turn into. Implemented by whatever holds the orders and publishes their reports
 * (a listing's reader in a session, an account's reader in the gateway), so the venue's rules are written once. Called
 * on that holder's thread; nothing may allocate.
 */
public interface OrderEventSink {

    /** The order registered under the correlation id whose key is {@code correlationKey}, or null if none. */
    OrderContext order(long correlationKey);

    /**
     * Every order held, for venues whose events can only be matched to an order by its terms. Iterating it doesn't
     * allocate but visits every order, so it is for events no id identifies.
     */
    Iterable<OrderContext> orders();

    /** Acknowledges the order ({@code NEW}) with what has filled and what is working; marks it acknowledged. */
    void acknowledge(OrderContext ctx, long cumulativeQty, long leavesQty, long eventNanos);

    /**
     * Reports a fill. The order's cumulative quantity, cost and leaves are already updated to after it.
     *
     * @param filled whether the fill ends the order
     */
    void fill(
            OrderContext ctx,
            long fillQty,
            long fillPrice,
            long fee,
            Liquidity liquidity,
            boolean filled,
            long eventNanos);

    /** Ends the order with a {@code CANCEL} that keeps its fills so far, and releases it. */
    void cancel(OrderContext ctx, long eventNanos);

    /** Ends an order the venue refused after taking it, with a {@code REJECT}, and releases it. */
    void reject(OrderContext ctx, RejectReason reason, long eventNanos);

    /** Releases an order whose last report already ended it, such as a fill to its full size. */
    void release(OrderContext ctx);

    /**
     * The venue's id for {@code ctx}, which the sending thread didn't know, has just been learned from an event and set
     * on it; the sending thread needs it to cancel or amend the order.
     */
    default void venueOrderIdLearned(final OrderContext ctx) {}

    /** When the event being applied was received. */
    long receiveNanos();
}
