package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import org.agrona.concurrent.EpochNanoClock;

public abstract class OutboundSocketReader implements GnomeAgent, OrderEventSink {

    protected final Logger logger;
    private final SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private final ManyToOneRingBuffer<OrderContext> newOrderQueue;
    // Counts handoffs and socket messages within one doWork() pass, because the queue reads don't report how
    // many entries they consumed and a back-off idle strategy needs an honest work count.
    private int handoffsThisPass;
    private final ManyToOneRingBuffer<OrderContext> writerReportQueue;
    protected final EpochNanoClock clock;
    protected final Listing listing;

    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    // Held once, so the hot loop doesn't create a method reference on every pass.
    private final MessageConsumer<OrderContext> newOrderHandler = this::consumeNewOrder;
    private final MessageConsumer<OrderContext> writerReportHandler = this::consumeWriterReport;

    private final OrderReports reports;

    public final ReaderPauseControl pauseControl = new ReaderPauseControl();
    // Set by the supervisor during a connect, before it resumes the reader, which the resume hands over.
    private volatile boolean venueStatePending;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    public volatile long recvTimestamp;
    private final long createdAtNanos;

    protected OutboundSocketReader(
            Logger logger,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ManyToOneRingBuffer<OrderContext> newOrderQueue,
            ManyToOneRingBuffer<OrderContext> writerReportQueue,
            ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            EpochNanoClock clock,
            Listing listing) {
        this.logger = logger;
        this.execReportBuffer = execReportBuffer;
        // It is this ring's only producer, so its own stop may end a wait on the ring.
        this.pauseControl.onStop(this.execReportBuffer::stop);
        this.newOrderQueue = newOrderQueue;
        this.writerReportQueue = writerReportQueue;
        this.releasedOrderQueue = releasedOrderQueue;
        this.clock = clock;
        this.listing = listing;
        this.reports = new OrderReports(OrderContext.READER_POOL_SIZE, clock, new ReportOutput());
        this.reports.venueIdListener(this::onVenueOrderId);
        this.recvTimestamp = 0;
        this.createdAtNanos = clock.nanoTime();
    }

    /** Reports go to the session's ring; released orders back to the writer, which knows them by counter. */
    private final class ReportOutput implements OrderReports.Output {
        @Override
        public void publish(final OrderContext ctx, final OrderExecutionReport report) {
            execReportBuffer.publishRaw(
                    report.buffer, OrderExecutionReportEncoder.TEMPLATE_ID, report.totalMessageSize());
        }

        @Override
        public void released(final OrderContext ctx) {
            // Dropping a completion would leave the order in the writer's activeOrders forever and leak a writer
            // pool slot, so fail as loudly as the other two queues do.
            final int idx = releasedOrderQueue.tryClaim();
            if (idx < 0) {
                throw new RuntimeException("Released order queue overflow");
            }
            releasedOrderQueue.indexAt(idx).clientOidCounter = ctx.clientOidCounter;
            releasedOrderQueue.commit(idx);
        }
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(ByteBuffer buffer) throws Exception;

    protected abstract void attachSocket() throws IOException;

    protected abstract void disconnectSocket() throws Exception;

    protected abstract void subscribe() throws IOException;

    public abstract void keepAlive() throws IOException;

    /**
     * Applies the gateway's socket settings to the reader's connection. Called once, before the first connect;
     * readers whose connection keeps its settings across reconnects need nothing more.
     */
    public void configureSocket(final GatewayConfig config) throws IOException {}

    public final void connect() throws IOException {
        this.pauseControl.pause();
        attachSocket();
        // Subscribed first, so nothing can fall between the venue's state and the events that follow it. A
        // failure fails the connect: the reader is never resumed without having caught up.
        fetchVenueState();
        this.venueStatePending = true;
        // Silence is measured from the new connection: left at the old connection's last message, the supervisor
        // would still see the silence that caused this reconnect and reconnect again before anything arrives.
        this.recvTimestamp = clock.nanoTime();
        this.pauseControl.resume();
    }

    /**
     * Supervisor thread, while the reader is paused and after its socket is subscribed: fetches what the venue
     * says of the orders this reader holds, for {@link #applyVenueState} to apply.
     */
    protected void fetchVenueState() throws IOException {}

    /** Reader thread, on its first pass after a connect: applies what {@link #fetchVenueState} fetched. */
    protected void applyVenueState() {}

    public final void disconnect() throws Exception {
        logger.log(LogMessage.SOCKET_DISCONNECTING);
        this.pauseControl.pause();
        disconnectSocket();
        logger.log(LogMessage.SOCKET_DISCONNECTED);
    }

    /** Runs on the reader's own thread after its last pass, so the supervisor can stop waiting on it. */
    @Override
    public final void onClose() {
        this.pauseControl.readerExited();
    }

