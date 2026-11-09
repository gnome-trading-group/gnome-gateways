package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.fee.FeeSchedule;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ScaledMath;
import java.nio.charset.StandardCharsets;
import org.agrona.collections.LongHashSet;

/**
 * Polymarket International's {@code user} channel events and what they mean for an order, without I/O: an order or
 * trade event from the socket, or a trade from the REST listing, parsed and then applied to the orders it is for
 * through an {@link OrderEventSink}. The channel is account-wide already, so one instance serves a listing's reader or
 * an account's. Not thread-safe: one per reading thread. Nothing here allocates on the event path.
 *
 * <p>Order events carry the order's hash as {@code id}. A trade event names the taker's order in
 * {@code taker_order_id} and each resting order it matched in {@code maker_orders}, with that order's own
 * size and price; one trade can fill several of our orders, on either side. A trade is reported the first
 * time it is seen, usually as {@code MATCHED}; its {@code MINED} and {@code CONFIRMED} updates are on-chain
 * settlement afterwards, but after a reconnect they can be the first word of it.
 */
public final class PolymarketIntlUserEvents {

    /** An order's own outcome token and fee schedules, which a trade's pricing and fees depend on. */
    public interface Markets {
        /** The order's token id, as the venue writes it in a trade's maker legs. */
        byte[] tokenId(OrderContext ctx);

        FeeTimeline fees(OrderContext ctx);
    }

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int TOKEN_ID_MAX_LENGTH = 96;
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

    private final Logger logger;

    private int eventType;
    private int orderType;
    private int status;
    private final byte[] idBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int idLength;
    private final byte[] takerOrderIdBytes = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int takerOrderIdLength;
    private long price;
    private long size;
    // What an order event says has matched in all; QTY_ABSENT when it doesn't say.
    private long sizeMatched;
    private long timestampEvent;
    private long matchTimeNanos;
    private final MakerLeg[] makerLegs = new MakerLeg[MAX_MAKER_LEGS];
    private int makerLegCount;

    // A taker fill's price and fee, as priceTakerFill worked them out; reused for every trade.
    private long takerFillPrice;
    private long takerFillFee;

    // Doubled so the set's load-factor threshold stays above the window and it never rehashes.
    private final LongHashSet seenTradeKeys = new LongHashSet(2 * SEEN_TRADE_CAPACITY);
    private final long[] seenTradeRing = new long[SEEN_TRADE_CAPACITY];
    private int seenTradeHead;
    private int seenTradeCount;

    public PolymarketIntlUserEvents(final Logger logger) {
        this.logger = logger;
        for (int i = 0; i < MAX_MAKER_LEGS; i++) {
            this.makerLegs[i] = new MakerLeg();
        }
    }

    /** Reads a socket message's top-level object, ready to {@link #apply}. */
    public void read(final JsonDecoder.JsonObject message) {
        reset();
        while (message.hasNextKey()) {
            try (var entry = message.nextKey()) {
                readField(entry);
            }
        }
    }

    /** Reads one trade of a REST listing, ready to {@link #apply}. */
    public void readListedTrade(final JsonDecoder.JsonObject trade) {
        reset();
        while (trade.hasNextKey()) {
            try (var field = trade.nextKey()) {
                readField(field);
            }
        }
        // A REST trade carries no event type, and its time only as match_time.
        this.eventType = EVENT_TYPE_TRADE;
        if (this.timestampEvent == OrderExecutionReportEncoder.timestampEventNullValue()) {
            this.timestampEvent = this.matchTimeNanos;
        }
    }

    /** Applies the event read last to the orders {@code sink} holds that it is for. */
    public void apply(final OrderEventSink sink, final Markets markets, final long nowNanos) {
        if (this.eventType == EVENT_TYPE_ORDER) {
            applyOrderEvent(sink);
        } else if (this.eventType == EVENT_TYPE_TRADE) {
            applyTrade(sink, markets, nowNanos);
        }
    }

    private void applyOrderEvent(final OrderEventSink sink) {
        final OrderContext ctx = sink.order(OrderKeys.of(this.idBytes, this.idLength));
        if (ctx == null) {
            return;
        }
        if (this.orderType == ORDER_TYPE_CANCELLATION || this.status == STATUS_CANCELED) {
            if (this.sizeMatched != OrderContext.QTY_ABSENT && this.sizeMatched > ctx.cumulativeFilledQty) {
                // It matched more than the trades seen: those still to come (late, or from catch-up after a socket
                // gap) end it, at what it matched, rather than being dropped on an order already ended.
                ctx.awaitsFinalFill = true;
                ctx.finalFillQty = this.sizeMatched;
            }
            // An IOC's killed remainder can be reported before its trades: those still to come would be dropped.
            if (!ctx.fillsPending()) {
                sink.cancel(ctx, this.timestampEvent);
            }
        } else if (this.orderType == ORDER_TYPE_PLACEMENT && this.status == STATUS_LIVE && !ctx.acked) {
            sink.acknowledge(
                    ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, this.timestampEvent);
        }
    }

