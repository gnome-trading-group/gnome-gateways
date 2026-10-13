package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.gateways.outbound.fee.PredictionMarketFees;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
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
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Reads the CLOB's authenticated {@code user} channel.
 *
 * <p>Order events carry the order's hash as {@code id}. A trade event names the taker's order in
 * {@code taker_order_id} and each resting order it matched in {@code maker_orders}, with that order's own
 * size and price; one trade can fill several of our orders, on either side. A trade is reported the first
 * time it is seen, usually as {@code MATCHED}; its {@code MINED} and {@code CONFIRMED} updates are on-chain
 * settlement afterwards, but after a reconnect they can be the first word of it.
 */
public final class PolymarketIntlOutboundReader extends OutboundJsonWebSocketReader {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int TOKEN_ID_MAX_LENGTH = 96;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    // Placed shortly before the time the writer records, by a clock that may run behind the venue's.
    private static final long LOOKUP_SLACK_SECONDS = 60;
    // The order list can lag an order placed a moment ago, so one this young is never taken for ended.
    private static final long ENDED_GRACE_MILLIS = 5_000;
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
    private final Listing listing;
    private final String conditionId;
    private final byte[] tokenIdBytes;
    // A taker fill's price and fee, as priceTakerFill worked them out; reused for every trade.
    private long takerFillPrice;
    private long takerFillFee;
    // Its own HTTP client and auth headers: the supervisor uses it while the writer uses theirs. Null to skip.
    private final PolymarketIntlVenueOrderQuery catchUpQuery;
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> venueTradePages = List.of();
    private List<VenueOrder> venueEndedOrders = List.of();

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
            PolymarketIntlMarketInfo marketInfo,
            PolymarketIntlVenueOrderQuery catchUpQuery) {
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
        this.listing = listing;
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        this.conditionId = colon >= 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
        this.tokenIdBytes = exchangeSecurityId.substring(colon + 1).getBytes(StandardCharsets.US_ASCII);
        this.catchUpQuery = catchUpQuery;
    }

    /**
     * Fetches the market's trades since just before the oldest order held or the socket went quiet, and the status of
     * every held order the venue no longer lists as open: what happened while the socket was down.
     */
    @Override
    protected void fetchVenueState() throws IOException {
        this.venueTradePages = List.of();
        this.venueEndedOrders = List.of();
        if (this.catchUpQuery == null) {
            return;
        }
        final long nowMillis = this.clock.nanoTime() / NANOS_PER_MILLI;
        // Orders the writer sent meanwhile are still queued for this reader, not among those it holds.
        final long oldestHeld = oldestOpenOrderMillis();
        final long sinceMillis = oldestHeld < 0 ? lastHeardMillis() : Math.min(oldestHeld, lastHeardMillis());
        final List<ByteBuffer> trades =
                this.catchUpQuery.tradePages(this.conditionId, sinceMillis / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS);

        final Set<String> open = new HashSet<>();
        for (final VenueOrder order : this.catchUpQuery.listOpenOrders(this.listing)) {
            open.add(order.venueId());
        }
        final List<VenueOrder> ended = new ArrayList<>();
        for (final OrderContext ctx : heldOrders()) {
            final String id =
                    new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII);
            if (!open.contains(id) && ctx.submittedAtMillis < nowMillis - ENDED_GRACE_MILLIS) {
                this.catchUpQuery
                        .getOrder(this.listing, id, 0)
                        .filter(VenueOrder::terminal)
                        .ifPresent(ended::add);
            }
        }
        this.venueTradePages = trades;
        this.venueEndedOrders = ended;
    }

    /** Trades first, through the same handling as the socket's; then the orders that ended without them. */
    @Override
    protected void applyVenueState() {
        for (final ByteBuffer page : this.venueTradePages) {
            applyTradePage(page);
        }
        for (final VenueOrder order : this.venueEndedOrders) {
            applyEnded(order);
        }
        this.venueTradePages = List.of();
        this.venueEndedOrders = List.of();
    }

    private void applyTradePage(final ByteBuffer page) {
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("data") && !entry.isNull()) {
                        applyTrades(entry);
                    }
                }
            }
        }
    }

    private void applyTrades(final JsonDecoder.JsonNode trades) {
        try (var list = trades.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var trade = item.asObject()) {
                    applyTrade(trade);
                }
            }
        }
    }

    private void applyTrade(final JsonDecoder.JsonObject trade) {
        this.parsedEvent.reset();
        while (trade.hasNextKey()) {
            try (var field = trade.nextKey()) {
                parseEventField(field, this.parsedEvent);
            }
        }
        // A REST trade carries no event type, and its time only as match_time.
        if (this.parsedEvent.timestampEvent == OrderExecutionReportEncoder.timestampEventNullValue()) {
            this.parsedEvent.timestampEvent = this.parsedEvent.matchTimeNanos;
        }
        emitTradeEvent(this.parsedEvent);
    }

    private void applyEnded(final VenueOrder order) {
        final byte[] id = order.venueId().getBytes(StandardCharsets.US_ASCII);
        final long key = computeKey(id, id.length);
        final OrderContext ctx = findOrderContext(key);
        if (ctx == null) {
            return;
        }
        if (order.filledQty() > ctx.cumulativeFilledQty) {
            // More matched than any trade we've seen: ending it now would cut off fills still to come.
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket order %s ended with fills not yet reported; left open",
                    order.venueId());
            return;
        }
        publishCanceled(ctx, key, OrderExecutionReportEncoder.timestampEventNullValue());
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
            // An IOC's killed remainder can be reported before its trades: those still to come would be dropped.
            if (!fillsPending(ctx)) {
                publishCanceled(ctx, key, event.timestampEvent);
            }
        } else if (event.orderType == ORDER_TYPE_PLACEMENT && event.status == STATUS_LIVE && !ctx.acked) {
            publishNew(
                    ctx,
                    ctx.cumulativeFilledQty,
                    ctx.originalQty - ctx.cumulativeFilledQty,
                    event.timestampEvent,
                    this.recvTimestamp);
        }
    }

    /** Whether the venue has said the order fills more than this reader has seen. */
    private static boolean fillsPending(final OrderContext ctx) {
        return ctx.awaitsFinalFill
                && ctx.finalFillQty != OrderContext.QTY_ABSENT
                && ctx.cumulativeFilledQty < ctx.finalFillQty;
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
        if (event.status == 0 || !markTradeSeen(computeKey(event.idBytes, event.idLength))) {
            return;
        }
        final long takerKey = computeKey(event.takerOrderIdBytes, event.takerOrderIdLength);
        final OrderContext taker = findOrderContext(takerKey);
        if (taker != null) {
            priceTakerFill(event);
            emitFill(taker, takerKey, event.size, this.takerFillPrice, this.takerFillFee, Liquidity.TAKER, event);
        }
        for (int i = 0; i < event.makerLegCount; i++) {
            final MakerLeg leg = event.makerLegs[i];
            final long makerKey = computeKey(leg.orderIdBytes, leg.orderIdLength);
            final OrderContext maker = findOrderContext(makerKey);
            if (maker != null) {
                emitFill(
                        maker,
                        makerKey,
                        leg.matchedAmount,
                        leg.price,
                        fee(leg.price, leg.matchedAmount, Liquidity.MAKER),
                        Liquidity.MAKER,
                        event);
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
            final long fillFee,
            final Liquidity liquidity,
            final ParsedEvent event) {
        final long timestampEvent = event.timestampEvent;
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
        final boolean done = fillsComplete(ctx);
        // An IOC the venue filled only partly ends below its size; a dollar-sized BUY can end above it.
        final boolean fullyFilled = done && ctx.cumulativeFilledQty >= ctx.originalQty;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.rejectReason(group.gnometrading.schemas.RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(fillQty);
        this.execReport.encoder.fillPrice(fillPrice);
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        this.execReport.encoder.fee(fillFee);
        this.execReport.encoder.liquidity(liquidity);
        setTimestamps(timestampEvent);
        publishExecReport();

        if (fullyFilled) {
            releaseOrderContext(key);
        } else if (done) {
            publishCanceled(ctx, key, timestampEvent);
        }
    }

    /**
     * A taker's fill is priced from the resting orders it matched, not the trade's own price, which is only the best
     * of them: the average of their prices, and the fee each charges at its own. A resting order on the other outcome
     * (matched by minting or merging) trades this token at the complement of its price.
     */
    private void priceTakerFill(final ParsedEvent event) {
        long qty = 0;
        long notional = 0;
        long rawFee = 0;
        for (int i = 0; i < event.makerLegCount; i++) {
            final MakerLeg leg = event.makerLegs[i];
            final long price = isThisToken(leg) ? leg.price : Statics.PRICE_SCALING_FACTOR - leg.price;
            qty += leg.matchedAmount;
            notional += ScaledMath.multiplyDivide(price, leg.matchedAmount, Statics.SIZE_SCALING_FACTOR);
            rawFee += rawFee(price, leg.matchedAmount);
        }
        if (qty <= 0) {
            this.takerFillPrice = event.price;
            this.takerFillFee = fee(event.price, event.size, Liquidity.TAKER);
            return;
        }
        this.takerFillPrice = (ScaledMath.multiplyDivide(2 * notional, Statics.SIZE_SCALING_FACTOR, qty) + 1) / 2;
        this.takerFillFee = roundFee(rawFee);
    }

    private boolean isThisToken(final MakerLeg leg) {
        if (leg.assetIdLength == 0) {
            return true;
        }
        if (leg.assetIdLength != this.tokenIdBytes.length) {
            return false;
        }
        for (int i = 0; i < leg.assetIdLength; i++) {
            if (leg.assetIdBytes[i] != this.tokenIdBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private long fee(final long fillPrice, final long fillQty, final Liquidity liquidity) {
        if (liquidity == Liquidity.MAKER && this.marketInfo.feeTakerOnly()) {
            return 0;
        }
        return roundFee(rawFee(fillPrice, fillQty));
    }

    private long rawFee(final long fillPrice, final long fillQty) {
        return PredictionMarketFees.calculateScaledFee(
                fillPrice, fillQty, this.marketInfo.feeRate(), this.marketInfo.feeExponent());
    }

    /** Polymarket charges fees to 5 decimal places; anything under half of 0.00001 USDC is free. */
    private static long roundFee(final long fee) {
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
        // The decoder reads a null as whatever string or array comes after it.
        if (entry.isNull()) {
            return;
        }
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
        } else if (name.equals("match_time")) {
            event.matchTimeNanos = entry.asString().toFixedPointLong(1L) * NANOS_PER_SECOND;
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
        if (field.isNull()) {
            return;
        }
        final GnomeString name = field.getName();
        if (name.equals("order_id")) {
            leg.orderIdLength = copyId(field.asString(), leg.orderIdBytes);
        } else if (name.equals("asset_id")) {
            leg.assetIdLength = copyId(field.asString(), leg.assetIdBytes);
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
        final int length = Math.min(val.length(), dest.length);
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
        // Token ids are up to 78 digits, longer than an order hash.
        final byte[] assetIdBytes = new byte[TOKEN_ID_MAX_LENGTH];
        int assetIdLength;
        long matchedAmount;
        long price;

        void reset() {
            this.orderIdLength = 0;
            this.assetIdLength = 0;
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
        long matchTimeNanos;
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
            this.matchTimeNanos = OrderExecutionReportEncoder.timestampEventNullValue();
            this.makerLegCount = 0;
        }
    }
}
