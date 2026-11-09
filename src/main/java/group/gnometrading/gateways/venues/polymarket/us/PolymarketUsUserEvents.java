package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.utils.ScaledMath;
import java.nio.charset.StandardCharsets;
import org.agrona.collections.Long2LongHashMap;

/**
 * Polymarket US's order stream and what it means for an order, without I/O: an execution from the private socket, or
 * an order from the REST listings, parsed and then applied to the order it is for through an {@link OrderEventSink}.
 * Not thread-safe: one per reading thread. Nothing here allocates on the event path.
 *
 * <p>Every execution carries the whole order as it stands (state, cumulative quantity, average price, total
 * commission), so each is applied as the order's new state rather than as a delta: a redelivered or out-of-order event
 * changes nothing. Events name orders by the venue's id, which the writer learns only from a submit's answer; an order
 * is taken from the first event it can match instead. A resting order is matched by its {@link PolymarketUsStamps
 * stamp}, an IOC by its terms, oldest first: the venue handles one writer's orders in the order it sent them, and the
 * writer never has two with the same terms and unknown outcomes out at once.
 */
public final class PolymarketUsUserEvents {

    /** Which listing an order is on, which decides what the venue's events for it mean. */
    interface Markets {
        /** Whether the venue's order trades the listing {@code ctx} is on. */
        boolean onListing(OrderContext ctx, PolymarketUsOrderFields order);

        /** A venue (long) price in the terms of the listing {@code ctx} is on. */
        long listingPrice(OrderContext ctx, long venuePrice);
    }

    private static final long NO_KEY = 0;

    private final Logger logger;
    private final PolymarketUsOrderFields fields = new PolymarketUsOrderFields();
    private final byte[] stampBuf = new byte[PolymarketUsStamps.LENGTH];
    // Venue id key to correlation key, for orders an event has already been matched to.
    private final Long2LongHashMap venueKeys;

    /** @param capacity the most orders held at once */
    public PolymarketUsUserEvents(final Logger logger, final int capacity) {
        this.logger = logger;
        this.venueKeys = new Long2LongHashMap(capacity * 2, 0.6f, NO_KEY);
    }

    /**
     * Reads a socket message's top-level object; true if it carried an execution, now ready to {@link #apply}.
     * Subscription acks, heartbeats and snapshots are skipped.
     */
    public boolean readUpdate(final JsonDecoder.JsonObject message) {
        boolean sawExecution = false;
        this.fields.reset();
        while (message.hasNextKey()) {
            try (var entry = message.nextKey()) {
                if (entry.getName().equals("orderSubscriptionUpdate") && !entry.isNull()) {
                    sawExecution = readSubscriptionUpdate(entry);
                }
            }
        }
        return sawExecution;
    }

