package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.fee.FeeSchedule;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ScaledMath;

/**
 * Kalshi's order events and what they mean for an order, without I/O: a {@code user_order} update from the socket, or
 * an order from the REST listing (which carries the same fields), parsed and then applied to the order it is for
 * through an {@link OrderEventSink}. Every market's events are read the same way, so one instance serves a listing's
 * reader or an account's. Not thread-safe: one per reading thread. Nothing here allocates.
 *
 * <p>Kalshi reports an order's cumulative fill count and cost rather than each execution, so what a fill added is the
 * difference from what was last reported.
 */
public final class KalshiUserEvents {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int STATUS_RESTING = 1;
    private static final int STATUS_EXECUTED = 2;
    private static final int STATUS_CANCELED = 3;

    private final byte[] orderIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    // Kalshi's own id for the order, which cancels and amends name it by.
    private final byte[] venueIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int venueIdLength;
    private int orderIdLength;
    private int statusFlag;
    private long fillCount;
    private long remainingCount;
    private long takerFillCost;
    private long makerFillCost;
    private long takerFees;
    private long makerFees;
    private boolean feesReported;
    private long timestampMs;

    /**
     * Reads a socket message; true if it was a {@code user_order} update, now ready to {@link #apply}.
     *
     * @param message the message's top-level object
     */
    public boolean readUpdate(final JsonDecoder.JsonObject message) {
        boolean userOrder = false;
        reset();
        while (message.hasNextKey()) {
            try (var entry = message.nextKey()) {
                final GnomeString name = entry.getName();
                if (name.equals("type")) {
                    userOrder = entry.asString().equals("user_order");
                } else if (name.equals("msg") && userOrder) {
                    try (var msg = entry.asObject()) {
                        readFields(msg);
                    }
                }
            }
        }
        return userOrder;
    }

    /** Reads one order of a REST listing, ready to {@link #apply}. */
    public void readListedOrder(final JsonDecoder.JsonObject order) {
        reset();
        readFields(order);
    }

    /** The key of the correlation id (the client order id) the event read last is for. */
    public long correlationKey() {
        return OrderKeys.of(this.orderIdBytes, this.orderIdLength);
    }

    /**
     * Applies the event read last to its order, if {@code sink} holds it: what was already reported is reported again
     * only by what changed.
     *
     * @param fees the order's listing's fee schedules, for an event that doesn't carry Kalshi's own fees
     */
    public void apply(final OrderEventSink sink, final FeeTimeline fees, final long nowNanos) {
        final OrderContext ctx = sink.order(correlationKey());
        if (ctx == null) {
            return;
        }
        if (ctx.exchangeOrderIdLength == 0 && this.venueIdLength > 0) {
            ctx.exchangeOrderIdLength = this.venueIdLength;
            System.arraycopy(this.venueIdBytes, 0, ctx.exchangeOrderIdBytes, 0, this.venueIdLength);
            sink.venueOrderIdLearned(ctx);
        }
        if (this.statusFlag == STATUS_CANCELED) {
            applyCancel(sink, ctx, fees, nowNanos);
        } else if (this.fillCount > ctx.cumulativeFilledQty) {
            applyFill(sink, ctx, fees, nowNanos);
        } else if (this.statusFlag == STATUS_EXECUTED) {
            // Executed with nothing new filled, e.g. amended down to what had already filled: nothing more will
            // fill, and without a final report the order would stay working in the OMS for good.
            sink.cancel(ctx, eventNanos());
        } else if (this.statusFlag == STATUS_RESTING && !ctx.acked) {
            // Only the first `resting` update acknowledges the order. Any later one, such as one following
            // an amend, must not become a second NEW: amends are acknowledged from the writer's notice.
            sink.acknowledge(ctx, 0, ctx.originalQty, eventNanos());
        }
    }

    private void applyCancel(
            final OrderEventSink sink, final OrderContext ctx, final FeeTimeline fees, final long nowNanos) {
        // An order that fills and is cancelled together, most often an IOC that partly filled, reports
        // both in one update. The fills must be reported first or the OMS never learns it holds them.
        if (this.fillCount > ctx.cumulativeFilledQty) {
            final boolean fillsCompleteOrder = this.fillCount - ctx.cumulativeFilledQty >= ctx.leavesQty;
            fill(sink, ctx, fees, nowNanos, fillsCompleteOrder, false, fillsCompleteOrder ? 0 : workingAtCancel(ctx));
            if (fillsCompleteOrder) {
                sink.release(ctx);
                return;
            }
        }
        sink.cancel(ctx, eventNanos());
    }

