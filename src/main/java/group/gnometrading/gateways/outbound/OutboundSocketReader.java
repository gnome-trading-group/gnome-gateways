package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
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

    private final Logger logger;
    private final SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private final ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private final ManyToOneRingBuffer<OrderContext> writerReportQueue;
    protected final EpochNanoClock clock;
    protected final Listing listing;

    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;

    private final Long2ObjectHashMap<OrderContext> orderContexts;
    private final OrderContext[] contextPool;
    private int contextPoolHead;

    protected final OrderExecutionReport execReport;

    public volatile boolean pause;
    public volatile boolean isPaused;
    public volatile long recvTimestamp;

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
        this.orderContexts = new Long2ObjectHashMap<>(OrderContext.MAX_IN_FLIGHT_ORDERS * 2, 0.6f);
        this.contextPool = new OrderContext[OrderContext.MAX_IN_FLIGHT_ORDERS];
        for (int i = 0; i < OrderContext.MAX_IN_FLIGHT_ORDERS; i++) {
            this.contextPool[i] = new OrderContext();
        }
        this.contextPoolHead = OrderContext.MAX_IN_FLIGHT_ORDERS;
        this.execReport = new OrderExecutionReport();
        this.execReport.wrap(this.execReport.buffer);
        this.pause = true;
        this.isPaused = false;
        this.recvTimestamp = 0;
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(ByteBuffer buffer) throws Exception;

    protected abstract void attachSocket() throws IOException;

    protected abstract void disconnectSocket() throws Exception;

    protected abstract void subscribe() throws IOException;

    public abstract void keepAlive() throws IOException;

    public final void connect() throws IOException {
        this.pause = true;
        while (!this.isPaused) {
            Thread.yield();
        }
        attachSocket();
        this.pause = false;
    }

    public final void disconnect() throws Exception {
        logger.log(LogMessage.SOCKET_DISCONNECTING);
        this.pause = true;
        while (!this.isPaused) {
            Thread.yield();
        }
        disconnectSocket();
        logger.log(LogMessage.SOCKET_DISCONNECTED);
    }

    @Override
    public final int doWork() throws Exception {
        if (this.pause) {
            this.isPaused = true;
            while (this.pause) {
                Thread.yield();
            }
            this.isPaused = false;
        }

        // New orders first: an order submitted and amended in one writer poll must exist here before
        // its amend notice is applied.
        this.newOrderQueue.read(this::consumeNewOrder, OrderContext.HANDOFF_QUEUE_CAPACITY);
        this.writerReportQueue.read(this::consumeWriterReport, OrderContext.HANDOFF_QUEUE_CAPACITY);

        final ByteBuffer buffer = readSocket();
        if (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    private void consumeNewOrder(final OrderContext src) {
        if (this.contextPoolHead <= 0) {
            throw new RuntimeException("Order context pool exhausted");
        }
        final OrderContext ctx = this.contextPool[--this.contextPoolHead];
        ctx.copyFrom(src);
        this.orderContexts.put(computeKey(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength), ctx);
    }

    private void consumeWriterReport(final OrderContext src) {
        if (src.amendAccepted) {
            applyAcceptedAmend(src);
            return;
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
        final OrderContext ctx =
                this.orderContexts.get(computeKey(src.exchangeOrderIdBytes, src.exchangeOrderIdLength));
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
        this.execReport.encoder.orderId(ctx.orderId);
        this.execReport.encodeClientOid(ctx.clientOidCounter, ctx.clientOidStrategyId);
        this.execReport.encoder.flags().clear();
        this.execReport.encoder.liquidity(Liquidity.NULL_VAL);
    }

    protected final void publishExecReport() {
        this.execReportBuffer.publishRaw(
                this.execReport.buffer, OrderExecutionReportEncoder.TEMPLATE_ID, this.execReport.totalMessageSize());
    }

    protected final OrderContext findOrderContext(final long key) {
        return this.orderContexts.get(key);
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
        this.pause = true;
        logger.log(LogMessage.SOCKET_DISCONNECTED);
        throw new RuntimeException("Socket closed");
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
