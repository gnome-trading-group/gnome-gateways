package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.account.reader.JsonAccountProtocol;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.transport.WebSocketChannel;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Polymarket US's private socket for a whole key: the order subscription with no market filter, so every order on
 * the account. Events are matched as a listing's reader matches them (stamp, or an IOC's terms), and also by market,
 * since one socket carries them all.
 *
 * <p>Catch-up fetches the account's open orders and each held order the venue no longer lists, on the supervisor.
 * Orders the lane sent while the socket was down are held only once the reader has drained them, after that fetch, so
 * those it knows by id but catch-up didn't cover are looked up in a {@linkplain #wantsFollowUp follow-up}, also on
 * the supervisor: the reader never waits on the venue.
 */
public final class PolymarketUsAccountProtocol extends JsonAccountProtocol<WebSocketChannel> {

    public static final String WEBSOCKET_PATH = "/v1/ws/private";

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final byte[] SUBSCRIBE =
            "{\"subscribe\":{\"requestId\":\"orders\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\"}}"
                    .getBytes(StandardCharsets.US_ASCII);

    /** What the events need of an order's market. */
    private record Market(PolymarketUsListing listing, byte[] slug) {}

    private final Logger logger;
    private final PolymarketUsAuthSigner upgradeSigner;
    // Catch-up's own clients and signers, on the supervisor's thread and those it starts; the first lists open orders,
    // and all of them look orders up, a share each, at once.
    private final List<PolymarketUsVenueOrderQuery> catchUpQueries;
    private final EpochNanoClock clock;
    private final PolymarketUsUserEvents events;
    private final PublishedMap<Market> markets = new PublishedMap<>();
    private final PolymarketUsUserEvents.Markets marketsOfOrders = new PolymarketUsUserEvents.Markets() {
        @Override
        public boolean onListing(final OrderContext ctx, final PolymarketUsOrderFields order) {
            final Market market = market(ctx);
            return market != null
                    && order.inMarket(market.slug())
                    && market.listing().trades(order.intent);
        }

        @Override
        public long listingPrice(final OrderContext ctx, final long venuePrice) {
            final Market market = market(ctx);
            return market == null ? venuePrice : market.listing().convertPrice(venuePrice);
        }
    };

    // Venue id keys of the orders the current catch-up has applied; reader thread.
    private final LongHashSet caughtUpIds = new LongHashSet();
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> pagesFetched = List.of();
    // Ids catch-up left for a follow-up, from the reader to the supervisor; its pages back the other way.
    private List<String> followUpIds = List.of();
    private volatile boolean followUpWanted;
    private List<ByteBuffer> followUpPages = List.of();

    /**
     * @param upgradeSigner signs the socket's upgrade, on the supervisor's thread only
     * @param catchUpQueries catch-up's own clients and signers, one per lookup run at once, none sharing a client or a
     *     signer; at least one
     * @param maxOrders the most orders the account holds, for the events' index of them by venue id
     */
    public PolymarketUsAccountProtocol(
            final Logger logger,
            final PolymarketUsAuthSigner upgradeSigner,
            final List<PolymarketUsVenueOrderQuery> catchUpQueries,
            final EpochNanoClock clock,
            final int maxOrders) {
        this.logger = logger;
        this.upgradeSigner = upgradeSigner;
        if (catchUpQueries.isEmpty()) {
            throw new IllegalArgumentException("Catch-up needs at least one query");
        }
        this.catchUpQueries = List.copyOf(catchUpQueries);
        this.clock = clock;
        this.events = new PolymarketUsUserEvents(logger, maxOrders);
    }

    /** Off the hot path: a listing the account trades. */
    public void addMarket(final int exchangeId, final long securityId, final Listing listing) {
        final PolymarketUsListing venueListing = new PolymarketUsListing(listing);
        this.markets.put(
                PublishedMap.listingKey(exchangeId, securityId),
                new Market(venueListing, venueListing.marketSlug().getBytes(StandardCharsets.US_ASCII)));
    }

    private Market market(final OrderContext ctx) {
        return this.markets.get(PublishedMap.listingKey(ctx.exchangeId, ctx.securityId));
    }