    private void applyFill(
            final OrderEventSink sink, final OrderContext ctx, final FeeTimeline fees, final long nowNanos) {
        // An update that leaves out remaining_count_fp says nothing about whether the order is done.
        final boolean fullyFilled = this.statusFlag == STATUS_EXECUTED
                || (this.remainingCount != OrderContext.QTY_ABSENT && this.remainingCount <= 0);
        fill(sink, ctx, fees, nowNanos, fullyFilled, !fullyFilled, fullyFilled ? 0 : workingAfter(ctx));
        if (fullyFilled) {
            sink.release(ctx);
        }
    }

    /** Reports the fill that moved the order to the event's fill count, leaving {@code leavesAfter} working. */
    private void fill(
            final OrderEventSink sink,
            final OrderContext ctx,
            final FeeTimeline fees,
            final long nowNanos,
            final boolean fullyFilled,
            final boolean stillWorking,
            final long leavesAfter) {
        final long fillDelta = this.fillCount - ctx.cumulativeFilledQty;
        final long totalCost = this.takerFillCost + this.makerFillCost;
        final long costDelta = totalCost - ctx.cumulativeCost;
        final long fillPrice = listingPrice(
                ctx, fillDelta > 0 ? ScaledMath.multiplyDivide(costDelta, Statics.SIZE_SCALING_FACTOR, fillDelta) : 0);

        // An order that fills on arrival has no `resting` update to acknowledge it. Without a NEW
        // the OMS slot stays PENDING_NEW, so acknowledge it first if it is still working.
        if (!ctx.acked && stillWorking) {
            sink.acknowledge(ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, eventNanos());
        }
        ctx.acked = true;

        final long makerCostDelta = this.makerFillCost - ctx.cumulativeMakerCost;
        final Liquidity liquidity = classifyLiquidity(costDelta, makerCostDelta);
        final long fee = fillFee(ctx, fees, nowNanos, fillPrice, fillDelta, costDelta, makerCostDelta);

        ctx.cumulativeFilledQty = this.fillCount;
        ctx.cumulativeCost = totalCost;
        ctx.cumulativeMakerCost = this.makerFillCost;
        ctx.leavesQty = leavesAfter;
        sink.fill(ctx, fillDelta, fillPrice, fee, liquidity, fullyFilled, eventNanos());
    }

    /**
     * Kalshi prices a fill at what the side it bought cost. A bid on either listing buys that listing's own side; an
     * ask buys the other one, so its price on the listing is the complement. Confirmed on the demo exchange.
     */
    private static long listingPrice(final OrderContext ctx, final long purchasedSidePrice) {
        if (ctx.side == Side.Ask && purchasedSidePrice > 0) {
            return Statics.PRICE_SCALING_FACTOR - purchasedSidePrice;
        }
        return purchasedSidePrice;
    }

    /**
     * What was still working when a cancel arrived together with fills. The update's own remaining
     * count is already zero, so this comes from our working quantity less the new fills. The OMS
     * removes it from position leaves on the CANCEL that follows, so it must not be zero here.
     */
    private long workingAtCancel(final OrderContext ctx) {
        if (ctx.amendFillCount != OrderContext.QTY_ABSENT && this.fillCount <= ctx.amendFillCount) {
            return ctx.leavesQty;
        }
        return Math.max(0, ctx.leavesQty - (this.fillCount - ctx.cumulativeFilledQty));
    }

    /**
     * A fill the venue had already counted when it accepted an amend reports the pre-amend
     * remaining quantity, so it cannot be trusted. The amend's acknowledgement already netted that
     * fill out of the working quantity, which therefore stands; later fills report post-amend numbers.
     */
    private long workingAfter(final OrderContext ctx) {
        if (ctx.amendFillCount != OrderContext.QTY_ABSENT && this.fillCount <= ctx.amendFillCount) {
            return ctx.leavesQty;
        }
        if (this.remainingCount == OrderContext.QTY_ABSENT) {
            return Math.max(0, ctx.leavesQty - (this.fillCount - ctx.cumulativeFilledQty));
        }
        return this.remainingCount;
    }

    /**
     * Kalshi reports cumulative maker and taker cost per order rather than per execution, so a single
     * update can cover both. Such a mixed fill is reported as unknown rather than attributed to one side.
     */
    private static Liquidity classifyLiquidity(final long costDelta, final long makerCostDelta) {
        if (costDelta <= 0) {
            return Liquidity.NULL_VAL;
        }
        if (makerCostDelta >= costDelta) {
            return Liquidity.MAKER;
        }
        return makerCostDelta <= 0 ? Liquidity.TAKER : Liquidity.NULL_VAL;
    }

