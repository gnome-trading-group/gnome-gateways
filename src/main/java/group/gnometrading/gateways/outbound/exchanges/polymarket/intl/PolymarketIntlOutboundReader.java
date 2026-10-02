package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.gateways.outbound.fee.PredictionMarketFees;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Reads the CLOB's authenticated {@code user} channel.
 *
 * <p>Order events carry the order's hash as {@code id}. A trade event names the taker's order in
 * {@code taker_order_id} and each resting order it matched in {@code maker_orders}, with that order's own
 * size and price; one trade can fill several of our orders, on either side. Fills are reported when the
 * trade is {@code MATCHED}; {@code MINED} and {@code CONFIRMED} are on-chain settlement afterwards.
 */
public final class PolymarketIntlOutboundReader extends OutboundJsonWebSocketReader {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long FEE_INCREMENT = Statics.PRICE_SCALING_FACTOR / 100_000;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);

    // More maker orders than this in one trade are dropped; a market maker has at most one per side.
    private static final int MAX_MAKER_LEGS = 32;

    // The venue redelivers MATCHED trades (e.g. on reconnect); a redelivery lands well inside this window.
    private static final int SEEN_TRADE_CAPACITY = 4096;

    private static final int EVENT_TYPE_ORDER = 1;
    private static final int EVENT_TYPE_TRADE = 2;

    private static final int ORDER_TYPE_PLACEMENT = 1;
    private static final int ORDER_TYPE_UPDATE = 2;
    private static final int ORDER_TYPE_CANCELLATION = 3;

    private static final int STATUS_LIVE = 1;
    private static final int STATUS_CANCELED = 2;
    private static final int STATUS_MATCHED = 3;
    private static final int STATUS_FAILED = 4;
    private static final int STATUS_OTHER = 5;

    private final String apiKey;
    private final String secret;
    private final String passphrase;
    private final PolymarketIntlMarketInfo marketInfo;
    private final ByteBuffer pingBuffer;

    private final ParsedEvent parsedEvent = new ParsedEvent();

    // Doubled so the set's load-factor threshold stays above the window and it never rehashes.
    private final LongHashSet seenTradeKeys = new LongHashSet(2 * SEEN_TRADE_CAPACITY);
    private final long[] seenTradeRing = new long[SEEN_TRADE_CAPACITY];
    private int seenTradeHead;
    private int seenTradeCount;

    public PolymarketIntlOutboundReader(
            Logger logger,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ManyToOneRingBuffer<OrderContext> newOrderQueue,
            ManyToOneRingBuffer<OrderContext> writerReportQueue,
            ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            EpochNanoClock clock,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder,
            String apiKey,
            String secret,
            String passphrase,
            PolymarketIntlMarketInfo marketInfo) {
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
        this.apiKey = apiKey;
        this.secret = secret;
        this.passphrase = passphrase;
        this.marketInfo = marketInfo;
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
        if (this.parsedEvent.eventType == EVENT_TYPE_ORDER) {
            emitOrderEvent(this.parsedEvent);
        } else if (this.parsedEvent.eventType == EVENT_TYPE_TRADE) {
            emitTradeEvent(this.parsedEvent);
        }
    }

    @Override
    protected void subscribe() throws IOException {
        // Authenticated with the CLOB L2 credentials, not the order-signing key.
        final String authMsg = "{\"auth\":{\"apiKey\":\"" + this.apiKey
                + "\",\"secret\":\"" + this.secret
                + "\",\"passphrase\":\"" + this.passphrase
                + "\"},\"type\":\"user\"}";
        this.socketClient.writeMessage(Opcode.TEXT, ByteBuffer.wrap(authMsg.getBytes(StandardCharsets.UTF_8)));
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

    private void emitOrderEvent(final ParsedEvent event) {
        final long key = computeKey(event.idBytes, event.idLength);
        final OrderContext ctx = findOrderContext(key);
        if (ctx == null) {
            return;
        }
        if (event.orderType == ORDER_TYPE_CANCELLATION || event.status == STATUS_CANCELED) {
            prepareExecReportHeader(ctx);
            this.execReport.encoder.execType(ExecType.CANCEL);
            this.execReport.encoder.orderStatus(OrderStatus.CANCELED);
            setNullFillFields();
            this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
            this.execReport.encoder.leavesQty(0);
            setTimestamps(event.timestampEvent);
            publishExecReport();
            releaseOrderContext(key);
        } else if (event.orderType == ORDER_TYPE_PLACEMENT && event.status == STATUS_LIVE && !ctx.acked) {
            publishNew(
                    ctx,
                    ctx.cumulativeFilledQty,
                    ctx.originalQty - ctx.cumulativeFilledQty,
                    event.timestampEvent,
                    this.recvTimestamp);
        }
    }

    private void emitTradeEvent(final ParsedEvent event) {
        if (event.status == STATUS_FAILED) {
            // The match was reversed on-chain after we reported its fill. There is no execution type for
            // a busted trade yet, so the position is now overstated; flag it loudly for reconciliation.
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket trade %s failed settlement; its reported fill did not happen",
                    new String(event.idBytes, 0, event.idLength, StandardCharsets.US_ASCII));
            return;
        }
        if (event.status != STATUS_MATCHED || !markTradeSeen(computeKey(event.idBytes, event.idLength))) {
            return;
        }
        final long takerKey = computeKey(event.takerOrderIdBytes, event.takerOrderIdLength);
        final OrderContext taker = findOrderContext(takerKey);
        if (taker != null) {
            emitFill(taker, takerKey, event.size, event.price, Liquidity.TAKER, event.timestampEvent);
        }
        for (int i = 0; i < event.makerLegCount; i++) {
            final MakerLeg leg = event.makerLegs[i];
            final long makerKey = computeKey(leg.orderIdBytes, leg.orderIdLength);
            final OrderContext maker = findOrderContext(makerKey);
            if (maker != null) {
                emitFill(maker, makerKey, leg.matchedAmount, leg.price, Liquidity.MAKER, event.timestampEvent);
            }
        }
    }

    /** Records the trade, evicting the oldest once full; false if it was already recorded. */
    private boolean markTradeSeen(final long tradeKey) {
        if (!this.seenTradeKeys.add(tradeKey)) {
            return false;
        }
        if (this.seenTradeCount == SEEN_TRADE_CAPACITY) {
            this.seenTradeKeys.remove(this.seenTradeRing[this.seenTradeHead]);
        } else {
            this.seenTradeCount++;
        }
        this.seenTradeRing[this.seenTradeHead] = tradeKey;
        this.seenTradeHead = (this.seenTradeHead + 1) % SEEN_TRADE_CAPACITY;
        return true;
    }

    private void emitFill(
            final OrderContext ctx,
            final long key,
            final long fillQty,
            final long fillPrice,
            final Liquidity liquidity,
            final long timestampEvent) {
        if (!ctx.acked) {
            // An order that matched on arrival may never be reported as resting; acknowledge it first.
            publishNew(
                    ctx,
                    ctx.cumulativeFilledQty,
                    ctx.originalQty - ctx.cumulativeFilledQty,
                    timestampEvent,
                    this.recvTimestamp);
        }
        ctx.cumulativeFilledQty += fillQty;
        ctx.leavesQty = Math.max(0, ctx.originalQty - ctx.cumulativeFilledQty);
        final boolean fullyFilled = ctx.leavesQty == 0;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.rejectReason(group.gnometrading.schemas.RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(fillQty);
        this.execReport.encoder.fillPrice(fillPrice);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        this.execReport.encoder.fee(fee(fillPrice, fillQty, liquidity));
        this.execReport.encoder.liquidity(liquidity);
        setTimestamps(timestampEvent);
        publishExecReport();

        if (fullyFilled) {
            releaseOrderContext(key);
        }
    }

    private long fee(final long fillPrice, final long fillQty, final Liquidity liquidity) {
        if (liquidity == Liquidity.MAKER && this.marketInfo.feeTakerOnly()) {
            return 0;
        }
        final long fee = PredictionMarketFees.calculateScaledFee(
                fillPrice, fillQty, this.marketInfo.feeRate(), this.marketInfo.feeExponent());
        // Polymarket charges fees to 5 decimal places; anything under half of 0.00001 USDC is free.
        return (fee + FEE_INCREMENT / 2) / FEE_INCREMENT * FEE_INCREMENT;
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

    private static void parseEventField(final JsonDecoder.JsonNode entry, final ParsedEvent event) {
        final GnomeString name = entry.getName();
        if (name.equals("event_type")) {
            final GnomeString val = entry.asString();
            if (val.equals("order")) {
                event.eventType = EVENT_TYPE_ORDER;
            } else if (val.equals("trade")) {
                event.eventType = EVENT_TYPE_TRADE;
            }
        } else if (name.equals("type")) {
            event.orderType = parseOrderType(entry.asString());
        } else if (name.equals("status")) {
            event.status = parseStatus(entry.asString());
        } else {
            parseDetailField(name, entry, event);
        }
    }

    private static void parseDetailField(
            final GnomeString name, final JsonDecoder.JsonNode entry, final ParsedEvent event) {
        if (name.equals("id")) {
            event.idLength = copyId(entry.asString(), event.idBytes);
        } else if (name.equals("taker_order_id")) {
            event.takerOrderIdLength = copyId(entry.asString(), event.takerOrderIdBytes);
        } else if (name.equals("price")) {
            event.price = entry.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("size")) {
            event.size = entry.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("timestamp")) {
            event.timestampEvent = entry.asString().toFixedPointLong(1L) * NANOS_PER_MILLI;
        } else if (name.equals("maker_orders")) {
            parseMakerOrders(entry, event);
        }
    }

    private static void parseMakerOrders(final JsonDecoder.JsonNode entry, final ParsedEvent event) {
        try (var legs = entry.asArray()) {
            while (legs.hasNextItem()) {
                try (var item = legs.nextItem();
                        var legObj = item.asObject()) {
                    if (event.makerLegCount < MAX_MAKER_LEGS) {
                        final MakerLeg leg = event.makerLegs[event.makerLegCount++];
                        leg.reset();
                        parseMakerLeg(legObj, leg);
                    }
                }
            }
        }
    }

    private static void parseMakerLeg(final JsonDecoder.JsonObject legObj, final MakerLeg leg) {
        while (legObj.hasNextKey()) {
            try (var field = legObj.nextKey()) {
                parseMakerLegField(field, leg);
            }
        }
    }

    private static void parseMakerLegField(final JsonDecoder.JsonNode field, final MakerLeg leg) {
        final GnomeString name = field.getName();
        if (name.equals("order_id")) {
            leg.orderIdLength = copyId(field.asString(), leg.orderIdBytes);
        } else if (name.equals("matched_amount")) {
            leg.matchedAmount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("price")) {
            leg.price = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        }
    }

    private static int parseOrderType(final GnomeString val) {
        if (val.equals("PLACEMENT")) {
            return ORDER_TYPE_PLACEMENT;
        } else if (val.equals("UPDATE")) {
            return ORDER_TYPE_UPDATE;
        } else if (val.equals("CANCELLATION")) {
            return ORDER_TYPE_CANCELLATION;
        }
        return 0;
    }

    private static int parseStatus(final GnomeString val) {
        if (val.equals("LIVE")) {
            return STATUS_LIVE;
        } else if (val.equals("CANCELED")) {
            return STATUS_CANCELED;
        } else if (val.equals("MATCHED")) {
            return STATUS_MATCHED;
        } else if (val.equals("FAILED")) {
            return STATUS_FAILED;
        }
        return STATUS_OTHER;
    }

    private static int copyId(final GnomeString val, final byte[] dest) {
        final int length = Math.min(val.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < length; i++) {
            dest[i] = val.byteAt(i);
        }
        return length;
    }

    private static boolean isPong(final ByteBuffer buffer) {
        return buffer.remaining() == 4
                && buffer.get(buffer.position()) == 'P'
                && buffer.get(buffer.position() + 1) == 'O'
                && buffer.get(buffer.position() + 2) == 'N'
                && buffer.get(buffer.position() + 3) == 'G';
    }

    private static final class MakerLeg {
        final byte[] orderIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
        int orderIdLength;
        long matchedAmount;
        long price;

        void reset() {
            this.orderIdLength = 0;
            this.matchedAmount = 0;
            this.price = 0;
        }
    }

    private static final class ParsedEvent {
        int eventType;
        int orderType;
        int status;
        final byte[] idBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
        int idLength;
        final byte[] takerOrderIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
        int takerOrderIdLength;
        long price;
        long size;
        long timestampEvent;
        final MakerLeg[] makerLegs = new MakerLeg[MAX_MAKER_LEGS];
        int makerLegCount;

        ParsedEvent() {
            for (int i = 0; i < MAX_MAKER_LEGS; i++) {
                this.makerLegs[i] = new MakerLeg();
            }
        }

        void reset() {
            this.eventType = 0;
            this.orderType = 0;
            this.status = 0;
            this.idLength = 0;
            this.takerOrderIdLength = 0;
            this.price = OrderExecutionReportEncoder.fillPriceNullValue();
            this.size = 0;
            this.timestampEvent = OrderExecutionReportEncoder.timestampEventNullValue();
            this.makerLegCount = 0;
        }
    }
}
