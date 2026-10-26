package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OutboundJsonWebSocketReader;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Reads the market's order stream on Polymarket US's private socket.
 *
 * <p>Every execution carries the whole order as it stands (state, cumulative quantity, average price, total
 * commission), so each is applied as the order's new state rather than as a delta: a redelivered or out-of-order event
 * changes nothing. Events name orders by the venue's id, which the writer learns only from a submit's answer; the
 * reader takes it from the first event it can match instead. A resting order is matched by its {@link
 * PolymarketUsStamps stamp}, an IOC by its terms, oldest first: the venue handles one writer's orders in the order it
 * sent them, and the writer never has two with the same terms and unknown outcomes out at once.
 *
 * <p>After a reconnect, the market's open orders and every held order the venue no longer lists are fetched and
 * applied the same way.
 */
public final class PolymarketUsOutboundReader extends OutboundJsonWebSocketReader {

    public static final String WEBSOCKET_PATH = "/v1/ws/private";

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NO_KEY = 0;

    private final PolymarketUsAuthSigner signer;
    private final PolymarketUsListing venueListing;
    // Its own HTTP client and signer: the supervisor uses it while the writer uses theirs. Null to skip catch-up.
    private final PolymarketUsVenueOrderQuery catchUpQuery;
    private final byte[] subscribeMessage;

    private final PolymarketUsOrderFields fields = new PolymarketUsOrderFields();
    private final byte[] stampBuf = new byte[PolymarketUsStamps.LENGTH];
    // Venue id key to correlation key, for orders an event has already been matched to.
    private final Long2LongHashMap venueKeys = new Long2LongHashMap(OrderContext.READER_POOL_SIZE * 2, 0.6f, NO_KEY);

    // Venue id keys of the orders the current catch-up has applied.
    private final LongHashSet caughtUpIds = new LongHashSet();
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> venueOrderPages = List.of();

    public PolymarketUsOutboundReader(
            final Logger logger,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final EpochNanoClock clock,
            final Listing listing,
            final WebSocketClient socketClient,
            final JsonDecoder jsonDecoder,
            final PolymarketUsAuthSigner signer,
            final PolymarketUsVenueOrderQuery catchUpQuery) {
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
        this.signer = signer;
        this.venueListing = new PolymarketUsListing(listing);
        this.catchUpQuery = catchUpQuery;
        this.subscribeMessage = ("{\"subscribe\":{\"requestId\":\"orders\",\"subscriptionType\":"
                        + "\"SUBSCRIPTION_TYPE_ORDER\",\"marketSlugs\":[\"" + this.venueListing.marketSlug() + "\"]}}")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void beforeConnect() throws IOException {
        this.signer.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WEBSOCKET_PATH);
        this.socketClient.setHeader(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, this.signer.apiKey());
        this.socketClient.setHeader(PolymarketUsAuthSigner.TIMESTAMP_HEADER, this.signer.timestamp());
        this.socketClient.setHeader(PolymarketUsAuthSigner.SIGNATURE_HEADER, this.signer.signature());
    }

    @Override
    protected void subscribe() throws IOException {
        this.socketClient.writeMessage(Opcode.TEXT, ByteBuffer.wrap(this.subscribeMessage));
    }

    @Override
    public void keepAlive() throws IOException {
        // The venue pings this socket every few seconds, and the base class answers and counts them.
    }

    @Override
    public String roleName() {
        return "polymarket-us-outbound-reader";
    }

