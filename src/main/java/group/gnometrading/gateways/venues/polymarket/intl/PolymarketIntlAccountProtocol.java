package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.account.reader.JsonAccountProtocol;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.transport.WebSocketChannel;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Polymarket International's {@code user} channel for a whole wallet: the channel is account-wide already, so one
 * subscription carries every order. Catch-up after a reconnect applies the account's trades since just before the
 * socket went quiet, then ends any held order the venue no longer lists as open whose lookup says it finished. An
 * unlisted order too young for the list to be trusted is looked up again, as a follow-up, once it is old enough: it
 * may have ended while the socket was down, and nothing else would ever say so.
 */
public final class PolymarketIntlAccountProtocol extends JsonAccountProtocol<WebSocketChannel> {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    // Placed shortly before the time the lane records, by a clock that may run behind the venue's.
    private static final long LOOKUP_SLACK_SECONDS = 60;
    // The order list can lag an order placed a moment ago, so one this young is never taken for ended.
    private static final long ENDED_GRACE_MILLIS = 5_000;
    // A lookup that keeps failing is given up on: the order stays as last heard, and the next catch-up judges it again.
    private static final int MAX_FOLLOW_UP_TRIES = 3;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NO_TOKEN = new byte[0];

    /** What the events need of an order's market. */
    private record Market(byte[] tokenId, FeeTimeline fees) {}

    private final Logger logger;
    private final EpochNanoClock clock;
    private final byte[] subscribeMessage;
    private final PolymarketIntlVenueOrderQuery catchUpQuery;
    private final PolymarketIntlUserEvents events;
    private final PublishedMap<Market> markets = new PublishedMap<>();
    private final ByteBuffer ping = ByteBuffer.wrap(PING);
    private final PolymarketIntlUserEvents.Markets marketsOfOrders = new PolymarketIntlUserEvents.Markets() {
        @Override
        public byte[] tokenId(final OrderContext ctx) {
            final Market market = market(ctx);
            return market == null ? NO_TOKEN : market.tokenId();
        }

        @Override
        public FeeTimeline fees(final OrderContext ctx) {
            final Market market = market(ctx);
            return market == null ? FeeTimeline.EMPTY : market.fees();
        }
    };
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> tradePagesFetched = List.of();
    private List<VenueOrder> endedOrdersFetched = List.of();
    private List<VenueOrder> endedFollowUpFetched = List.of();
    // Supervisor thread: unlisted orders catch-up found too young to judge, by venue id, with when they no longer are.
    private final Map<String, Long> tooYoung = new HashMap<>();
    // Supervisor thread: follow-up lookups that failed so far, by venue id.
    private final Map<String, Integer> followUpFailures = new HashMap<>();