    /**
     * Fee for this fill, as a delta against the order's running total. Kalshi reports the fees it
     * actually charged, split by liquidity, so those are used whenever present. The listing's fee
     * schedule is only a fallback for messages without them, and splits the fill by which cost
     * bucket moved.
     */
    private long fillFee(
            final OrderContext ctx,
            final FeeTimeline fees,
            final long nowNanos,
            final long fillPrice,
            final long fillDelta,
            final long costDelta,
            final long makerCostDelta) {
        if (this.feesReported) {
            final long totalFees = this.takerFees + this.makerFees;
            final long fee = totalFees - ctx.cumulativeFees;
            ctx.cumulativeFees = totalFees;
            return fee;
        }
        final FeeSchedule schedule = fees.at(nowNanos);
        if (schedule == null) {
            return 0;
        }
        final double makerShare = costDelta > 0 ? Math.min(1.0, (double) makerCostDelta / costDelta) : 0.0;
        final long makerQty = (long) (fillDelta * makerShare);
        final boolean buy = ctx.side == Side.Bid;
        final long fee = schedule.fee(buy, fillPrice, makerQty, true, ctx.feeState)
                + schedule.fee(buy, fillPrice, fillDelta - makerQty, false, ctx.feeState);
        ctx.cumulativeFees += fee;
        return fee;
    }

    private long eventNanos() {
        return this.timestampMs * NANOS_PER_MILLI;
    }

    private void readFields(final JsonDecoder.JsonObject msg) {
        while (msg.hasNextKey()) {
            try (var field = msg.nextKey()) {
                final GnomeString name = field.getName();
                // Orders are registered under the client order id the writer sent, before Kalshi's
                // order_id is known.
                if (name.equals("client_order_id")) {
                    copyOrderId(field.asString());
                } else if (name.equals("order_id")) {
                    this.venueIdLength = copy(field.asString(), this.venueIdBytes);
                } else if (name.equals("status")) {
                    readStatus(field.asString());
                } else if (name.equals("fill_count_fp")) {
                    this.fillCount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("remaining_count_fp")) {
                    this.remainingCount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("last_updated_ts_ms")) {
                    this.timestampMs = field.asLong();
                } else if (name.equals("last_update_time")) {
                    // The REST order list has only this; updates carry both, with last_updated_ts_ms after it.
                    final long nanos = Rfc3339.toEpochNanos(field.asString());
                    if (nanos != Rfc3339.INVALID) {
                        this.timestampMs = nanos / NANOS_PER_MILLI;
                    }
                } else {
                    readDollarField(name, field);
                }
            }
        }
    }

    private void readDollarField(final GnomeString name, final JsonDecoder.JsonNode field) {
        if (name.equals("taker_fill_cost_dollars")) {
            this.takerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("maker_fill_cost_dollars")) {
            this.makerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("taker_fees_dollars")) {
            this.takerFees = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            this.feesReported = true;
        } else if (name.equals("maker_fees_dollars")) {
            this.makerFees = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            this.feesReported = true;
        }
    }

    private void readStatus(final GnomeString val) {
        if (val.equals("resting")) {
            this.statusFlag = STATUS_RESTING;
        } else if (val.equals("executed")) {
            this.statusFlag = STATUS_EXECUTED;
        } else if (val.equals("canceled")) {
            this.statusFlag = STATUS_CANCELED;
        }
    }

    private static int copy(final GnomeString val, final byte[] dest) {
        final int length = Math.min(val.length(), dest.length);
        for (int i = 0; i < length; i++) {
            dest[i] = val.byteAt(i);
        }
        return length;
    }

    private void copyOrderId(final GnomeString val) {
        this.orderIdLength = Math.min(val.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < this.orderIdLength; i++) {
            this.orderIdBytes[i] = val.byteAt(i);
        }
    }

    private void reset() {
        this.orderIdLength = 0;
        this.venueIdLength = 0;
        this.statusFlag = 0;
        this.fillCount = 0;
        this.remainingCount = OrderContext.QTY_ABSENT;
        this.takerFillCost = 0;
        this.makerFillCost = 0;
        this.takerFees = 0;
        this.makerFees = 0;
        this.feesReported = false;
        this.timestampMs = OrderExecutionReportEncoder.timestampEventNullValue();
    }
}
