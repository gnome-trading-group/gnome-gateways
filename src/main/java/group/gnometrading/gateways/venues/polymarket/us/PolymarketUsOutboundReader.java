package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private final PolymarketUsAuthSigner signer;
    private final PolymarketUsListing venueListing;
    // Its own HTTP client and signer: the supervisor uses it while the writer uses theirs. Null to skip catch-up.
    private final PolymarketUsVenueOrderQuery catchUpQuery;
    private final byte[] subscribeMessage;

    private final PolymarketUsUserEvents events;
    // A listing's reader holds only its own listing's orders, and sees the other side of its market too.
    private final PolymarketUsUserEvents.Markets markets = new PolymarketUsUserEvents.Markets() {
        @Override
        public boolean onListing(final OrderContext ctx, final PolymarketUsOrderFields order) {
            return PolymarketUsOutboundReader.this.venueListing.trades(order.intent);
        }

        @Override
        public long listingPrice(final OrderContext ctx, final long venuePrice) {
            return PolymarketUsOutboundReader.this.venueListing.convertPrice(venuePrice);
        }
    };

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
        this.events = new PolymarketUsUserEvents(logger, OrderContext.READER_POOL_SIZE);
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
                    && !this.caughtUpIds.contains(OrderKeys.of(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength))) {
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
            this.events.readListedOrder(order);
        }
        this.caughtUpIds.add(this.events.venueKey());
        this.events.apply(this, this.markets);
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonObject obj) {
        if (this.events.readUpdate(obj)) {
            this.events.apply(this, this.markets);
        }
    }

    @Override
    protected void onVenueOrderId(final OrderContext ctx, final long key) {
        this.events.onVenueOrderId(ctx);
    }
}