    @Override
    public final int doWork() throws Exception {
        if (!this.pauseControl.awaitIfPaused()) {
            return 0;
        }

        // New orders first: an order submitted and amended in one writer poll must exist here before
        // its amend notice is applied.
        this.newOrderQueue.read(this.newOrderHandler, OrderContext.HANDOFF_QUEUE_CAPACITY);
        this.writerReportQueue.read(this.writerReportHandler, OrderContext.HANDOFF_QUEUE_CAPACITY);
        // What the venue said while the socket was down comes before anything queued on the new socket.
        if (this.venueStatePending) {
            this.venueStatePending = false;
            applyVenueState();
        }

        final ByteBuffer buffer;
        try {
            buffer = readSocket();
        } catch (SocketClosedException e) {
            // The reader saw the close itself and has already paused and logged it.
            throw e;
        } catch (IOException | RuntimeException e) {
            // Whatever broke the read (a dead connection, a message larger than the buffer, a framing error)
            // breaks every read after it on this connection too, so stop and let it be reconnected.
            onSocketClose(e);
            return 0;
        }
        if (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            this.reports.receivedAt(this.recvTimestamp);
            handleGatewayMessage(buffer);
            this.handoffsThisPass++;
        }
        final int work = this.handoffsThisPass;
        this.handoffsThisPass = 0;
        return work;
    }

    @Override
    public final ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    private void consumeNewOrder(final OrderContext src) {
        this.handoffsThisPass++;
        this.reports.register(src);
    }

    private void consumeWriterReport(final OrderContext src) {
        this.handoffsThisPass++;
        this.reports.onNotice(src);
    }

    /**
     * Called when the writer reports the venue's id for an order this reader holds, under {@code key}. Venues that
     * index their orders by that id override it.
     */
    protected void onVenueOrderId(final OrderContext ctx, final long key) {}

    /** Ends the order with a {@link ExecType#CANCEL} that keeps its fills so far, and releases it. */
    protected final void publishCanceled(final OrderContext ctx, final long key, final long timestampEvent) {
        this.reports.publishCanceled(ctx, key, timestampEvent);
    }

    /** See {@link OrderReports#publishRejected}. */
    protected final void publishRejected(
            final OrderContext ctx, final long key, final long timestampEvent, final RejectReason reason) {
        this.reports.publishRejected(ctx, key, timestampEvent, reason);
    }

    /** Publishes the order's {@link ExecType#NEW} acknowledgement and records that it was sent. */
    protected final void publishNew(
            final OrderContext ctx,
            final long cumulativeQty,
            final long leavesQty,
            final long timestampEvent,
            final long timestampRecv) {
        this.reports.publishNew(ctx, cumulativeQty, leavesQty, timestampEvent, timestampRecv);
    }

    @Override
    public final OrderContext order(final long correlationKey) {
        return this.reports.order(correlationKey);
    }

    @Override
    public final Iterable<OrderContext> orders() {
        return this.reports.orders();
    }

    @Override
    public final void acknowledge(
            final OrderContext ctx, final long cumulativeQty, final long leavesQty, final long eventNanos) {
        this.reports.acknowledge(ctx, cumulativeQty, leavesQty, eventNanos);
    }

    @Override
    public final void fill(
            final OrderContext ctx,
            final long fillQty,
            final long fillPrice,
            final long fee,
            final Liquidity liquidity,
            final boolean filled,
            final long eventNanos) {
        this.reports.fill(ctx, fillQty, fillPrice, fee, liquidity, filled, eventNanos);
    }

    @Override
    public final void cancel(final OrderContext ctx, final long eventNanos) {
        this.reports.cancel(ctx, eventNanos);
    }

    @Override
    public final void reject(final OrderContext ctx, final RejectReason reason, final long eventNanos) {
        this.reports.reject(ctx, reason, eventNanos);
    }

    @Override
    public final void release(final OrderContext ctx) {
        this.reports.release(ctx);
    }

    @Override
    public final long receiveNanos() {
        return this.reports.receiveNanos();
    }

    /**
     * When the oldest order this reader holds was submitted, or -1 if it holds none. Supervisor thread, while the
     * reader is paused; not for the hot path.
     */
    protected final long oldestOpenOrderMillis() {
        return this.reports.oldestOpenOrderMillis();
    }

    /**
     * When the socket last delivered anything, in epoch millis, or when this reader was made if it never connected.
     * Venue events after it may have been missed, including for orders sent while the socket was down. Supervisor
     * thread, while the reader is paused.
     */
    protected final long lastHeardMillis() {
        final long lastHeard = this.recvTimestamp;
        return (lastHeard > 0 ? lastHeard : this.createdAtNanos) / NANOS_PER_MILLI;
    }

    /**
     * The orders this reader holds: on the reader's own thread, or the supervisor's while the reader is paused.
     * Iterating it doesn't allocate, but visits every order, so it is for rare paths.
     */
    protected final Collection<OrderContext> heldOrders() {
        return this.reports.held();
    }

    protected final OrderContext findOrderContext(final long key) {
        return this.reports.order(key);
    }

    protected final void releaseOrderContext(final long key) {
        this.reports.release(key);
    }

    protected final void onSocketClose() {
        onSocketClose(null);
    }

    private void onSocketClose(final Exception cause) {
        this.pauseControl.pauseSelf();
        logger.log(LogMessage.SOCKET_DISCONNECTED);
        throw new SocketClosedException(cause);
    }

    @VisibleForTesting
    protected final int orderContextCount() {
        return this.reports.size();
    }
}
