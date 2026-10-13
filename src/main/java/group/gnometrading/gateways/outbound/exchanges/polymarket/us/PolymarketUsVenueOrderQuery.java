package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Polymarket US's side of startup recovery, and the REST lookups behind the reader's reconnect catch-up.
 *
 * <p>The ledger knows a resting order by its {@link PolymarketUsStamps stamp}, which the venue can't search by: an
 * open order is found in the market's open orders, and a finished one among the account's trades in the market,
 * which carry each side's order. Every venue id found is remembered, so the lookups recovery repeats while it waits
 * for a cancel go straight to the order. A finished order that never traded can't be found, and needs nothing booked.
 * An IOC never rests, and can't be found by its id.
 */
public final class PolymarketUsVenueOrderQuery implements VenueOrderQuery {

    private static final int HTTP_NOT_FOUND = 404;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    // Trade history is read newest first until it predates the order; this bounds a search that finds nothing.
    private static final int MAX_ACTIVITY_PAGES = 20;
    private static final int ACTIVITY_PAGE_SIZE = 100;

    private final HTTPClient httpClient;
    private final String apiHost;
    private final PolymarketUsAuthSigner signer;
    private final LongSupplier clockMillis;
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private final PolymarketUsOrderFields fields = new PolymarketUsOrderFields();
    private final PolymarketUsOrderFields aggressorFields = new PolymarketUsOrderFields();
    private final PolymarketUsOrderFields passiveFields = new PolymarketUsOrderFields();
    private final byte[] stampBuf = new byte[PolymarketUsStamps.LENGTH];
    private final Map<String, String> venueIdsByStamp = new HashMap<>();

    public PolymarketUsVenueOrderQuery(
            final HTTPClient httpClient,
            final String apiHost,
            final PolymarketUsAuthSigner signer,
            final LongSupplier clockMillis) {
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.signer = signer;
        this.clockMillis = clockMillis;
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return readOrderList(openOrdersPage(new PolymarketUsListing(listing).marketSlug()));
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        if (exchangeOrderId.startsWith("ioc-")) {
            return Optional.empty();
        }
        final long stamp = PolymarketUsStamps.parseMillis(new ViewString(exchangeOrderId));
        if (stamp == Rfc3339.INVALID) {
            return orderById(exchangeOrderId);
        }
        String venueId = this.venueIdsByStamp.get(exchangeOrderId);
        if (venueId == null) {
            venueId = findOpen(listing, exchangeOrderId);
        }
        if (venueId == null) {
            venueId = findTraded(new PolymarketUsListing(listing).marketSlug(), stamp, createdAfterMs);
        }
        return venueId == null ? Optional.empty() : orderById(venueId);
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        final byte[] body = ("{\"marketSlug\":\"" + new PolymarketUsListing(listing).marketSlug() + "\"}")
                .getBytes(StandardCharsets.US_ASCII);
        final HTTPResponse response = PolymarketUsRequests.post(
                this.httpClient,
                this.apiHost,
                this.signer,
                this.clockMillis.getAsLong(),
                new ViewString(PolymarketUsRequests.ORDER_PATH + order.venueId() + PolymarketUsRequests.CANCEL_SUFFIX),
                body,
                body.length);
        return response.isSuccess();
    }

    /** {@code {"orders":[...]}}: the market's open orders, copied out of the HTTP client's buffer. */
    ByteBuffer openOrdersPage(final String marketSlug) throws IOException {
        final HTTPResponse response = get(PolymarketUsRequests.OPEN_ORDERS_PATH + "?slugs=" + marketSlug);
        if (!response.isSuccess() || response.getBody() == null) {
            throw new IOException("Polymarket US open orders failed with status " + response.getStatusCode());
        }
        return copy(response.getBody());
    }

    /** {@code {"order":{...}}} for one order, copied out of the HTTP client's buffer; null if the venue has none. */
    ByteBuffer orderPage(final String venueId) throws IOException {
        final HTTPResponse response = get(PolymarketUsRequests.ORDER_PATH + venueId);
        if (response.getStatusCode() == HTTP_NOT_FOUND) {
            return null;
        }
        if (!response.isSuccess() || response.getBody() == null) {
            throw new IOException("Polymarket US order lookup failed with status " + response.getStatusCode());
        }
        return copy(response.getBody());
    }