    private boolean readSubscriptionUpdate(final JsonDecoder.JsonNode update) {
        boolean sawExecution = false;
        try (var obj = update.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("execution") && !entry.isNull()) {
                        readExecution(entry);
                        sawExecution = true;
                    }
                }
            }
        }
        return sawExecution;
    }

    private void readExecution(final JsonDecoder.JsonNode node) {
        try (var execution = node.asObject()) {
            this.fields.readExecution(execution);
        }
    }

    /** Reads one order of a REST listing or lookup, ready to {@link #apply}. */
    public void readListedOrder(final JsonDecoder.JsonObject order) {
        this.fields.reset();
        this.fields.readOrder(order);
    }

    /** The key of the venue id of the order read last. */
    public long venueKey() {
        return OrderKeys.of(this.fields.orderId, this.fields.orderIdLength);
    }

    /** Brings the order the venue reports up to its state: fills first, then how it ended or that it rests. */
    void apply(final OrderEventSink sink, final Markets markets) {
        final PolymarketUsOrderFields order = this.fields;
        if (order.orderIdLength == 0) {
            return;
        }
        final OrderContext ctx = matchOrder(sink, markets, order);
        if (ctx == null) {
            return;
        }
        final long timestampEvent = eventTimestamp(order);
        if (order.cumQuantity > ctx.cumulativeFilledQty && fill(sink, markets, ctx, order, timestampEvent)) {
            return;
        }
        settle(sink, ctx, order, timestampEvent);
    }

    /** Records the venue's id for an order, learned from its submit's answer, so events can find it by that id. */
    public void onVenueOrderId(final OrderContext ctx) {
        this.venueKeys.put(OrderKeys.of(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength), OrderKeys.of(ctx));
    }

    /** Ends the order if the venue says it has, else acknowledges it once it rests. */
    private void settle(
            final OrderEventSink sink,
            final OrderContext ctx,
            final PolymarketUsOrderFields order,
            final long timestampEvent) {
        if (order.state == PolymarketUsOrderFields.STATE_REJECTED && !ctx.acked) {
            logRejected(ctx, order);
            forget(ctx);
            sink.reject(ctx, order.rejectReason(), timestampEvent);
        } else if (order.terminal()) {
            forget(ctx);
            sink.cancel(ctx, timestampEvent);
        } else if (!ctx.acked && order.state == PolymarketUsOrderFields.STATE_WORKING) {
            acknowledge(sink, ctx, order, timestampEvent);
        }
    }

    /** The held order the venue's order is, or null if it isn't one of ours on the listing it trades. */
    private OrderContext matchOrder(
            final OrderEventSink sink, final Markets markets, final PolymarketUsOrderFields order) {
        final long venueKey = OrderKeys.of(order.orderId, order.orderIdLength);
        final long known = this.venueKeys.get(venueKey);
        if (known != NO_KEY) {
            final OrderContext ctx = sink.order(known);
            if (ctx == null) {
                this.venueKeys.remove(venueKey);
                return null;
            }
            return markets.onListing(ctx, order) ? ctx : null;
        }
        final OrderContext ctx = order.immediate ? matchByTerms(sink, markets, order) : matchByStamp(sink, order);
        if (ctx == null || !markets.onListing(ctx, order)) {
            return null;
        }
        if (ctx.exchangeOrderIdLength == 0) {
            ctx.exchangeOrderIdLength = order.orderIdLength;
            System.arraycopy(order.orderId, 0, ctx.exchangeOrderIdBytes, 0, order.orderIdLength);
            sink.venueOrderIdLearned(ctx);
        }
        this.venueKeys.put(venueKey, OrderKeys.of(ctx));
        return ctx;
    }

    private OrderContext matchByStamp(final OrderEventSink sink, final PolymarketUsOrderFields order) {
        if (order.goodTillMillis == Rfc3339.INVALID || !PolymarketUsStamps.isStamp(order.goodTillMillis)) {
            return null;
        }
        final int length = PolymarketUsStamps.write(order.goodTillMillis, this.stampBuf, 0);
        return sink.order(OrderKeys.of(this.stampBuf, length));
    }

    /** The oldest held IOC on the order's listing, side and price that no event has been matched to yet. */
    private static OrderContext matchByTerms(
            final OrderEventSink sink, final Markets markets, final PolymarketUsOrderFields order) {
        final Side side = order.intent == PolymarketUsOrderFields.INTENT_BUY_LONG
                        || order.intent == PolymarketUsOrderFields.INTENT_BUY_SHORT
                ? Side.Bid
                : Side.Ask;
        OrderContext oldest = null;
        for (final OrderContext ctx : sink.orders()) {
            if (unmatchedWithTerms(ctx, side, order)
                    && markets.onListing(ctx, order)
                    && (oldest == null || ctx.clientOidCounter < oldest.clientOidCounter)) {
                oldest = ctx;
            }
        }
        return oldest;
    }

    private static boolean unmatchedWithTerms(
            final OrderContext ctx, final Side side, final PolymarketUsOrderFields order) {
        return ctx.exchangeOrderIdLength == 0
                && PolymarketUsOrderCodec.isImmediate(ctx)
                && ctx.side == side
                && ctx.venuePrice == order.price
                // The venue may cut an order's quantity (to the lot, or to what the balance covers), never raise it.
                && order.quantity <= ctx.originalQty;
    }

    private void acknowledge(
            final OrderEventSink sink,
            final OrderContext ctx,
            final PolymarketUsOrderFields order,
            final long timestampEvent) {
        if (order.quantity != OrderContext.QTY_ABSENT && order.quantity < ctx.originalQty) {
            // The venue cuts a BUY down to what the account can pay for; the order then ends at the venue's size.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket US order %s rests for %d of %d",
                    new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                    order.quantity,
                    ctx.originalQty);
        }
        sink.acknowledge(ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, timestampEvent);
    }

    /**
     * Reports the fills that brought the order to the venue's cumulative quantity. A single new fill is priced at
     * its own price; several at once (after a reconnect) at what the venue's average says they cost together.
     *
     * @return whether the order is done and released
     */
    private boolean fill(
            final OrderEventSink sink,
            final Markets markets,
            final OrderContext ctx,
            final PolymarketUsOrderFields order,
            final long timestampEvent) {
        final long fillQty = order.cumQuantity - ctx.cumulativeFilledQty;
        final boolean single = isSingleFill(order, fillQty);
        final long venueNotional = single
                ? ScaledMath.multiplyDivide(order.lastPx, fillQty, Statics.SIZE_SCALING_FACTOR)
                : ScaledMath.multiplyDivide(order.avgPx, order.cumQuantity, Statics.SIZE_SCALING_FACTOR)
                        - ctx.cumulativeCost;
        final long venuePrice =
                single ? order.lastPx : ScaledMath.multiplyDivide(venueNotional, Statics.SIZE_SCALING_FACTOR, fillQty);
        final long fee = order.sawCommissionTotal ? order.commissionTotal - ctx.cumulativeFees : 0;
        final Liquidity liquidity =
                single ? (order.aggressor ? Liquidity.TAKER : Liquidity.MAKER) : liquidityFromFee(fee);

        if (!ctx.acked) {
            // An order that matched on arrival may never be reported as resting; acknowledge it first.
            sink.acknowledge(ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, timestampEvent);
        }
        ctx.cumulativeFilledQty = order.cumQuantity;
        ctx.cumulativeCost += venueNotional;
        ctx.cumulativeFees += fee;
        ctx.leavesQty = Math.max(0, ctx.originalQty - ctx.cumulativeFilledQty);
        final boolean fullyFilled = ctx.cumulativeFilledQty >= ctx.originalQty;
        sink.fill(ctx, fillQty, markets.listingPrice(ctx, venuePrice), fee, liquidity, fullyFilled, timestampEvent);
        if (fullyFilled) {
            forget(ctx);
            sink.release(ctx);
            return true;
        }
        return false;
    }

    /**
     * Takers pay and makers are paid a rebate, so a fill reported without its execution (after a reconnect) is told
     * apart by its fee. A fee rounded to nothing says neither, and several fills of both kinds net out to one sign.
     */
    private static Liquidity liquidityFromFee(final long fee) {
        if (fee > 0) {
            return Liquidity.TAKER;
        }
        return fee < 0 ? Liquidity.MAKER : Liquidity.NULL_VAL;
    }

    /** Whether the event is the one fill that took the order to its cumulative quantity, at its own price. */
    private static boolean isSingleFill(final PolymarketUsOrderFields order, final long fillQty) {
        return order.execType == PolymarketUsOrderFields.EXEC_FILL && order.lastShares == fillQty && order.lastPx > 0;
    }

    private void forget(final OrderContext ctx) {
        if (ctx.exchangeOrderIdLength > 0) {
            this.venueKeys.remove(OrderKeys.of(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength));
        }
    }

    /** Off the hot path: a rejection allocates its log line. */
    private void logRejected(final OrderContext ctx, final PolymarketUsOrderFields order) {
        this.logger.logf(
                LogMessage.ORDER_REJECTED_BY_VENUE,
                "Polymarket US rejected %s (venue id %s) after taking it: %s %s",
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII),
                new String(order.orderRejectReason, 0, order.orderRejectReasonLength, StandardCharsets.US_ASCII),
                new String(order.text, 0, order.textLength, StandardCharsets.UTF_8));
    }

    private static long eventTimestamp(final PolymarketUsOrderFields order) {
        return order.transactTimeNanos == Rfc3339.INVALID
                ? OrderExecutionReportEncoder.timestampEventNullValue()
                : order.transactTimeNanos;
    }
}