    /** @param catchUpQuery catch-up's own client and auth, on the supervisor's thread only */
    public PolymarketIntlAccountProtocol(
            final Logger logger,
            final String apiKey,
            final String secret,
            final String passphrase,
            final PolymarketIntlVenueOrderQuery catchUpQuery,
            final EpochNanoClock clock) {
        this.logger = logger;
        this.clock = clock;
        this.catchUpQuery = catchUpQuery;
        this.events = new PolymarketIntlUserEvents(logger);
        // Authenticated with the CLOB L2 credentials, not the order-signing key; no markets, so every market.
        this.subscribeMessage = ("{\"auth\":{\"apiKey\":\"" + apiKey + "\",\"secret\":\"" + secret
                        + "\",\"passphrase\":\"" + passphrase + "\"},\"type\":\"user\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Off the hot path: a market the account trades, by its token id as trades name it. */
    public void addMarket(
            final int exchangeId, final long securityId, final String tokenId, final FeeTimeline feeTimeline) {
        this.markets.put(
                PublishedMap.listingKey(exchangeId, securityId),
                new Market(tokenId.getBytes(StandardCharsets.US_ASCII), feeTimeline));
    }

    private Market market(final OrderContext ctx) {
        return this.markets.get(PublishedMap.listingKey(ctx.exchangeId, ctx.securityId));
    }

    @Override
    public void subscribe(final WebSocketChannel channel) throws IOException {
        channel.client().writeMessage(Opcode.TEXT, ByteBuffer.wrap(this.subscribeMessage));
    }

    /** The venue drops a socket that sends nothing; a text {@code PING} is answered {@code PONG}. */
    @Override
    public void keepAlive(final WebSocketChannel channel) {
        this.ping.rewind();
        channel.writer().sendText(this.ping, false);
    }

    @Override
    protected boolean skipNonJson(final ByteBuffer frame) {
        if (frame.remaining() == 4
                && frame.get(frame.position()) == 'P'
                && frame.get(frame.position() + 1) == 'O'
                && frame.get(frame.position() + 2) == 'N'
                && frame.get(frame.position() + 3) == 'G') {
            frame.position(frame.limit());
            return true;
        }
        return false;
    }

    /** An event's time for fee schedules and trade dedup is when its frame arrived, which the sink already has. */
    @Override
    protected void onEvent(final JsonDecoder.JsonObject event, final OrderEventSink sink) {
        this.events.read(event);
        this.events.apply(sink, this.marketsOfOrders, sink.receiveNanos());
    }

    @Override
    public void fetchCatchUp(final long sinceMillis, final Collection<OrderContext> held) throws IOException {
        this.tradePagesFetched = List.of();
        this.endedOrdersFetched = List.of();
        final long nowMillis = this.clock.nanoTime() / NANOS_PER_MILLI;
        final List<ByteBuffer> trades =
                this.catchUpQuery.accountTradePages(sinceMillis / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS);
        final Set<String> open = new HashSet<>();
        for (final VenueOrder order : this.catchUpQuery.listAccountOpenOrders()) {
            open.add(order.venueId());
        }
        final List<VenueOrder> ended = new ArrayList<>();
        for (final OrderContext ctx : held) {
            final String id =
                    new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII);
            if (open.contains(id)) {
                this.tooYoung.remove(id);
            } else if (ctx.submittedAtMillis < nowMillis - ENDED_GRACE_MILLIS) {
                try {
                    this.catchUpQuery
                            .getOrder(null, id, 0)
                            .filter(VenueOrder::terminal)
                            .ifPresent(ended::add);
                } catch (final IOException e) {
                    // One order the venue can't answer for must not fail the connect, which would fail again on every
                    // reconnect: the follow-up asks about it later.
                    retryFollowUp(id, nowMillis, e);
                }
            } else {
                this.tooYoung.put(id, ctx.submittedAtMillis + ENDED_GRACE_MILLIS);
            }
        }
        this.tradePagesFetched = trades;
        this.endedOrdersFetched = ended;
    }

    /** Once an order catch-up found too young is old enough to judge. */
    @Override
    public boolean wantsFollowUp() {
        if (this.tooYoung.isEmpty()) {
            return false;
        }
        final long nowMillis = this.clock.nanoTime() / NANOS_PER_MILLI;
        for (final long judgeableAt : this.tooYoung.values()) {
            if (judgeableAt <= nowMillis) {
                return true;
            }
        }
        return false;
    }

    /** Looks up the orders now old enough, as catch-up would have: still unlisted and finished means ended. */
    @Override
    public void fetchFollowUp() throws IOException {
        final long nowMillis = this.clock.nanoTime() / NANOS_PER_MILLI;
        final List<String> due = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : this.tooYoung.entrySet()) {
            if (entry.getValue() <= nowMillis) {
                due.add(entry.getKey());
            }
        }
        final Set<String> open = new HashSet<>();
        try {
            for (final VenueOrder order : this.catchUpQuery.listAccountOpenOrders()) {
                open.add(order.venueId());
            }
        } catch (final IOException e) {
            // Without the listing no order can be judged; each waits a grace rather than being asked about every pass.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR, "Polymarket: open orders for the follow-up failed: %s", e.getMessage());
            for (final String id : due) {
                this.tooYoung.put(id, nowMillis + ENDED_GRACE_MILLIS);
            }
            this.endedFollowUpFetched = List.of();
            return;
        }
        final List<VenueOrder> ended = new ArrayList<>();
        for (final String id : due) {
            if (open.contains(id)) {
                forgetFollowUp(id);
                continue;
            }
            try {
                this.catchUpQuery
                        .getOrder(null, id, 0)
                        .filter(VenueOrder::terminal)
                        .ifPresent(ended::add);
                forgetFollowUp(id);
            } catch (final IOException e) {
                // One order's failing lookup must not hold up the others', nor, retried every pass, fail the
                // account's events: it waits a grace before trying again, and is given up on after a few tries.
                retryFollowUp(id, nowMillis, e);
            }
        }
        this.endedFollowUpFetched = ended;
    }

    private void forgetFollowUp(final String id) {
        this.tooYoung.remove(id);
        this.followUpFailures.remove(id);
    }

    private void retryFollowUp(final String id, final long nowMillis, final IOException failure) {
        final int failures = this.followUpFailures.merge(id, 1, Integer::sum);
        if (failures >= MAX_FOLLOW_UP_TRIES) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket order %s: gave up looking it up after %d tries, the last: %s",
                    id,
                    failures,
                    failure);
            forgetFollowUp(id);
            return;
        }
        this.tooYoung.put(id, nowMillis + ENDED_GRACE_MILLIS);
    }

    @Override
    public void applyFollowUp(final OrderEventSink sink) {
        for (final VenueOrder order : this.endedFollowUpFetched) {
            applyEnded(order, sink);
        }
        this.endedFollowUpFetched = List.of();
    }

    /** Trades first, through the same handling as the socket's; then the orders that ended without them. */
    @Override
    public void applyCatchUp(final OrderEventSink sink) {
        for (final ByteBuffer page : this.tradePagesFetched) {
            applyTradePage(page, sink);
        }
        for (final VenueOrder order : this.endedOrdersFetched) {
            applyEnded(order, sink);
        }
        this.tradePagesFetched = List.of();
        this.endedOrdersFetched = List.of();
    }

    private void applyTradePage(final ByteBuffer page, final OrderEventSink sink) {
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("data") && !entry.isNull()) {
                        applyTrades(entry, sink);
                    }
                }
            }
        }
    }

    private void applyTrades(final JsonDecoder.JsonNode trades, final OrderEventSink sink) {
        try (var list = trades.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var trade = item.asObject()) {
                    this.events.readListedTrade(trade);
                    this.events.apply(sink, this.marketsOfOrders, this.clock.nanoTime());
                }
            }
        }
    }

    private void applyEnded(final VenueOrder order, final OrderEventSink sink) {
        final byte[] id = order.venueId().getBytes(StandardCharsets.US_ASCII);
        final OrderContext ctx = sink.order(OrderKeys.of(id, id.length));
        if (ctx == null) {
            return;
        }
        if (order.filledQty() > ctx.cumulativeFilledQty) {
            // More matched than any trade we've seen: ending it now would cut off fills still to come.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Polymarket order %s ended with fills not yet reported; left open",
                    order.venueId());
            return;
        }
        sink.cancel(ctx, OrderExecutionReportEncoder.timestampEventNullValue());
    }
}
