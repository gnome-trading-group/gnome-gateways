package group.gnometrading.gateways.orders;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import java.util.Collection;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;

/**
 * The orders a reader holds and the reports their events turn into: what the sending thread hands over (each order
 * before it is sent, then the notices only it sees) and what the venue's events say, applied in one place on the
 * reader's thread, so every order gets each report once. Shared by a listing's reader in a session and an account's
 * reader in the gateway, which differ only in where reports and released orders go. Allocates nothing.
 */
public final class OrderReports implements OrderEventSink {

    /** Where reports and released orders go. */
    public interface Output {
        /** A report for {@code ctx}'s order, valid only during the call. */
        void publish(OrderContext ctx, OrderExecutionReport report);

        /** The order has ended: the sending thread may forget it. */
        void released(OrderContext ctx);

        /** An event gave the venue's id for {@code ctx}, which the sending thread may not know. */
        default void venueOrderId(final OrderContext ctx) {}
    }

    /** Told the venue's id for an order held under {@code key}, for venues whose events name orders by it. */
    public interface VenueIdListener {
        VenueIdListener NONE = (ctx, key) -> {};

        void onVenueOrderId(OrderContext ctx, long key);
    }

    private final EpochNanoClock clock;
    private final Output output;
    private final Long2ObjectHashMap<OrderContext> orderContexts;
    private final OrderContext[] contextPool;
    private int contextPoolHead;
    private final OrderExecutionReport execReport = new OrderExecutionReport();
    private VenueIdListener venueIdListener = VenueIdListener.NONE;
    private long receiveNanos;

    public OrderReports(final int poolSize, final EpochNanoClock clock, final Output output) {
        this.clock = clock;
        this.output = output;
        this.orderContexts = new Long2ObjectHashMap<>(poolSize * 2, 0.6f);
        this.contextPool = new OrderContext[poolSize];
        for (int i = 0; i < poolSize; i++) {
            this.contextPool[i] = new OrderContext();
        }
        this.contextPoolHead = poolSize;
        this.execReport.wrap(this.execReport.buffer);
    }

    public void venueIdListener(final VenueIdListener listener) {
        this.venueIdListener = listener;
    }

    /** When the frame whose events are being applied arrived; reports carry it. */
    public void receivedAt(final long nanos) {
        this.receiveNanos = nanos;
    }

    /** An order the sending thread is about to send. */
    public void register(final OrderContext src) {
        if (this.contextPoolHead <= 0) {
            throw new IllegalStateException("Order context pool exhausted");
        }
        final OrderContext ctx = this.contextPool[--this.contextPoolHead];
        ctx.copyFrom(src);
        ctx.registration = false;
        this.orderContexts.put(OrderKeys.of(ctx.correlationIdBytes, ctx.correlationIdLength), ctx);
    }

    /**
     * Something only the sending thread saw: a new order to hold (where orders and notices share a queue), an amend or final fill to apply, the venue's id, or a report of its own
     * (a reject, a cancel of an order never sent, a refused cancel), which ends any order it names that is held here.
     */
    public void onNotice(final OrderContext src) {
        if (src.registration) {
            register(src);
            return;
        }
        if (src.amendAccepted) {
            applyAcceptedAmend(src);
            return;
        }
        if (src.finalFillNotice) {
            applyFinalFill(src);
            return;
        }
        if (src.venueOrderIdNotice) {
            applyVenueOrderId(src);
            return;
        }
        onSenderReport(src);
    }

    /** A report the sender made itself, which ends any order it names that is held here. */
    private void onSenderReport(final OrderContext src) {
        if (src.execType == ExecType.CANCEL && src.endsRegistered) {
            // Ended by the sender, with what the venue's events have filled of it; dropped if they ended it already.
            final long key = OrderKeys.of(src.correlationIdBytes, src.correlationIdLength);
            final OrderContext held = this.orderContexts.get(key);
            if (held != null) {
                publishCanceled(held, key, OrderExecutionReportEncoder.timestampEventNullValue());
            }
            return;
        }
        if (src.execType == ExecType.REJECT || src.execType == ExecType.CANCEL) {
            // The sender registers an order before sending it, and frees its own copy when it ends it. One the venue's
            // events have ended already has had its last report.
            final boolean held = discard(OrderKeys.of(src.correlationIdBytes, src.correlationIdLength));
            if (src.endsRegistered && !held) {
                return;
            }
        }
        prepareHeader(src);
        this.execReport.encoder.execType(src.execType);
        this.execReport.encoder.orderStatus(src.orderStatus);
        this.execReport.encoder.rejectReason(src.rejectReason != null ? src.rejectReason : RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.cumulativeQty(0);
        this.execReport.encoder.leavesQty(0);
        this.execReport.encoder.timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue());
        this.execReport.encoder.timestampRecv(this.clock.nanoTime());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.output.publish(src, this.execReport);
    }

