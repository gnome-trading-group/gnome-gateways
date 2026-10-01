package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.gateways.outbound.fee.PredictionMarketFees;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundReader extends OutboundJsonWebSocketReader {

    // Signing uses the WebSocket endpoint path
    private static final String WS_PATH = "/user_orders";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final int TYPE_FLAG_USER_ORDER = 1;

    private static final int STATUS_RESTING = 1;
    private static final int STATUS_EXECUTED = 2;
    private static final int STATUS_CANCELED = 3;

    private final KalshiAuthSigner authSigner;
    private final double takerFeeRate;
    private final double makerFeeRate;
    private final ParsedEvent parsedEvent = new ParsedEvent();

    public KalshiOutboundReader(
            final Logger logger,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final EpochNanoClock clock,
            final Listing listing,
            final WebSocketClient socketClient,
            final JsonDecoder jsonDecoder,
            final KalshiAuthSigner authSigner,
            final double takerFeeRate,
            final double makerFeeRate) {
        super(
                logger,
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                clock,
                listing,
                socketClient,
                jsonDecoder);
        this.authSigner = authSigner;
        this.takerFeeRate = takerFeeRate;
        this.makerFeeRate = makerFeeRate;
    }

    @Override
    protected void beforeConnect() throws IOException {
        this.authSigner.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WS_PATH);
        this.socketClient.setHeader("KALSHI-ACCESS-KEY", this.authSigner.apiKey());
        this.socketClient.setHeader("KALSHI-ACCESS-TIMESTAMP", this.authSigner.timestamp());
        this.socketClient.setHeader("KALSHI-ACCESS-SIGNATURE", this.authSigner.signature());
    }

    @Override
    protected void subscribe() throws IOException {
        // user_orders channel auto-streams all order updates on connect — no subscription message needed
    }

    @Override
    public void keepAlive() throws IOException {
        // Kalshi server sends WebSocket pings every 10s; base class handles PONG automatically
    }

    @Override
    public String roleName() {
        return "kalshi-outbound-reader";
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonObject obj) {
        int typeFlag = 0;
        this.parsedEvent.reset();
        while (obj.hasNextKey()) {
            try (var entry = obj.nextKey()) {
                final GnomeString name = entry.getName();
                if (name.equals("type")) {
                    typeFlag = parseTypeFlag(entry.asString());
                } else if (name.equals("msg") && typeFlag == TYPE_FLAG_USER_ORDER) {
                    try (var msgObj = entry.asObject()) {
                        parseMsgObject(msgObj, this.parsedEvent);
                    }
                }
            }
        }
        if (typeFlag == TYPE_FLAG_USER_ORDER) {
            emitEvent(this.parsedEvent);
        }
    }

    private static int parseTypeFlag(final GnomeString val) {
        if (val.equals("user_order")) {
            return TYPE_FLAG_USER_ORDER;
        }
        return 0;
    }

    private static void parseMsgObject(final JsonDecoder.JsonObject msgObj, final ParsedEvent event) {
        while (msgObj.hasNextKey()) {
            try (var field = msgObj.nextKey()) {
                final GnomeString name = field.getName();
                if (name.equals("order_id")) {
                    copyOrderId(field.asString(), event);
                } else if (name.equals("status")) {
                    parseStatus(field.asString(), event);
                } else if (name.equals("fill_count_fp")) {
                    event.fillCount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("remaining_count_fp")) {
                    event.remainingCount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("last_updated_ts_ms")) {
                    event.timestampMs = field.asLong();
                } else {
                    parseDollarField(name, field, event);
                }
            }
        }
    }

    private static void parseDollarField(
            final GnomeString name, final JsonDecoder.JsonNode field, final ParsedEvent event) {
        if (name.equals("taker_fill_cost_dollars")) {
            event.takerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("maker_fill_cost_dollars")) {
            event.makerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("taker_fees_dollars")) {
            event.takerFees = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            event.feesReported = true;
        } else if (name.equals("maker_fees_dollars")) {
            event.makerFees = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            event.feesReported = true;
        }
    }

    private static void parseStatus(final GnomeString val, final ParsedEvent event) {
        if (val.equals("resting")) {
            event.statusFlag = STATUS_RESTING;
        } else if (val.equals("executed")) {
            event.statusFlag = STATUS_EXECUTED;
        } else if (val.equals("canceled")) {
            event.statusFlag = STATUS_CANCELED;
        }
    }

    private static void copyOrderId(final GnomeString val, final ParsedEvent event) {
        event.orderIdLength = Math.min(val.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < event.orderIdLength; i++) {
            event.orderIdBytes[i] = val.byteAt(i);
        }
    }

    private void emitEvent(final ParsedEvent event) {
        final long key = computeKey(event.orderIdBytes, event.orderIdLength);
        final OrderContext ctx = findOrderContext(key);
        if (ctx == null) {
            return;
        }

        if (event.statusFlag == STATUS_CANCELED) {
            // An order that fills and is cancelled together, most often an IOC that partly filled, reports
            // both in one update. The fills must be reported first or the OMS never learns it holds them.
            if (event.fillCount > ctx.cumulativeFilledQty) {
                final boolean fillsCompleteOrder = event.fillCount - ctx.cumulativeFilledQty >= ctx.leavesQty;
                publishFill(
                        ctx, event, fillsCompleteOrder, false, fillsCompleteOrder ? 0 : workingQtyAtCancel(ctx, event));
                if (fillsCompleteOrder) {
                    releaseOrderContext(key);
                    return;
                }
            }
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.CANCEL);
            this.execReport.encoder.orderStatus(OrderStatus.CANCELED);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
            this.execReport.encoder.leavesQty(0);
            setTimestamp(event.timestampMs);
            publishExecReport();
            releaseOrderContext(key);
            return;
        }

        if (event.fillCount > ctx.cumulativeFilledQty) {
            emitFillEvent(ctx, event, key);
            return;
        }

        // Only the first `resting` update acknowledges the order. Any later one, such as one following
        // an amend, must not become a second NEW: amends are acknowledged from the writer's notice.
        if (event.statusFlag == STATUS_RESTING && !ctx.acked) {
            publishNew(ctx, 0, ctx.originalQty, event.timestampMs * NANOS_PER_MILLI, this.recvTimestamp);
        }
    }

    private void emitFillEvent(final OrderContext ctx, final ParsedEvent event, final long key) {
        final boolean fullyFilled = event.remainingCount <= 0 || event.statusFlag == STATUS_EXECUTED;
        publishFill(ctx, event, fullyFilled, !fullyFilled, fullyFilled ? 0 : workingQtyAfter(ctx, event));
        if (fullyFilled) {
            releaseOrderContext(key);
        }
    }

    /**
     * Publishes the fill that moved the order to {@code event.fillCount}, leaving {@code leavesAfter}
     * working. The caller decides both, since a cancel update's own remaining count is already zero.
     */
    private void publishFill(
            final OrderContext ctx,
            final ParsedEvent event,
            final boolean fullyFilled,
            final boolean stillWorking,
            final long leavesAfter) {
        final long fillDelta = event.fillCount - ctx.cumulativeFilledQty;
        final long totalCost = event.takerFillCost + event.makerFillCost;
        final long costDelta = totalCost - ctx.cumulativeCost;
        // fillPrice in PRICE_SCALING_FACTOR units per contract:
        // (costDelta in PRICE_SCALING_FACTOR) / (fillDelta in SIZE_SCALING_FACTOR) * SIZE_SCALING_FACTOR
        final long fillPrice = fillDelta > 0 ? costDelta * Statics.SIZE_SCALING_FACTOR / fillDelta : 0;

        // An order that fills on arrival has no `resting` update to acknowledge it. Without a NEW
        // the OMS slot stays PENDING_NEW, so acknowledge it first if it is still working.
        if (!ctx.acked && stillWorking) {
            publishNew(
                    ctx,
                    ctx.cumulativeFilledQty,
                    ctx.originalQty - ctx.cumulativeFilledQty,
                    event.timestampMs * NANOS_PER_MILLI,
                    this.recvTimestamp);
        }
        ctx.acked = true;

        final long makerCostDelta = event.makerFillCost - ctx.cumulativeMakerCost;
        final Liquidity liquidity = classifyLiquidity(costDelta, makerCostDelta);
        final long fee = fillFee(ctx, event, fillPrice, fillDelta, costDelta, makerCostDelta);

        ctx.cumulativeFilledQty = event.fillCount;
        ctx.cumulativeCost = totalCost;
        ctx.cumulativeMakerCost = event.makerFillCost;
        ctx.leavesQty = leavesAfter;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.filledQty(fillDelta);
        this.execReport.encoder.fillPrice(fillPrice);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        setTimestamp(event.timestampMs);
        this.execReport.encoder.fee(fee);
        this.execReport.encoder.liquidity(liquidity);
        publishExecReport();
    }

    /**
     * What was still working when a cancel arrived together with fills. The update's own remaining
     * count is already zero, so this comes from our working quantity less the new fills. The OMS
     * removes it from position leaves on the CANCEL that follows, so it must not be zero here.
     */
    private static long workingQtyAtCancel(final OrderContext ctx, final ParsedEvent event) {
        if (ctx.amendFillCount != OrderContext.QTY_ABSENT && event.fillCount <= ctx.amendFillCount) {
            return ctx.leavesQty;
        }
        return Math.max(0, ctx.leavesQty - (event.fillCount - ctx.cumulativeFilledQty));
    }

    /**
     * A fill the venue had already counted when it accepted an amend reports the pre-amend
     * remaining quantity, so it cannot be trusted. The amend's acknowledgement already netted that
     * fill out of the working quantity, which therefore stands; later fills report post-amend numbers.
     */
    private static long workingQtyAfter(final OrderContext ctx, final ParsedEvent event) {
        if (ctx.amendFillCount != OrderContext.QTY_ABSENT && event.fillCount <= ctx.amendFillCount) {
            return ctx.leavesQty;
        }
        return event.remainingCount;
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
     * actually charged, split by liquidity, so those are used whenever present. The rate model is
     * only a fallback for messages without them, and splits the fill by which cost bucket moved.
     */
    private long fillFee(
            final OrderContext ctx,
            final ParsedEvent event,
            final long fillPrice,
            final long fillDelta,
            final long costDelta,
            final long makerCostDelta) {
        if (event.feesReported) {
            final long totalFees = event.takerFees + event.makerFees;
            final long fee = totalFees - ctx.cumulativeFees;
            ctx.cumulativeFees = totalFees;
            return fee;
        }
        final double makerShare = costDelta > 0 ? Math.min(1.0, (double) makerCostDelta / costDelta) : 0.0;
        final long makerQty = (long) (fillDelta * makerShare);
        final long fee = PredictionMarketFees.calculateScaledFee(fillPrice, makerQty, this.makerFeeRate)
                + PredictionMarketFees.calculateScaledFee(fillPrice, fillDelta - makerQty, this.takerFeeRate);
        ctx.cumulativeFees += fee;
        return fee;
    }

    private void setNullFillFields() {
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
    }

    private void setTimestamp(final long timestampMs) {
        this.execReport.encoder.timestampEvent(timestampMs * NANOS_PER_MILLI);
        this.execReport.encoder.timestampRecv(this.recvTimestamp);
    }

    private static final class ParsedEvent {
        final byte[] orderIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
        int orderIdLength;
        int statusFlag;
        long fillCount;
        long remainingCount;
        long takerFillCost;
        long makerFillCost;
        long takerFees;
        long makerFees;
        boolean feesReported;
        long timestampMs;

        void reset() {
            this.orderIdLength = 0;
            this.statusFlag = 0;
            this.fillCount = 0;
            this.remainingCount = 0;
            this.takerFillCost = 0;
            this.makerFillCost = 0;
            this.takerFees = 0;
            this.makerFees = 0;
            this.feesReported = false;
            this.timestampMs = OrderExecutionReportEncoder.timestampEventNullValue();
        }
    }
}
