package group.gnometrading.gateways.outbound.exchanges.polymarket;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.gateways.outbound.fee.PredictionMarketFees;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.EpochNanoClock;

public final class PolymarketOutboundReader extends OutboundJsonWebSocketReader {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);

    // Integer flags for ParsedEvent — eliminates String allocation per WebSocket message
    private static final int EVENT_TYPE_FLAG_ORDER = 1;
    private static final int EVENT_TYPE_FLAG_TRADE = 2;

    private static final int ORDER_TYPE_FLAG_PLACEMENT = 1;
    private static final int ORDER_TYPE_FLAG_UPDATE = 2;
    private static final int ORDER_TYPE_FLAG_CANCELLATION = 3;

    private static final int STATUS_FLAG_LIVE = 1;
    private static final int STATUS_FLAG_CANCELED = 2;
    private static final int STATUS_FLAG_MATCHED = 3;
    private static final int STATUS_FLAG_FAILED = 4;
    private static final int STATUS_FLAG_MINED = 5;

    private final String apiKey;
    private final String secret;
    private final String passphrase;
    private final double takerFeeRate;
    private final double makerFeeRate;
    private final ByteBuffer pingBuffer;

    // Scratch space for parsing — reused each message
    private final ParsedEvent parsedEvent = new ParsedEvent();

    public PolymarketOutboundReader(
            Logger logger,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue,
            EpochNanoClock clock,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder,
            String apiKey,
            String secret,
            String passphrase,
            double takerFeeRate,
            double makerFeeRate) {
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
        this.apiKey = apiKey;
        this.secret = secret;
        this.passphrase = passphrase;
        this.takerFeeRate = takerFeeRate;
        this.makerFeeRate = makerFeeRate;
        this.pingBuffer = ByteBuffer.wrap(PING);
    }

    @Override
    protected boolean skipNonJsonMessage(final ByteBuffer buffer) throws IOException {
        if (isPong(buffer)) {
            buffer.position(buffer.limit());
            return true;
        }
        return false;
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonObject obj) {
        this.parsedEvent.reset();
        while (obj.hasNextKey()) {
            try (var entry = obj.nextKey()) {
                parseEventField(entry, this.parsedEvent);
            }
        }
        emitEvent(this.parsedEvent);
    }

    @Override
    protected void subscribe() throws IOException {
        // {"auth": {"apiKey": "...", "secret": "...", "passphrase": "..."}, "type": "user"}
        // We send the auth message over the WebSocket to identify this session.
        // Auth is performed via the CLOB L2 credentials (not the EIP-712 private key).
        final String authMsg = buildAuthMessage();
        final ByteBuffer authBuf = ByteBuffer.wrap(authMsg.getBytes(StandardCharsets.UTF_8));
        this.socketClient.writeMessage(Opcode.TEXT, authBuf);
    }

    @Override
    public void keepAlive() throws IOException {
        this.pingBuffer.rewind();
        this.socketClient.writeMessage(Opcode.TEXT, this.pingBuffer);
    }

    @Override
    public String roleName() {
        return "polymarket-outbound-reader";
    }

    private String buildAuthMessage() {
        return "{\"auth\":{\"apiKey\":\"" + this.apiKey
                + "\",\"secret\":\"" + this.secret
                + "\",\"passphrase\":\"" + this.passphrase
                + "\"},\"type\":\"user\"}";
    }

    private static void parseEventField(final JsonDecoder.JsonNode entry, final ParsedEvent event) {
        final GnomeString name = entry.getName();
        if (name.equals("event_type")) {
            parseEventType(entry.asString(), event);
        } else if (name.equals("type")) {
            parseOrderType(entry.asString(), event);
        } else if (name.equals("status")) {
            parseStatus(entry.asString(), event);
        } else if (name.equals("order_hash") || name.equals("hash")) {
            copyOrderHash(entry.asString(), event);
        } else if (name.equals("price")) {
            event.price = entry.asString().toFixedPointLong(group.gnometrading.schemas.Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("size")) {
            event.size = entry.asString().toFixedPointLong(group.gnometrading.schemas.Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("fee_rate_bps")) {
            event.feeRateBps = entry.asString().toFixedPointLong(1L);
        } else if (name.equals("timestamp")) {
            event.timestampEvent = entry.asString().toFixedPointLong(1L) * NANOS_PER_MILLI;
        }
    }

    private static void parseEventType(final GnomeString val, final ParsedEvent event) {
        if (val.equals("order")) {
            event.eventTypeFlag = EVENT_TYPE_FLAG_ORDER;
        } else if (val.equals("trade")) {
            event.eventTypeFlag = EVENT_TYPE_FLAG_TRADE;
        }
    }

    private static void parseOrderType(final GnomeString val, final ParsedEvent event) {
        if (val.equals("PLACEMENT")) {
            event.orderTypeFlag = ORDER_TYPE_FLAG_PLACEMENT;
        } else if (val.equals("UPDATE")) {
            event.orderTypeFlag = ORDER_TYPE_FLAG_UPDATE;
        } else if (val.equals("CANCELLATION")) {
            event.orderTypeFlag = ORDER_TYPE_FLAG_CANCELLATION;
        }
    }

    private static void parseStatus(final GnomeString val, final ParsedEvent event) {
        if (val.equals("LIVE")) {
            event.statusFlag = STATUS_FLAG_LIVE;
        } else if (val.equals("CANCELED")) {
            event.statusFlag = STATUS_FLAG_CANCELED;
        } else if (val.equals("MATCHED")) {
            event.statusFlag = STATUS_FLAG_MATCHED;
        } else if (val.equals("FAILED")) {
            event.statusFlag = STATUS_FLAG_FAILED;
        } else if (val.equals("MINED")) {
            event.statusFlag = STATUS_FLAG_MINED;
        }
    }

    private static void copyOrderHash(final GnomeString val, final ParsedEvent event) {
        event.orderHashLength = Math.min(val.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < event.orderHashLength; i++) {
            event.orderHashBytes[i] = val.byteAt(i);
        }
    }

    private void emitEvent(final ParsedEvent event) {
        final long key = computeKey(event.orderHashBytes, event.orderHashLength);
        final OrderContext ctx = findOrderContext(key);
        if (ctx == null) {
            return;
        }

        if (event.eventTypeFlag == EVENT_TYPE_FLAG_ORDER) {
            emitOrderEvent(ctx, event, key);
        } else if (event.eventTypeFlag == EVENT_TYPE_FLAG_TRADE) {
            emitTradeEvent(ctx, event, key);
        }
    }

    private void emitOrderEvent(final OrderContext ctx, final ParsedEvent event, final long key) {
        if (event.orderTypeFlag == ORDER_TYPE_FLAG_PLACEMENT && event.statusFlag == STATUS_FLAG_LIVE) {
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.NEW);
            this.execReport.encoder.orderStatus(OrderStatus.NEW);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(0);
            this.execReport.encoder.leavesQty(ctx.originalQty);
            setTimestamps(event.timestampEvent);
            publishExecReport();
        } else if (event.orderTypeFlag == ORDER_TYPE_FLAG_CANCELLATION && event.statusFlag == STATUS_FLAG_CANCELED) {
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.CANCEL);
            this.execReport.encoder.orderStatus(OrderStatus.CANCELED);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
            this.execReport.encoder.leavesQty(0);
            setTimestamps(event.timestampEvent);
            publishExecReport();
            releaseOrderContext(key);
        }
    }

    private void emitTradeEvent(final OrderContext ctx, final ParsedEvent event, final long key) {
        if (event.statusFlag == STATUS_FLAG_FAILED) {
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.CANCEL);
            this.execReport.encoder.orderStatus(OrderStatus.CANCELED);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
            this.execReport.encoder.leavesQty(0);
            setTimestamps(event.timestampEvent);
            publishExecReport();
            releaseOrderContext(key);
            return;
        }
        if (event.statusFlag != STATUS_FLAG_MATCHED) {
            // MINED/CONFIRMED are no-ops: we already reported on MATCHED
            return;
        }

        final long fillSize = event.size;
        ctx.cumulativeFilledQty += fillSize;
        ctx.leavesQty = ctx.originalQty - ctx.cumulativeFilledQty;
        final boolean fullyFilled = ctx.leavesQty <= 0;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.filledQty(fillSize);
        this.execReport.encoder.fillPrice(event.price);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(Math.max(0, ctx.leavesQty));
        setTimestamps(event.timestampEvent);
        double feeRate = event.feeRateBps > 0 ? event.feeRateBps / 10000.0 : takerFeeRate;
        this.execReport.encoder.fee(PredictionMarketFees.calculateScaledFee(event.price, fillSize, feeRate));
        publishExecReport();

        if (fullyFilled) {
            releaseOrderContext(key);
        }
    }

    private void setNullFillFields() {
        this.execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        this.execReport.encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        this.execReport.encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        this.execReport.encoder.rejectReason(group.gnometrading.schemas.RejectReason.NULL_VAL);
    }

    private void setTimestamps(final long timestampEvent) {
        this.execReport.encoder.timestampEvent(timestampEvent);
        this.execReport.encoder.timestampRecv(this.recvTimestamp);
    }

    private static boolean isPong(final ByteBuffer buffer) {
        return buffer.remaining() == 4
                && buffer.get(buffer.position()) == 'P'
                && buffer.get(buffer.position() + 1) == 'O'
                && buffer.get(buffer.position() + 2) == 'N'
                && buffer.get(buffer.position() + 3) == 'G';
    }

    private static final class ParsedEvent {
        int eventTypeFlag;
        int orderTypeFlag;
        int statusFlag;
        final byte[] orderHashBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
        int orderHashLength;
        long price;
        long size;
        long feeRateBps;
        long timestampEvent;

        void reset() {
            this.eventTypeFlag = 0;
            this.orderTypeFlag = 0;
            this.statusFlag = 0;
            this.orderHashLength = 0;
            this.price = OrderExecutionReportEncoder.fillPriceNullValue();
            this.size = OrderExecutionReportEncoder.filledQtyNullValue();
            this.feeRateBps = 0;
            this.timestampEvent = OrderExecutionReportEncoder.timestampEventNullValue();
        }
    }
}
