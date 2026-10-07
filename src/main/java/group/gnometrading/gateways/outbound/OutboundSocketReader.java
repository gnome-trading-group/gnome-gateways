package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;

public abstract class OutboundSocketReader implements GnomeAgent {

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

    private final Long2ObjectHashMap<OrderContext> orderContexts;
    private final OrderContext[] contextPool;
    private int contextPoolHead;

    protected final OrderExecutionReport execReport;

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
        this.newOrderQueue = newOrderQueue;
        this.writerReportQueue = writerReportQueue;
        this.releasedOrderQueue = releasedOrderQueue;
        this.clock = clock;
        this.listing = listing;
        this.orderContexts = new Long2ObjectHashMap<>(OrderContext.READER_POOL_SIZE * 2, 0.6f);
        this.contextPool = new OrderContext[OrderContext.READER_POOL_SIZE];
        for (int i = 0; i < OrderContext.READER_POOL_SIZE; i++) {
            this.contextPool[i] = new OrderContext();
        }
        this.contextPoolHead = OrderContext.READER_POOL_SIZE;
        this.execReport = new OrderExecutionReport();
        this.execReport.wrap(this.execReport.buffer);
        this.recvTimestamp = 0;
        this.createdAtNanos = clock.nanoTime();
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
        if (this.contextPoolHead <= 0) {
            throw new RuntimeException("Order context pool exhausted");
        }
        final OrderContext ctx = this.contextPool[--this.contextPoolHead];
        ctx.copyFrom(src);
        this.orderContexts.put(computeKey(ctx.correlationIdBytes, ctx.correlationIdLength), ctx);
    }

    private void consumeWriterReport(final OrderContext src) {
        this.handoffsThisPass++;
        if (src.amendAccepted) {
            applyAcceptedAmend(src);
            return;
        }
        if (src.execType == ExecType.REJECT) {
            // The writer registers an order before sending it, and frees its own copy on a reject.
            discardOrderContext(computeKey(src.correlationIdBytes, src.correlationIdLength));
        }
        prepareExecReportHeader(src);
        this.execReport.encoder.execType(src.execType);
        this.execReport.encoder.orderStatus(src.orderStatus);
        this.execReport.encoder.rejectReason(src.rejectReason);
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.cumulativeQty(0);
        this.execReport.encoder.leavesQty(0);
        this.execReport.encoder.timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue());
        this.execReport.encoder.timestampRecv(this.clock.nanoTime());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        publishExecReport();
    }

    /**
     * Acknowledges an amend the venue accepted, using this thread's view of the order's fills.
     *
     * <p>The venue's numbers are a snapshot taken when it processed the amend, but fills on the
     * stream can land on either side of that snapshot. Fills this reader has already seen beyond
     * the snapshot are taken off the venue's remaining quantity; fills still in flight are already
     * counted in it, and {@link OrderContext#amendFillCount} lets the venue reader recognise them.
     * An order that has finished in the meantime needs no acknowledgement: its terminal report
     * already settled the OMS's state.
     */
    private void applyAcceptedAmend(final OrderContext src) {
        final OrderContext ctx = this.orderContexts.get(computeKey(src.correlationIdBytes, src.correlationIdLength));
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

    /** Publishes the order's {@link ExecType#NEW} acknowledgement and records that it was sent. */
    protected final void publishNew(
            final OrderContext ctx,
            final long cumulativeQty,
            final long leavesQty,
            final long timestampEvent,
            final long timestampRecv) {
        prepareExecReportHeader(ctx);
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
        publishExecReport();
        ctx.acked = true;
    }

    protected final void prepareExecReportHeader(final OrderContext ctx) {
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

    protected final void publishExecReport() {
        this.execReportBuffer.publishRaw(
                this.execReport.buffer, OrderExecutionReportEncoder.TEMPLATE_ID, this.execReport.totalMessageSize());
    }

    /**
     * When the oldest order this reader holds was submitted, or -1 if it holds none. Supervisor thread, while the
     * reader is paused; not for the hot path.
     */
    protected final long oldestOpenOrderMillis() {
        long oldest = Long.MAX_VALUE;
        for (final OrderContext ctx : this.orderContexts.values()) {
            oldest = Math.min(oldest, ctx.submittedAtMillis);
        }
        return oldest == Long.MAX_VALUE ? -1 : oldest;
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

    protected final OrderContext findOrderContext(final long key) {
        return this.orderContexts.get(key);
    }

    private void discardOrderContext(final long key) {
        final OrderContext ctx = this.orderContexts.remove(key);
        if (ctx != null) {
            ctx.reset();
            this.contextPool[this.contextPoolHead++] = ctx;
        }
    }

    protected final void releaseOrderContext(final long key) {
        final OrderContext ctx = this.orderContexts.remove(key);
        if (ctx == null) {
            return;
        }
        // Dropping a completion would leave the order in the writer's activeOrders forever and leak
        // a writer pool slot, so fail as loudly as the other two queues do.
        final int idx = this.releasedOrderQueue.tryClaim();
        if (idx < 0) {
            throw new RuntimeException("Released order queue overflow");
        }
        this.releasedOrderQueue.indexAt(idx).clientOidCounter = ctx.clientOidCounter;
        this.releasedOrderQueue.commit(idx);
        ctx.reset();
        this.contextPool[this.contextPoolHead++] = ctx;
    }

    protected final void onSocketClose() {
        onSocketClose(null);
    }

    private void onSocketClose(final Exception cause) {
        this.pauseControl.pauseSelf();
        logger.log(LogMessage.SOCKET_DISCONNECTED);
        throw new SocketClosedException(cause);
    }

    protected static long computeKey(final byte[] bytes, final int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            hash ^= bytes[i] & 0xFF;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @VisibleForTesting
    protected final int orderContextCount() {
        return this.orderContexts.size();
    }
}