    /** The venue ids of the orders in an {@link #openOrdersPage}. */
    Set<String> venueIds(final ByteBuffer page) {
        final Set<String> ids = new HashSet<>();
        for (final VenueOrder order : readOrderList(page)) {
            ids.add(order.venueId());
        }
        return ids;
    }

    private Optional<VenueOrder> orderById(final String venueId) throws IOException {
        final ByteBuffer page = orderPage(venueId);
        if (page == null) {
            return Optional.empty();
        }
        VenueOrder found = null;
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                final VenueOrder order = readOrderEntry(obj.nextKey());
                if (order != null) {
                    found = order;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private VenueOrder readOrderEntry(final JsonDecoder.JsonNode entry) {
        try (entry) {
            if (!entry.getName().equals("order") || entry.isNull()) {
                return null;
            }
            return readOrder(entry);
        }
    }

    private String findOpen(final Listing listing, final String stampText) throws IOException {
        for (final VenueOrder order : listOpenOrders(listing)) {
            if (order.exchangeOrderId().equals(stampText)) {
                return order.venueId();
            }
        }
        return null;
    }

    /** Our order's venue id from the market's trades since {@code createdAfterMs}, newest first, if it traded. */
    private String findTraded(final String marketSlug, final long stamp, final long createdAfterMs) throws IOException {
        String cursor = "";
        for (int page = 0; page < MAX_ACTIVITY_PAGES; page++) {
            final HTTPResponse response = get(PolymarketUsRequests.ACTIVITIES_PATH + "?marketSlug=" + marketSlug
                    + "&types=ACTIVITY_TYPE_TRADE&limit=" + ACTIVITY_PAGE_SIZE
                    + (cursor.isEmpty() ? "" : "&cursor=" + cursor));
            if (!response.isSuccess() || response.getBody() == null) {
                throw new IOException("Polymarket US activities failed with status " + response.getStatusCode());
            }
            final ActivityScan scan = scanActivities(response.getBody(), stamp, createdAfterMs);
            if (scan.venueId != null) {
                return scan.venueId;
            }
            if (scan.exhausted || scan.nextCursor.isEmpty()) {
                return null;
            }
            cursor = scan.nextCursor;
        }
        return null;
    }

    private ActivityScan scanActivities(final ByteBuffer body, final long stamp, final long createdAfterMs) {
        final ActivityScan scan = new ActivityScan();
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.isNull()) {
                        continue;
                    }
                    if (entry.getName().equals("activities")) {
                        scanActivityList(entry, stamp, createdAfterMs, scan);
                    } else if (entry.getName().equals("nextCursor")) {
                        scan.nextCursor = entry.asString().toString();
                    }
                }
            }
        }
        return scan;
    }

    private void scanActivityList(
            final JsonDecoder.JsonNode list, final long stamp, final long createdAfterMs, final ActivityScan scan) {
        try (var activities = list.asArray()) {
            while (activities.hasNextItem() && scan.venueId == null && !scan.exhausted) {
                scanActivity(activities.nextItem(), stamp, createdAfterMs, scan);
            }
        }
    }

    private void scanActivity(
            final JsonDecoder.JsonNode item, final long stamp, final long createdAfterMs, final ActivityScan scan) {
        try (item;
                var activity = item.asObject()) {
            while (activity.hasNextKey()) {
                try (var field = activity.nextKey()) {
                    if (field.getName().equals("trade") && !field.isNull()) {
                        scanTrade(field, stamp, createdAfterMs, scan);
                    }
                }
            }
        }
    }

    /** A trade carries both sides' orders; {@code isAggressor} says which one is ours. */
    private void scanTrade(
            final JsonDecoder.JsonNode tradeNode,
            final long stamp,
            final long createdAfterMs,
            final ActivityScan scan) {
        this.aggressorFields.reset();
        this.passiveFields.reset();
        scan.tradeIsAggressor = false;
        scan.tradeCreateNanos = Rfc3339.INVALID;
        try (var trade = tradeNode.asObject()) {
            while (trade.hasNextKey()) {
                readTradeField(trade.nextKey(), scan);
            }
        }
        if (scan.tradeCreateNanos != Rfc3339.INVALID && scan.tradeCreateNanos / NANOS_PER_MILLI < createdAfterMs) {
            scan.exhausted = true;
            return;
        }
        final PolymarketUsOrderFields ours = scan.tradeIsAggressor ? this.aggressorFields : this.passiveFields;
        if (ours.goodTillMillis == stamp && ours.orderIdLength > 0) {
            scan.venueId = new String(ours.orderId, 0, ours.orderIdLength, StandardCharsets.US_ASCII);
        }
    }

    private void readTradeField(final JsonDecoder.JsonNode field, final ActivityScan scan) {
        try (field) {
            if (field.isNull()) {
                return;
            }
            final GnomeString name = field.getName();
            if (name.equals("aggressorExecution")) {
                readExecution(field, this.aggressorFields);
            } else if (name.equals("passiveExecution")) {
                readExecution(field, this.passiveFields);
            } else if (name.equals("isAggressor")) {
                scan.tradeIsAggressor = field.asBoolean();
            } else if (name.equals("createTime")) {
                scan.tradeCreateNanos = Rfc3339.toEpochNanos(field.asString());
            }
        }
    }

    private static void readExecution(final JsonDecoder.JsonNode node, final PolymarketUsOrderFields into) {
        try (var execution = node.asObject()) {
            into.readExecution(execution);
        }
    }

    /** {@code {"orders":[...]}} as venue orders. */
    private List<VenueOrder> readOrderList(final ByteBuffer page) {
        final List<VenueOrder> orders = new ArrayList<>();
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("orders") && !entry.isNull()) {
                        readOrders(entry, orders);
                    }
                }
            }
        }
        return orders;
    }

    private void readOrders(final JsonDecoder.JsonNode node, final List<VenueOrder> orders) {
        try (var list = node.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem()) {
                    orders.add(readOrder(item));
                }
            }
        }
    }

    private VenueOrder readOrder(final JsonDecoder.JsonNode node) {
        try (var order = node.asObject()) {
            this.fields.reset();
            this.fields.readOrder(order);
        }
        return toVenueOrder(this.fields);
    }

    /**
     * Known by its stamp if it carries one of ours, else by the venue's id. Fills are valued in the terms of the side
     * the order trades: a NO order's average is the complement of the venue's long-terms price.
     */
    private VenueOrder toVenueOrder(final PolymarketUsOrderFields order) {
        final String venueId = new String(order.orderId, 0, order.orderIdLength, StandardCharsets.US_ASCII);
        String exchangeOrderId = venueId;
        if (order.goodTillMillis != Rfc3339.INVALID && PolymarketUsStamps.isStamp(order.goodTillMillis)) {
            final int length = PolymarketUsStamps.write(order.goodTillMillis, this.stampBuf, 0);
            exchangeOrderId = new String(this.stampBuf, 0, length, StandardCharsets.US_ASCII);
            this.venueIdsByStamp.put(exchangeOrderId, venueId);
        }
        final long filled = Math.max(0, order.cumQuantity);
        final boolean shortSide = order.intent == PolymarketUsOrderFields.INTENT_BUY_SHORT
                || order.intent == PolymarketUsOrderFields.INTENT_SELL_SHORT;
        final long averagePrice = filled > 0 && shortSide ? Statics.PRICE_SCALING_FACTOR - order.avgPx : order.avgPx;
        final long notional = ScaledMath.multiplyDivide(averagePrice, filled, Statics.SIZE_SCALING_FACTOR);
        return new VenueOrder(exchangeOrderId, venueId, filled, notional, order.commissionTotal, order.terminal());
    }

    private HTTPResponse get(final String path) throws IOException {
        return PolymarketUsRequests.get(this.httpClient, this.apiHost, this.signer, this.clockMillis, path);
    }

    private static ByteBuffer copy(final ByteBuffer body) {
        final ByteBuffer page = ByteBuffer.allocate(body.remaining());
        page.put(body.duplicate()).flip();
        return page;
    }

    private static final class ActivityScan {
        String venueId;
        String nextCursor = "";
        boolean exhausted;
        boolean tradeIsAggressor;
        long tradeCreateNanos;
    }
}