    private void applyTrade(final OrderEventSink sink, final Markets markets, final long nowNanos) {
        if (this.status == STATUS_FAILED) {
            // The match was reversed on-chain after we reported its fill. There is no execution type for
            // a busted trade yet, so the position is now overstated; flag it loudly for reconciliation.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket trade %s failed settlement; its reported fill did not happen",
                    new String(this.idBytes, 0, this.idLength, StandardCharsets.US_ASCII));
            return;
        }
        if (this.status == 0 || !markTradeSeen(OrderKeys.of(this.idBytes, this.idLength))) {
            return;
        }
        final OrderContext taker = sink.order(OrderKeys.of(this.takerOrderIdBytes, this.takerOrderIdLength));
        if (taker != null) {
            priceTakerFill(taker, markets, nowNanos);
            fill(sink, taker, this.size, this.takerFillPrice, this.takerFillFee, Liquidity.TAKER);
        }
        for (int i = 0; i < this.makerLegCount; i++) {
            final MakerLeg leg = this.makerLegs[i];
            final OrderContext maker = sink.order(OrderKeys.of(leg.orderIdBytes, leg.orderIdLength));
            if (maker != null) {
                final long fee =
                        fee(markets.fees(maker), nowNanos, maker.side == Side.Bid, leg.price, leg.matchedAmount, true);
                fill(sink, maker, leg.matchedAmount, leg.price, fee, Liquidity.MAKER);
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

    private void fill(
            final OrderEventSink sink,
            final OrderContext ctx,
            final long fillQty,
            final long fillPrice,
            final long fillFee,
            final Liquidity liquidity) {
        if (!ctx.acked) {
            // An order that matched on arrival may never be reported as resting; acknowledge it first.
            sink.acknowledge(
                    ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, this.timestampEvent);
        }
        ctx.cumulativeFilledQty += fillQty;
        ctx.leavesQty = Math.max(0, ctx.originalQty - ctx.cumulativeFilledQty);
        final boolean done = ctx.fillsComplete();
        // An IOC the venue filled only partly ends below its size; a dollar-sized BUY can end above it.
        final boolean fullyFilled = done && ctx.cumulativeFilledQty >= ctx.originalQty;
        sink.fill(ctx, fillQty, fillPrice, fillFee, liquidity, fullyFilled, this.timestampEvent);
        if (fullyFilled) {
            sink.release(ctx);
        } else if (done) {
            sink.cancel(ctx, this.timestampEvent);
        }
    }

    /**
     * A taker's fill is priced from the resting orders it matched, not the trade's own price, which is only the best
     * of them: the average of their prices, and the fee each charges at its own. A resting order on the other outcome
     * (matched by minting or merging) trades this token at the complement of its price.
     */
    private void priceTakerFill(final OrderContext taker, final Markets markets, final long nowNanos) {
        final boolean buy = taker.side == Side.Bid;
        final FeeTimeline fees = markets.fees(taker);
        final byte[] tokenId = markets.tokenId(taker);
        long qty = 0;
        long notional = 0;
        long rawFeePico = 0;
        final FeeSchedule schedule = fees.at(nowNanos);
        for (int i = 0; i < this.makerLegCount; i++) {
            final MakerLeg leg = this.makerLegs[i];
            final long legPrice = leg.isToken(tokenId) ? leg.price : Statics.PRICE_SCALING_FACTOR - leg.price;
            qty += leg.matchedAmount;
            notional += ScaledMath.multiplyDivide(legPrice, leg.matchedAmount, Statics.SIZE_SCALING_FACTOR);
            if (schedule != null) {
                rawFeePico += schedule.rawPico(buy, legPrice, leg.matchedAmount, false);
            }
        }
        if (qty <= 0) {
            this.takerFillPrice = this.price;
            this.takerFillFee = fee(fees, nowNanos, buy, this.price, this.size, false);
            return;
        }
        this.takerFillPrice = (ScaledMath.multiplyDivide(2 * notional, Statics.SIZE_SCALING_FACTOR, qty) + 1) / 2;
        this.takerFillFee = schedule == null ? 0 : schedule.roundFill(rawFeePico);
    }

    /**
     * A fill's fee from the market's schedule in the registry, which carries the rate Polymarket set when it listed
     * the market and its rounding to $0.00001; nothing when no schedule is known.
     */
    private static long fee(
            final FeeTimeline fees,
            final long nowNanos,
            final boolean buy,
            final long fillPrice,
            final long fillQty,
            final boolean maker) {
        final FeeSchedule schedule = fees.at(nowNanos);
        if (schedule == null) {
            return 0;
        }
        return schedule.fee(buy, fillPrice, fillQty, maker, null);
    }

    private void readField(final JsonDecoder.JsonNode entry) {
        // The decoder reads a null as whatever string or array comes after it.
        if (entry.isNull()) {
            return;
        }
        final GnomeString name = entry.getName();
        if (name.equals("event_type")) {
            final GnomeString val = entry.asString();
            if (val.equals("order")) {
                this.eventType = EVENT_TYPE_ORDER;
            } else if (val.equals("trade")) {
                this.eventType = EVENT_TYPE_TRADE;
            }
        } else if (name.equals("type")) {
            this.orderType = readOrderType(entry.asString());
        } else if (name.equals("status")) {
            this.status = readStatus(entry.asString());
        } else {
            readDetailField(name, entry);
        }
    }

    private void readDetailField(final GnomeString name, final JsonDecoder.JsonNode entry) {
        if (name.equals("id")) {
            this.idLength = copyId(entry.asString(), this.idBytes);
        } else if (name.equals("taker_order_id")) {
            this.takerOrderIdLength = copyId(entry.asString(), this.takerOrderIdBytes);
        } else if (name.equals("price")) {
            this.price = entry.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
        } else if (name.equals("size")) {
            this.size = entry.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("size_matched")) {
            this.sizeMatched = entry.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        } else if (name.equals("timestamp")) {
            this.timestampEvent = entry.asString().toFixedPointLong(1L) * NANOS_PER_MILLI;
        } else if (name.equals("match_time")) {
            this.matchTimeNanos = entry.asString().toFixedPointLong(1L) * NANOS_PER_SECOND;
        } else if (name.equals("maker_orders")) {
            readMakerOrders(entry);
        }
    }

    private void readMakerOrders(final JsonDecoder.JsonNode entry) {
        try (var legs = entry.asArray()) {
            while (legs.hasNextItem()) {
                try (var item = legs.nextItem();
                        var legObj = item.asObject()) {
                    if (this.makerLegCount < MAX_MAKER_LEGS) {
                        final MakerLeg leg = this.makerLegs[this.makerLegCount++];
                        leg.reset();
                        leg.read(legObj);
                    }
                }
            }
        }
    }

    private static int readOrderType(final GnomeString val) {
        if (val.equals("PLACEMENT")) {
            return ORDER_TYPE_PLACEMENT;
        } else if (val.equals("UPDATE")) {
            return ORDER_TYPE_UPDATE;
        } else if (val.equals("CANCELLATION")) {
            return ORDER_TYPE_CANCELLATION;
        }
        return 0;
    }

    private static int readStatus(final GnomeString val) {
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

    private void reset() {
        this.eventType = 0;
        this.orderType = 0;
        this.status = 0;
        this.idLength = 0;
        this.takerOrderIdLength = 0;
        this.price = OrderExecutionReportEncoder.fillPriceNullValue();
        this.size = 0;
        this.sizeMatched = OrderContext.QTY_ABSENT;
        this.timestampEvent = OrderExecutionReportEncoder.timestampEventNullValue();
        this.matchTimeNanos = OrderExecutionReportEncoder.timestampEventNullValue();
        this.makerLegCount = 0;
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

        void read(final JsonDecoder.JsonObject legObj) {
            while (legObj.hasNextKey()) {
                try (var field = legObj.nextKey()) {
                    if (field.isNull()) {
                        continue;
                    }
                    final GnomeString name = field.getName();
                    if (name.equals("order_id")) {
                        this.orderIdLength = copyId(field.asString(), this.orderIdBytes);
                    } else if (name.equals("asset_id")) {
                        this.assetIdLength = copyId(field.asString(), this.assetIdBytes);
                    } else if (name.equals("matched_amount")) {
                        this.matchedAmount = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    } else if (name.equals("price")) {
                        this.price = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    }
                }
            }
        }

        /** Whether the leg trades {@code tokenId}; a leg that doesn't say is taken to. */
        boolean isToken(final byte[] tokenId) {
            if (this.assetIdLength == 0) {
                return true;
            }
            if (this.assetIdLength != tokenId.length) {
                return false;
            }
            for (int i = 0; i < this.assetIdLength; i++) {
                if (this.assetIdBytes[i] != tokenId[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
