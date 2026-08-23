package group.gnometrading.gateways.outbound;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;

public abstract class OutboundSocketReader implements GnomeAgent {

    private static final int DEFAULT_CONTEXT_POOL_SIZE = 1 << 7;

    private final Logger logger;
    private final SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private final ManyToOneRingBuffer<OrderContext> contextQueue;
    private final ManyToOneRingBuffer<OrderContext> rejectQueue;
    protected final EpochNanoClock clock;
    protected final Listing listing;

    private final ManyToOneRingBuffer<OrderContext> completionQueue;

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
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue,
            EpochNanoClock clock,
            Listing listing) {
        this.logger = logger;
        this.execReportBuffer = execReportBuffer;
        this.contextQueue = contextQueue;
        this.rejectQueue = rejectQueue;
        this.completionQueue = completionQueue;
        this.clock = clock;
        this.listing = listing;
        this.orderContexts = new Long2ObjectHashMap<>(DEFAULT_CONTEXT_POOL_SIZE * 2, 0.6f);
        this.contextPool = new OrderContext[DEFAULT_CONTEXT_POOL_SIZE];
        for (int i = 0; i < DEFAULT_CONTEXT_POOL_SIZE; i++) {
            this.contextPool[i] = new OrderContext();
        }
        this.contextPoolHead = DEFAULT_CONTEXT_POOL_SIZE;
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

        this.rejectQueue.read(this::consumeReject, 16);
        this.contextQueue.read(this::consumeContext, 16);

        final ByteBuffer buffer = readSocket();
        if (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    private void consumeContext(final OrderContext src) {
        if (this.contextPoolHead <= 0) {
            throw new RuntimeException("Order context pool exhausted");
        }
        final OrderContext ctx = this.contextPool[--this.contextPoolHead];
        ctx.copyFrom(src);
        this.orderContexts.put(computeKey(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength), ctx);
    }

    private void consumeReject(final OrderContext src) {
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

    protected final void prepareExecReportHeader(final OrderContext ctx) {
        this.execReport.encoder.exchangeId(ctx.exchangeId);
        this.execReport.encoder.securityId(ctx.securityId);
        this.execReport.encoder.orderId(ctx.orderId);
        this.execReport.encodeClientOid(ctx.clientOidCounter, ctx.clientOidStrategyId);
        this.execReport.encoder.flags().clear();
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
        if (ctx != null) {
            final int idx = this.completionQueue.tryClaim();
            if (idx >= 0) {
                this.completionQueue.indexAt(idx).orderId = ctx.orderId;
                this.completionQueue.commit(idx);
            }
            ctx.reset();
            this.contextPool[this.contextPoolHead++] = ctx;
        }
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
