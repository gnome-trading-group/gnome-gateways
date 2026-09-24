package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.gateways.outbound.fee.PredictionMarketFees;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.ExecType;
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
            final ManyToOneRingBuffer<OrderContext> contextQueue,
            final ManyToOneRingBuffer<OrderContext> rejectQueue,
            final ManyToOneRingBuffer<OrderContext> completionQueue,
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
                contextQueue,
                rejectQueue,
                completionQueue,
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
                } else if (name.equals("taker_fill_cost_dollars")) {
                    event.takerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (name.equals("maker_fill_cost_dollars")) {
                    event.makerFillCost = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (name.equals("last_updated_ts_ms")) {
                    event.timestampMs = field.asLong();
                }
            }
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

        if (event.statusFlag == STATUS_RESTING && ctx.cumulativeFilledQty == 0) {
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.NEW);
            this.execReport.encoder.orderStatus(OrderStatus.NEW);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(0);
            this.execReport.encoder.leavesQty(ctx.originalQty);
            setTimestamp(event.timestampMs);
            publishExecReport();
        }
    }

    private void emitFillEvent(final OrderContext ctx, final ParsedEvent event, final long key) {
        final long fillDelta = event.fillCount - ctx.cumulativeFilledQty;
        final long totalCost = event.takerFillCost + event.makerFillCost;
        final long costDelta = totalCost - ctx.cumulativeCost;
        // fillPrice in PRICE_SCALING_FACTOR units per contract:
        // (costDelta in PRICE_SCALING_FACTOR) / (fillDelta in SIZE_SCALING_FACTOR) * SIZE_SCALING_FACTOR
        final long fillPrice = fillDelta > 0 ? costDelta * Statics.SIZE_SCALING_FACTOR / fillDelta : 0;
        final boolean fullyFilled = event.remainingCount <= 0 || event.statusFlag == STATUS_EXECUTED;

        ctx.cumulativeFilledQty = event.fillCount;
        ctx.cumulativeCost = totalCost;
        ctx.leavesQty = fullyFilled ? 0 : event.remainingCount;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.filledQty(fillDelta);
        this.execReport.encoder.fillPrice(fillPrice);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        setTimestamp(event.timestampMs);
        this.execReport.encoder.fee(PredictionMarketFees.calculateScaledFee(fillPrice, fillDelta, takerFeeRate));
        publishExecReport();

        if (fullyFilled) {
            releaseOrderContext(key);
        }
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
        long timestampMs;

        void reset() {
            this.orderIdLength = 0;
            this.statusFlag = 0;
            this.fillCount = 0;
            this.remainingCount = 0;
            this.takerFillCost = 0;
            this.makerFillCost = 0;
            this.timestampMs = OrderExecutionReportEncoder.timestampEventNullValue();
        }
    }
}