    /**
     * Acknowledges an amend the venue accepted, using this thread's view of the order's fills.
     *
     * <p>The venue's numbers are a snapshot taken when it processed the amend, but fills on the stream can land on
     * either side of that snapshot. Fills this reader has already seen beyond the snapshot are taken off the venue's
     * remaining quantity; fills still in flight are already counted in it, and {@link OrderContext#amendFillCount}
     * lets the venue reader recognise them. An order that has finished in the meantime needs no acknowledgement: its
     * terminal report already settled the OMS's state.
     */
    private void applyAcceptedAmend(final OrderContext src) {
        final OrderContext ctx = this.orderContexts.get(OrderKeys.of(src.correlationIdBytes, src.correlationIdLength));
        if (ctx == null) {
            return;
        }
        final long venueFillCount =
                src.cumulativeFilledQty == OrderContext.QTY_ABSENT ? ctx.cumulativeFilledQty : src.cumulativeFilledQty;
        final long leaves;
        if (src.leavesQty == OrderContext.QTY_ABSENT) {
            leaves = src.originalQty - ctx.cumulativeFilledQty;
        } else {
            leaves = src.leavesQty - Math.max(0, ctx.cumulativeFilledQty - venueFillCount);
        }

        ctx.originalQty = src.originalQty;
        ctx.leavesQty = Math.max(0, leaves);
        ctx.amendFillCount = venueFillCount;
        publishNew(
                ctx,
                ctx.cumulativeFilledQty,
                ctx.leavesQty,
                OrderExecutionReportEncoder.timestampEventNullValue(),
                this.clock.nanoTime());
    }

    /**
     * Records the venue's total for the order, and ends it if its fills already reached it. Without a total (the
     * venue didn't say), the order goes back to ending at its own size or on the venue's word, like any other.
     */
    private void applyFinalFill(final OrderContext src) {
        final long key = OrderKeys.of(src.correlationIdBytes, src.correlationIdLength);
        final OrderContext ctx = this.orderContexts.get(key);
        if (ctx == null) {
            return;
        }
        if (src.finalFillQty == OrderContext.QTY_ABSENT) {
            ctx.awaitsFinalFill = false;
            if (ctx.cumulativeFilledQty >= ctx.originalQty) {
                publishFilledWithoutNewFill(ctx, key);
            }
            return;
        }
        ctx.finalFillQty = src.finalFillQty;
        if (ctx.cumulativeFilledQty < ctx.finalFillQty) {
            return;
        }
        if (ctx.cumulativeFilledQty >= ctx.originalQty) {
            publishFilledWithoutNewFill(ctx, key);
        } else {
            publishCanceled(ctx, key, OrderExecutionReportEncoder.timestampEventNullValue());
        }
    }

    /** Records the venue's id for an order held here, which may have finished meanwhile. */
    private void applyVenueOrderId(final OrderContext src) {
        final long key = OrderKeys.of(src.correlationIdBytes, src.correlationIdLength);
        final OrderContext ctx = this.orderContexts.get(key);
        if (ctx == null) {
            return;
        }
        ctx.exchangeOrderIdLength = src.exchangeOrderIdLength;
        System.arraycopy(src.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdBytes, 0, src.exchangeOrderIdLength);
        this.venueIdListener.onVenueOrderId(ctx, key);
    }