    @Override
    public void authenticate(final WebSocketChannel channel) throws IOException {
        this.upgradeSigner.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WEBSOCKET_PATH);
        channel.client().setHeader(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, this.upgradeSigner.apiKey());
        channel.client().setHeader(PolymarketUsAuthSigner.TIMESTAMP_HEADER, this.upgradeSigner.timestamp());
        channel.client().setHeader(PolymarketUsAuthSigner.SIGNATURE_HEADER, this.upgradeSigner.signature());
    }

    @Override
    public void subscribe(final WebSocketChannel channel) throws IOException {
        channel.client().writeMessage(Opcode.TEXT, ByteBuffer.wrap(SUBSCRIBE));
    }

    /** The venue pings the socket every few seconds, and the transport answers. */
    @Override
    public void keepAlive(final WebSocketChannel channel) {}

    @Override
    protected void onEvent(final JsonDecoder.JsonObject event, final OrderEventSink sink) {
        if (this.events.readUpdate(event)) {
            this.events.apply(sink, this.marketsOfOrders);
        }
    }

    @Override
    public void onVenueOrderId(final OrderContext ctx, final long key) {
        this.events.onVenueOrderId(ctx);
    }

    @Override
    public void fetchCatchUp(final long sinceMillis, final Collection<OrderContext> held) throws IOException {
        this.pagesFetched = List.of();
        final List<ByteBuffer> pages = new ArrayList<>();
        final ByteBuffer open = this.catchUpQueries.get(0).accountOpenOrdersPage();
        pages.add(open);
        final Set<String> openIds = this.catchUpQueries.get(0).venueIds(open.duplicate());
        final List<String> ended = new ArrayList<>();
        for (final OrderContext ctx : held) {
            if (ctx.exchangeOrderIdLength > 0 && !openIds.contains(venueId(ctx))) {
                ended.add(venueId(ctx));
            }
        }
        // One that can't be looked up now isn't caught up, so the follow-up tries it again.
        pages.addAll(lookUp(ended));
        this.pagesFetched = pages;
    }

    /**
     * Each fetched order, open or finished, goes through the same handling as an event. Held orders known by id that
     * none of them covered were sent while the socket was down: they are left for the follow-up.
     */
    @Override
    public void applyCatchUp(final OrderEventSink sink) {
        this.caughtUpIds.clear();
        for (final ByteBuffer page : this.pagesFetched) {
            applyPage(page, sink);
        }
        this.pagesFetched = List.of();
        final List<String> ids = new ArrayList<>();
        for (final OrderContext ctx : sink.orders()) {
            if (ctx.exchangeOrderIdLength > 0
                    && !this.caughtUpIds.contains(OrderKeys.of(ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength))) {
                ids.add(venueId(ctx));
            }
        }
        if (!ids.isEmpty()) {
            this.followUpIds = ids;
            this.followUpWanted = true;
        }
    }

    @Override
    public boolean wantsFollowUp() {
        return this.followUpWanted;
    }

    @Override
    public void fetchFollowUp() {
        final List<ByteBuffer> pages = lookUp(this.followUpIds);
        this.followUpIds = List.of();
        this.followUpPages = pages;
        this.followUpWanted = false;
    }

    /**
     * Each order's page, the ids shared among the queries and looked up at once, each query on a thread of its own:
     * after a reconnect the venue may have to be asked about many orders, and their events wait on the answers. An
     * order that can't be looked up is logged and left out.
     */
    private List<ByteBuffer> lookUp(final List<String> ids) {
        final int lanes = Math.min(this.catchUpQueries.size(), ids.size());
        if (lanes <= 1) {
            return lookUpShare(this.catchUpQueries.get(0), ids, 0, 1);
        }
        final List<List<ByteBuffer>> shares = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            final List<ByteBuffer> share = new ArrayList<>();
            shares.add(share);
            final PolymarketUsVenueOrderQuery query = this.catchUpQueries.get(lane);
            final int first = lane;
            final Thread thread = new Thread(
                    () -> share.addAll(lookUpShare(query, ids, first, lanes)), "polymarket-us-catch-up-" + lane);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        final List<ByteBuffer> pages = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            try {
                threads.get(lane).join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return pages;
            }
            pages.addAll(shares.get(lane));
        }
        return pages;
    }

    private List<ByteBuffer> lookUpShare(
            final PolymarketUsVenueOrderQuery query, final List<String> ids, final int first, final int step) {
        final List<ByteBuffer> pages = new ArrayList<>();
        for (int i = first; i < ids.size(); i += step) {
            final String id = ids.get(i);
            try {
                final ByteBuffer page = query.orderPage(id);
                if (page != null) {
                    pages.add(page);
                }
            } catch (final IOException | RuntimeException e) {
                // Left out, so not caught up: the follow-up asks again.
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Polymarket US order %s couldn't be looked up after a reconnect: %s",
                        id,
                        e.toString());
            }
        }
        return pages;
    }

    @Override
    public void applyFollowUp(final OrderEventSink sink) {
        for (final ByteBuffer page : this.followUpPages) {
            applyPage(page, sink);
        }
        this.followUpPages = List.of();
    }

    private void applyPage(final ByteBuffer page, final OrderEventSink sink) {
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.isNull()) {
                        continue;
                    }
                    if (entry.getName().equals("orders")) {
                        applyOrderList(entry, sink);
                    } else if (entry.getName().equals("order")) {
                        applyOrder(entry, sink);
                    }
                }
            }
        }
    }

    private void applyOrderList(final JsonDecoder.JsonNode list, final OrderEventSink sink) {
        try (var orders = list.asArray()) {
            while (orders.hasNextItem()) {
                try (var item = orders.nextItem()) {
                    applyOrder(item, sink);
                }
            }
        }
    }

    private void applyOrder(final JsonDecoder.JsonNode node, final OrderEventSink sink) {
        try (var order = node.asObject()) {
            this.events.readListedOrder(order);
        }
        this.caughtUpIds.add(this.events.venueKey());
        this.events.apply(sink, this.marketsOfOrders);
    }

    private static String venueId(final OrderContext ctx) {
        return new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII);
    }
}
