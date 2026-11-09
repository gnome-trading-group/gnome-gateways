package group.gnometrading.gateways.account.reader;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.gateways.transport.FrameReader;
import group.gnometrading.schemas.OrderExecutionReport;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * What an account's event socket does on its reader's thread: holds every working order on the account, as the lane
 * hands them over before sending them, applies the lane's other {@link ReaderNotices} and the venue's events to them, and
 * writes each report, tagged with its session's slot, to the account's out ring. Ended orders go back to the lane.
 *
 * <p>Back to the lane go ended orders and venue ids learned from events, which the lane needs to cancel an order
 * whose submit's answer never named it. Neither ring may drop an entry, so a full one is waited out. The ring back to
 * the lane never fills if it holds twice as many entries as the lane holds orders, as each order is released once and
 * has its id learned at most once; the out ring stays full only while its reader is stalled.
 */
public final class AccountReader implements FrameReader.Handler {

    private final AccountProtocol<?> protocol;
    private final ManyToOneRingBuffer<OrderContext> noticeQueue;
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private final ManyToOneRingBuffer<AccountReport> reportQueue;
    private final OrderReports reports;
    // Held once, so the loop doesn't create a method reference on every pass.
    private final MessageConsumer<OrderContext> noticeHandler;
    // Handed over by the supervisor: set after its fetch, read by this thread before the next frame.
    private volatile boolean catchUpPending;
    private volatile boolean followUpPending;
    // Set by the supervisor as a socket opens: its frames wait until the catch-up fetched for it has been applied.
    private volatile boolean holdFrames;

    public AccountReader(
            final AccountProtocol<?> protocol,
            final EpochNanoClock clock,
            final int maxOrders,
            final ManyToOneRingBuffer<OrderContext> noticeQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final ManyToOneRingBuffer<AccountReport> reportQueue) {
        this.protocol = protocol;
        this.noticeQueue = noticeQueue;
        this.releasedOrderQueue = releasedOrderQueue;
        this.reportQueue = reportQueue;
        this.reports = new OrderReports(maxOrders, clock, new Output());
        this.reports.venueIdListener(protocol::onVenueOrderId);
        this.noticeHandler = this.reports::onNotice;
    }

    /** The lane's orders and notices, on one queue in the order it wrote them, so a notice never precedes its order. */
    @Override
    public void beforeRead() {
        this.noticeQueue.read(this.noticeHandler, Integer.MAX_VALUE);
        if (this.catchUpPending) {
            this.catchUpPending = false;
            this.protocol.applyCatchUp(this.reports);
            this.holdFrames = false;
        }
        if (this.followUpPending) {
            this.followUpPending = false;
            this.protocol.applyFollowUp(this.reports);
        }
    }

    @Override
    public boolean mayRead() {
        return !this.holdFrames;
    }

    /**
     * Drains the lane's queue again first: an order it registered after this pass began, then sent, may be the one
     * this frame is about, and its registration was committed before the frame could have been read.
     */
    @Override
    public void onFrame(final ByteBuffer frame, final long receiveNanos) {
        this.noticeQueue.read(this.noticeHandler, Integer.MAX_VALUE);
        this.reports.receivedAt(receiveNanos);
        this.protocol.onFrame(frame, this.reports);
    }

    /**
     * Holds an order a process before this one sent, as the lane {@linkplain AccountLane#recover takes it on again}:
     * its events and catch-up then apply to it as to any other, and its reports go to its session's slot. Pass
     * {@code acked} and the fills on {@code src} as they were last reported, so nothing is reported twice. Before the
     * reader's thread starts.
     */
    public void recover(final OrderContext src) {
        this.reports.register(src);
        if (src.exchangeOrderIdLength > 0) {
            final long key = OrderKeys.of(src);
            this.protocol.onVenueOrderId(this.reports.order(key), key);
        }
    }

    /**
     * Supervisor thread, reader paused: a copy of the orders held, for a catch-up fetched while the reader runs on.
     * Allocates: for a connect, not the hot path.
     */
    List<OrderContext> copyHeld() {
        final List<OrderContext> copies = new ArrayList<>(this.reports.size());
        for (final OrderContext ctx : this.reports.held()) {
            final OrderContext copy = new OrderContext();
            copy.copyFrom(ctx);
            copies.add(copy);
        }
        return copies;
    }

    /** Supervisor thread, reader paused: the new socket's frames wait for the catch-up fetched for it. */
    void holdFramesForCatchUp() {
        this.holdFrames = true;
    }

    /** Supervisor thread, after {@link AccountProtocol#fetchCatchUp}: applied on the reader's next pass. */
    void catchUpFetched() {
        this.catchUpPending = true;
    }

    /** Supervisor thread, after {@link AccountProtocol#fetchFollowUp}: applied on the reader's next pass. */
    void followUpFetched() {
        this.followUpPending = true;
    }

    /** Orders held, for status and tests; reader thread, or any while paused. */
    public int heldOrders() {
        return this.reports.size();
    }

    /**
     * Every order held, by session slot and counter with its venue id and fills so far, for a stuck order's post-mortem.
     * Reads the reader's state without its lock, so only once it is quiet; allocates.
     */
    public String describeHeld() {
        final StringBuilder text = new StringBuilder();
        for (final OrderContext ctx : this.reports.orders()) {
            text.append(ctx.sessionSlot)
                    .append('/')
                    .append(ctx.clientOidCounter)
                    .append(": venue id ")
                    .append(new String(
                            ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII))
                    .append(", filled ")
                    .append(ctx.cumulativeFilledQty)
                    .append('\n');
        }
        return text.toString();
    }

    private final class Output implements OrderReports.Output {
        @Override
        public void publish(final OrderContext ctx, final OrderExecutionReport report) {
            final int idx = claim(reportQueue);
            reportQueue.indexAt(idx).copyFrom(ctx.sessionSlot, report);
            reportQueue.commit(idx);
        }

        @Override
        public void released(final OrderContext ctx) {
            final int idx = claim(releasedOrderQueue);
            final OrderContext released = releasedOrderQueue.indexAt(idx);
            released.reset();
            released.sessionSlot = ctx.sessionSlot;
            released.clientOidCounter = ctx.clientOidCounter;
            releasedOrderQueue.commit(idx);
        }

        @Override
        public void venueOrderId(final OrderContext ctx) {
            final int idx = claim(releasedOrderQueue);
            final OrderContext notice = releasedOrderQueue.indexAt(idx);
            notice.reset();
            notice.sessionSlot = ctx.sessionSlot;
            notice.clientOidCounter = ctx.clientOidCounter;
            notice.venueOrderIdNotice = true;
            notice.exchangeOrderIdLength = ctx.exchangeOrderIdLength;
            System.arraycopy(ctx.exchangeOrderIdBytes, 0, notice.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
            releasedOrderQueue.commit(idx);
        }
    }

    private static int claim(final ManyToOneRingBuffer<?> queue) {
        int idx;
        while ((idx = queue.tryClaim()) < 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted waiting for room in a reader's queue");
            }
            Thread.onSpinWait();
        }
        return idx;
    }
}