    /**
     * Ends an order its fills already completed with a {@link ExecType#FILL} that adds nothing, which the OMS reads
     * as the order filled: its fills were reported as partial while the venue's total was still unknown.
     */
    private void publishFilledWithoutNewFill(final OrderContext ctx, final long key) {
        prepareHeader(ctx);
        this.execReport.encoder.execType(ExecType.FILL);
        this.execReport.encoder.orderStatus(OrderStatus.FILLED);
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(0);
        this.execReport.encoder.fillPrice(0);
        this.execReport.encoder.fee(0);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(0);
        this.execReport.encoder.timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue());
        this.execReport.encoder.timestampRecv(this.clock.nanoTime());
        this.output.publish(ctx, this.execReport);
        release(key);
    }

    /** Ends the order with a {@link ExecType#CANCEL} that keeps its fills so far, and releases it. */
    public void publishCanceled(final OrderContext ctx, final long key, final long timestampEvent) {
        prepareHeader(ctx);
        this.execReport.encoder.execType(ExecType.CANCEL);
        this.execReport.encoder.orderStatus(OrderStatus.CANCELED);
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(0);
        this.execReport.encoder.timestampEvent(timestampEvent);
        this.execReport.encoder.timestampRecv(this.receiveNanos);
        this.output.publish(ctx, this.execReport);
        release(key);
    }

    /**
     * Ends an order the venue refused after taking it, before acknowledging it, with a {@link ExecType#REJECT}, and
     * releases it. On venues that validate an order only after assigning it an id.
     */
    public void publishRejected(
            final OrderContext ctx, final long key, final long timestampEvent, final RejectReason reason) {
        prepareHeader(ctx);
        this.execReport.encoder.execType(ExecType.REJECT);
        this.execReport.encoder.orderStatus(OrderStatus.REJECTED);
        this.execReport.encoder.rejectReason(reason);
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.execReport.encoder.cumulativeQty(0);
        this.execReport.encoder.leavesQty(0);
        this.execReport.encoder.timestampEvent(timestampEvent);
        this.execReport.encoder.timestampRecv(this.receiveNanos);
        this.output.publish(ctx, this.execReport);
        release(key);
    }

    /** Publishes the order's {@link ExecType#NEW} acknowledgement and records that it was sent. */
    public void publishNew(
            final OrderContext ctx,
            final long cumulativeQty,
            final long leavesQty,
            final long timestampEvent,
            final long timestampRecv) {
        prepareHeader(ctx);
        this.execReport.encoder.execType(ExecType.NEW);
        this.execReport.encoder.orderStatus(OrderStatus.NEW);
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.execReport.encoder.cumulativeQty(cumulativeQty);
        this.execReport.encoder.leavesQty(leavesQty);
        this.execReport.encoder.timestampEvent(timestampEvent);
        this.execReport.encoder.timestampRecv(timestampRecv);
        this.output.publish(ctx, this.execReport);
        ctx.acked = true;
    }

    @Override
    public OrderContext order(final long correlationKey) {
        return this.orderContexts.get(correlationKey);
    }

    @Override
    public Iterable<OrderContext> orders() {
        return this.orderContexts.values();
    }

    @Override
    public void acknowledge(
            final OrderContext ctx, final long cumulativeQty, final long leavesQty, final long eventNanos) {
        publishNew(ctx, cumulativeQty, leavesQty, eventNanos, this.receiveNanos);
    }

    @Override
    public void fill(
            final OrderContext ctx,
            final long fillQty,
            final long fillPrice,
            final long fee,
            final Liquidity liquidity,
            final boolean filled,
            final long eventNanos) {
        prepareHeader(ctx);
        this.execReport.encoder.execType(filled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(filled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.filledQty(fillQty);
        this.execReport.encoder.fillPrice(fillPrice);
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        this.execReport.encoder.timestampEvent(eventNanos);
        this.execReport.encoder.timestampRecv(this.receiveNanos);
        this.execReport.encoder.fee(fee);
        this.execReport.encoder.liquidity(liquidity);
        this.output.publish(ctx, this.execReport);
    }

    @Override
    public void cancel(final OrderContext ctx, final long eventNanos) {
        publishCanceled(ctx, OrderKeys.of(ctx), eventNanos);
    }

    @Override
    public void reject(final OrderContext ctx, final RejectReason reason, final long eventNanos) {
        publishRejected(ctx, OrderKeys.of(ctx), eventNanos, reason);
    }

    @Override
    public void release(final OrderContext ctx) {
        release(OrderKeys.of(ctx));
    }

    /** Ends holding an order whose last report has gone out, and tells the sending thread. */
    public void release(final long key) {
        final OrderContext ctx = this.orderContexts.remove(key);
        if (ctx == null) {
            return;
        }
        this.output.released(ctx);
        ctx.reset();
        this.contextPool[this.contextPoolHead++] = ctx;
    }

    @Override
    public void venueOrderIdLearned(final OrderContext ctx) {
        this.output.venueOrderId(ctx);
    }

    @Override
    public long receiveNanos() {
        return this.receiveNanos;
    }

    /**
     * When the oldest order held was submitted, in epoch millis, or -1 if none is held. Visits every order: for the
     * supervisor while the reader is paused, not the hot path.
     */
    public long oldestOpenOrderMillis() {
        long oldest = Long.MAX_VALUE;
        for (final OrderContext ctx : this.orderContexts.values()) {
            oldest = Math.min(oldest, ctx.submittedAtMillis);
        }
        return oldest == Long.MAX_VALUE ? -1 : oldest;
    }

    /** The orders held; iterating doesn't allocate but visits every order, so it is for rare paths. */
    public Collection<OrderContext> held() {
        return this.orderContexts.values();
    }

    public int size() {
        return this.orderContexts.size();
    }

    private boolean discard(final long key) {
        final OrderContext ctx = this.orderContexts.remove(key);
        if (ctx == null) {
            return false;
        }
        ctx.reset();
        this.contextPool[this.contextPoolHead++] = ctx;
        return true;
    }

    private void prepareHeader(final OrderContext ctx) {
        this.execReport.encoder.exchangeId(ctx.exchangeId);
        this.execReport.encoder.securityId(ctx.securityId);
        writeExchangeOrderId(ctx);
        this.execReport.encodeClientOid(ctx.clientOidCounter, ctx.clientOidStrategyId);
        this.execReport.encoder.flags().clear();
        this.execReport.encoder.liquidity(Liquidity.NULL_VAL);
    }

    private void writeExchangeOrderId(final OrderContext ctx) {
        final int length = Math.min(ctx.correlationIdLength, OrderExecutionReportEncoder.exchangeOrderIdLength());
        for (int i = 0; i < length; i++) {
            this.execReport.encoder.exchangeOrderId(i, ctx.correlationIdBytes[i]);
        }
        for (int i = length; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            this.execReport.encoder.exchangeOrderId(i, (byte) 0);
        }
    }
}
