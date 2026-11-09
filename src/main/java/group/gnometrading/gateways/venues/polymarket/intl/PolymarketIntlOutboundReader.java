package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final long MILLIS_PER_SECOND = 1_000L;
    // Placed shortly before the time the writer records, by a clock that may run behind the venue's.
    private static final long LOOKUP_SLACK_SECONDS = 60;
    // The order list can lag an order placed a moment ago, so one this young is never taken for ended.
    private static final long ENDED_GRACE_MILLIS = 5_000;
    private static final byte[] PING = "PING".getBytes(StandardCharsets.US_ASCII);

    private final String apiKey;
    private final String secret;
    private final String passphrase;
    private final FeeTimeline feeTimeline;
    private final ByteBuffer pingBuffer;
    private final Listing listing;
    private final String conditionId;
    private final byte[] tokenIdBytes;
    private final PolymarketIntlUserEvents events;
    // A listing's reader holds only its own listing's orders.
    private final PolymarketIntlUserEvents.Markets markets = new PolymarketIntlUserEvents.Markets() {
        @Override
        public byte[] tokenId(final OrderContext ctx) {
            return PolymarketIntlOutboundReader.this.tokenIdBytes;
        }

        @Override
        public FeeTimeline fees(final OrderContext ctx) {
            return PolymarketIntlOutboundReader.this.feeTimeline;
        }
    };
    // Its own HTTP client and auth headers: the supervisor uses it while the writer uses theirs. Null to skip.
    private final PolymarketIntlVenueOrderQuery catchUpQuery;
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> venueTradePages = List.of();
    private List<VenueOrder> venueEndedOrders = List.of();

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
            FeeTimeline feeTimeline,
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
        this.feeTimeline = feeTimeline;
        this.pingBuffer = ByteBuffer.wrap(PING);
        this.listing = listing;
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        this.conditionId = colon >= 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
        this.tokenIdBytes = exchangeSecurityId.substring(colon + 1).getBytes(StandardCharsets.US_ASCII);
        this.catchUpQuery = catchUpQuery;
        this.events = new PolymarketIntlUserEvents(logger);
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
        this.events.readListedTrade(trade);
        this.events.apply(this, this.markets, this.clock.nanoTime());
    }

    private void applyEnded(final VenueOrder order) {
        final byte[] id = order.venueId().getBytes(StandardCharsets.US_ASCII);
        final long key = OrderKeys.of(id, id.length);
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
        this.events.read(obj);
        this.events.apply(this, this.markets, this.clock.nanoTime());
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

    private static boolean isPong(final ByteBuffer buffer) {
        return buffer.remaining() == 4
                && buffer.get(buffer.position()) == 'P'
                && buffer.get(buffer.position() + 1) == 'O'
                && buffer.get(buffer.position() + 2) == 'N'
                && buffer.get(buffer.position() + 3) == 'G';
    }
}