    /**
     * Fetches the market's open orders, and each held order the venue knows by id but no longer lists: what happened
     * to them while the socket was down.
     */
    @Override
    protected void fetchVenueState() throws IOException {
        this.venueOrderPages = List.of();
        if (this.catchUpQuery == null) {
            return;
        }
        final List<ByteBuffer> pages = new ArrayList<>();
        final ByteBuffer open = this.catchUpQuery.openOrdersPage(this.venueListing.marketSlug());
        pages.add(open);
        final Set<String> openIds = this.catchUpQuery.venueIds(open.duplicate());
        for (final OrderContext ctx : heldOrders()) {
            if (ctx.exchangeOrderIdLength == 0) {
                continue;
            }
            final String id =
                    new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII);
            if (!openIds.contains(id)) {
                final ByteBuffer page = this.catchUpQuery.orderPage(id);
                if (page != null) {
                    pages.add(page);
                }
            }
        }
        this.venueOrderPages = pages;
    }

    /**
     * Each fetched order, open or finished, goes through the same handling as an event on the socket. Then any held
     * order the venue knows by id that wasn't among them is looked up here: one the writer sent while the socket was
     * down was still queued for this reader when the supervisor fetched, so only now is it held, with the id the
     * writer learned. Blocking, but only on the first pass after a reconnect.
     */
    @Override
    protected void applyVenueState() {
        if (this.catchUpQuery == null) {
            return;
        }
        this.caughtUpIds.clear();
        for (final ByteBuffer page : this.venueOrderPages) {
            applyPage(page);
        }
        this.venueOrderPages = List.of();
        for (final String id : heldIdsNotCaughtUp()) {
            try {
                final ByteBuffer page = this.catchUpQuery.orderPage(id);
                if (page != null) {
                    applyPage(page);
                }
            } catch (final IOException e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Polymarket US order %s couldn't be looked up after a reconnect: %s",
                        id,
                        e.getMessage());
            }
        }
    }

    /** Copied out first: applying an order can release it while the held orders are being iterated. */
    private List<String> heldIdsNotCaughtUp() {
        final List<String> ids = new ArrayList<>();
        for (final OrderContext ctx : heldOrders()) {
            if (ctx.exchangeOrderIdLength > 0
                    && !this.caughtUpIds.contains(computeKey(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength))) {
                ids.add(new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII));
            }
        }
        return ids;
    }

    private void applyPage(final ByteBuffer page) {
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                applyPageEntry(obj.nextKey());
            }
        }
    }

    private void applyPageEntry(final JsonDecoder.JsonNode entry) {
        try (entry) {
            if (entry.isNull()) {
                return;
            }
            if (entry.getName().equals("orders")) {
                applyOrderList(entry);
            } else if (entry.getName().equals("order")) {
                applyOrder(entry);
            }
        }
    }

    private void applyOrderList(final JsonDecoder.JsonNode list) {
        try (var orders = list.asArray()) {
            while (orders.hasNextItem()) {
                applyOrder(orders.nextItem());
            }
        }
    }

    private void applyOrder(final JsonDecoder.JsonNode node) {
        try (node;
                var order = node.asObject()) {
            this.fields.reset();
            this.fields.readOrder(order);
        }
        this.caughtUpIds.add(computeKey(this.fields.orderId, this.fields.orderIdLength));
        apply(this.fields);
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonObject obj) {
        while (obj.hasNextKey()) {
            try (var entry = obj.nextKey()) {
                if (entry.getName().equals("orderSubscriptionUpdate") && !entry.isNull()) {
                    readUpdate(entry);
                }
                // requestId, subscriptionType, heartbeat, snapshot: auto-consumed on close
            }
        }
    }

    private void readUpdate(final JsonDecoder.JsonNode update) {
        boolean sawExecution = false;
        this.fields.reset();
        try (var obj = update.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("execution") && !entry.isNull()) {
                        readExecution(entry);
                        sawExecution = true;
                    }
                }
            }
        }
        if (sawExecution) {
            apply(this.fields);
        }
    }

    private void readExecution(final JsonDecoder.JsonNode node) {
        try (var execution = node.asObject()) {
            this.fields.readExecution(execution);
        }
    }

    /** Brings the order the venue reports up to its state: fills first, then how it ended or that it rests. */
    private void apply(final PolymarketUsOrderFields order) {
        if (order.orderIdLength == 0 || !this.venueListing.trades(order.intent)) {
            return;
        }
        final long key = matchOrder(order);
        if (key == NO_KEY) {
            return;
        }
        final OrderContext ctx = findOrderContext(key);
        final long timestampEvent = eventTimestamp(order);
        if (order.cumQuantity > ctx.cumulativeFilledQty && emitFill(ctx, key, order, timestampEvent)) {
            return;
        }
        settle(ctx, key, order, timestampEvent);
    }

    /** Ends the order if the venue says it has, else acknowledges it once it rests. */
    private void settle(
            final OrderContext ctx, final long key, final PolymarketUsOrderFields order, final long timestampEvent) {
        if (order.state == PolymarketUsOrderFields.STATE_REJECTED && !ctx.acked) {
            logRejected(ctx, order);
            forget(ctx);
            publishRejected(ctx, key, timestampEvent, order.rejectReason());
        } else if (order.terminal()) {
            forget(ctx);
            publishCanceled(ctx, key, timestampEvent);
        } else if (!ctx.acked && order.state == PolymarketUsOrderFields.STATE_WORKING) {
            acknowledge(ctx, order, timestampEvent);
        }
    }

    /** The key of the held order the venue's order is, or {@link #NO_KEY} if it isn't one of ours. */
    private long matchOrder(final PolymarketUsOrderFields order) {
        final long venueKey = computeKey(order.orderId, order.orderIdLength);
        final long known = this.venueKeys.get(venueKey);
        if (known != NO_KEY) {
            if (findOrderContext(known) != null) {
                return known;
            }
            this.venueKeys.remove(venueKey);
            return NO_KEY;
        }
        final long key = order.immediate ? matchByTerms(order) : matchByStamp(order);
        if (key == NO_KEY) {
            return NO_KEY;
        }
        final OrderContext ctx = findOrderContext(key);
        if (ctx.exchangeOrderIdLength == 0) {
            ctx.exchangeOrderIdLength = order.orderIdLength;
            System.arraycopy(order.orderId, 0, ctx.exchangeOrderIdBytes, 0, order.orderIdLength);
        }
        this.venueKeys.put(venueKey, key);
        return key;
    }

    private long matchByStamp(final PolymarketUsOrderFields order) {
        if (order.goodTillMillis == Rfc3339.INVALID || !PolymarketUsStamps.isStamp(order.goodTillMillis)) {
            return NO_KEY;
        }
        final int length = PolymarketUsStamps.write(order.goodTillMillis, this.stampBuf, 0);
        final long key = computeKey(this.stampBuf, length);
        return findOrderContext(key) == null ? NO_KEY : key;
    }

    /** The oldest held IOC on the order's side at its price that no event has been matched to yet. */
    private long matchByTerms(final PolymarketUsOrderFields order) {
        final Side side = order.intent == PolymarketUsOrderFields.INTENT_BUY_LONG
                        || order.intent == PolymarketUsOrderFields.INTENT_BUY_SHORT
                ? Side.Bid
                : Side.Ask;
        OrderContext oldest = null;
        for (final OrderContext ctx : heldOrders()) {
            if (unmatchedWithTerms(ctx, side, order)
                    && (oldest == null || ctx.clientOidCounter < oldest.clientOidCounter)) {
                oldest = ctx;
            }
        }
        return oldest == null ? NO_KEY : computeKey(oldest.correlationIdBytes, oldest.correlationIdLength);
    }

    private static boolean unmatchedWithTerms(
            final OrderContext ctx, final Side side, final PolymarketUsOrderFields order) {
        return ctx.exchangeOrderIdLength == 0
                && PolymarketUsOutboundWriter.isImmediate(ctx)
                && ctx.side == side
                && ctx.limitPrice == order.price
                // The venue may cut an order's quantity (to the lot, or to what the balance covers), never raise it.
                && order.quantity <= ctx.originalQty;
    }

    @Override
    protected void onVenueOrderId(final OrderContext ctx, final long key) {
        this.venueKeys.put(computeKey(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength), key);
    }

    private void acknowledge(final OrderContext ctx, final PolymarketUsOrderFields order, final long timestampEvent) {
        if (order.quantity != OrderContext.QTY_ABSENT && order.quantity < ctx.originalQty) {
            // The venue cuts a BUY down to what the account can pay for; the order then ends at the venue's size.
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket US order %s rests for %d of %d",
                    new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                    order.quantity,
                    ctx.originalQty);
        }
        publishNew(
                ctx,
                ctx.cumulativeFilledQty,
                ctx.originalQty - ctx.cumulativeFilledQty,
                timestampEvent,
                this.recvTimestamp);
    }

    /**
     * Reports the fills that brought the order to the venue's cumulative quantity. A single new fill is priced at
     * its own price; several at once (after a reconnect) at what the venue's average says they cost together.
     *
     * @return whether the order is done and released
     */
    private boolean emitFill(
            final OrderContext ctx, final long key, final PolymarketUsOrderFields order, final long timestampEvent) {
        final long fillQty = order.cumQuantity - ctx.cumulativeFilledQty;
        final boolean single = isSingleFill(order, fillQty);
        final long venueNotional = single
                ? ScaledMath.multiplyDivide(order.lastPx, fillQty, Statics.SIZE_SCALING_FACTOR)
                : ScaledMath.multiplyDivide(order.avgPx, order.cumQuantity, Statics.SIZE_SCALING_FACTOR)
                        - ctx.cumulativeCost;
        final long venuePrice =
                single ? order.lastPx : ScaledMath.multiplyDivide(venueNotional, Statics.SIZE_SCALING_FACTOR, fillQty);
        final long fee = order.sawCommissionTotal ? order.commissionTotal - ctx.cumulativeFees : 0;
        final Liquidity liquidity =
                single ? (order.aggressor ? Liquidity.TAKER : Liquidity.MAKER) : liquidityFromFee(fee);

        if (!ctx.acked) {
            // An order that matched on arrival may never be reported as resting; acknowledge it first.
            publishNew(
                    ctx,
                    ctx.cumulativeFilledQty,
                    ctx.originalQty - ctx.cumulativeFilledQty,
                    timestampEvent,
                    this.recvTimestamp);
        }
        ctx.cumulativeFilledQty = order.cumQuantity;
        ctx.cumulativeCost += venueNotional;
        ctx.cumulativeFees += fee;
        ctx.leavesQty = Math.max(0, ctx.originalQty - ctx.cumulativeFilledQty);
        final boolean fullyFilled = ctx.cumulativeFilledQty >= ctx.originalQty;

        prepareExecReportHeader(ctx);
        this.execReport.encoder.execType(fullyFilled ? ExecType.FILL : ExecType.PARTIAL_FILL);
        this.execReport.encoder.orderStatus(fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        this.execReport.encoder.rejectReason(RejectReason.NULL_VAL);
        this.execReport.encoder.filledQty(fillQty);
        this.execReport.encoder.fillPrice(this.venueListing.convertPrice(venuePrice));
        this.execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
        this.execReport.encoder.leavesQty(ctx.leavesQty);
        this.execReport.encoder.fee(fee);
        this.execReport.encoder.liquidity(liquidity);
        this.execReport.encoder.timestampEvent(timestampEvent);
        this.execReport.encoder.timestampRecv(this.recvTimestamp);
        publishExecReport();

        if (fullyFilled) {
            forget(ctx);
            releaseOrderContext(key);
            return true;
        }
        return false;
    }

    /**
     * Takers pay and makers are paid a rebate, so a fill reported without its execution (after a reconnect) is told
     * apart by its fee. A fee rounded to nothing says neither, and several fills of both kinds net out to one sign.
     */
    private static Liquidity liquidityFromFee(final long fee) {
        if (fee > 0) {
            return Liquidity.TAKER;
        }
        return fee < 0 ? Liquidity.MAKER : Liquidity.NULL_VAL;
    }

    /** Whether the event is the one fill that took the order to its cumulative quantity, at its own price. */
    private static boolean isSingleFill(final PolymarketUsOrderFields order, final long fillQty) {
        return order.execType == PolymarketUsOrderFields.EXEC_FILL && order.lastShares == fillQty && order.lastPx > 0;
    }

    private void forget(final OrderContext ctx) {
        if (ctx.exchangeOrderIdLength > 0) {
            this.venueKeys.remove(computeKey(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength));
        }
    }

    /** Off the hot path: a rejection allocates its log line. */
    private void logRejected(final OrderContext ctx, final PolymarketUsOrderFields order) {
        logger.logf(
                LogMessage.ORDER_REJECTED_BY_VENUE,
                "Polymarket US rejected %s (venue id %s) after taking it: %s %s",
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII),
                new String(order.orderRejectReason, 0, order.orderRejectReasonLength, StandardCharsets.US_ASCII),
                new String(order.text, 0, order.textLength, StandardCharsets.UTF_8));
    }

    private static long eventTimestamp(final PolymarketUsOrderFields order) {
        return order.transactTimeNanos == Rfc3339.INVALID
                ? OrderExecutionReportEncoder.timestampEventNullValue()
                : order.transactTimeNanos;
    }
}
