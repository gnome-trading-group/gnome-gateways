package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.RequestFailure;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Polymarket International's side of startup recovery. Each listing is one outcome token, and an order is known by
 * its hash both to us and to the CLOB.
 *
 * <p>An order reports how much matched but not at what prices, so its fills are valued at its limit price: the
 * price a resting order fills at. Fees are left at 0, since the order doesn't report them.
 */
public final class PolymarketIntlVenueOrderQuery implements VenueOrderQuery {

    private static final String ORDERS_PATH = "/data/orders";
    private static final String TRADES_PATH = "/data/trades";
    // The cursor the CLOB returns after the last page.
    private static final String END_CURSOR = "LTE=";
    private static final int HTTP_NOT_FOUND = 404;
    private static final long MILLIS_PER_SECOND = 1_000;
    // Statuses of an order that can still fill; anything else (matched, cancelled) has finished.
    private static final Set<String> WORKING_STATUSES = Set.of("LIVE", "DELAYED", "UNMATCHED");

    private final HTTPClient httpClient;
    private final String clobHost;
    private final PolymarketIntlAuthHeaders authHeaders;
    private final JsonDecoder jsonDecoder = new JsonDecoder();

    public PolymarketIntlVenueOrderQuery(
            final HTTPClient httpClient, final String clobHost, final PolymarketIntlAuthHeaders authHeaders) {
        this.httpClient = httpClient;
        this.clobHost = clobHost;
        this.authHeaders = authHeaders;
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return venueOrders(openOrders("?asset_id=" + tokenId(listing)));
    }

    /** Every open order on the account, in any market. */
    List<VenueOrder> listAccountOpenOrders() throws IOException {
        return venueOrders(openOrders(""));
    }

    @Override
    public List<AccountOrder> listAllOpenOrders() throws IOException {
        final List<OrderFields> read = openOrders("");
        final List<AccountOrder> orders = new ArrayList<>(read.size());
        for (final OrderFields fields : read) {
            orders.add(new AccountOrder(fields.toVenueOrder(), fields.tokenId, fields.createdMillis()));
        }
        return orders;
    }

    private static List<VenueOrder> venueOrders(final List<OrderFields> read) {
        final List<VenueOrder> orders = new ArrayList<>(read.size());
        for (final OrderFields fields : read) {
            orders.add(fields.toVenueOrder());
        }
        return orders;
    }

    /** @param filter the query without its cursor: empty, or {@code ?name=value...} */
    private List<OrderFields> openOrders(final String filter) throws IOException {
        final List<OrderFields> orders = new ArrayList<>();
        String cursor = "";
        do {
            final String path = ORDERS_PATH + filter + cursorParameter(filter, cursor);
            final HTTPResponse response = get(ORDERS_PATH, path);
            if (!response.isSuccess() || response.getBody() == null) {
                throw RequestFailure.of("Polymarket order list", response);
            }
            cursor = readPage(response.getBody(), orders);
        } while (!cursor.isEmpty() && !cursor.equals(END_CURSOR));
        return orders;
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        final String path = PolymarketIntlRequests.ORDER_LOOKUP_PATH + exchangeOrderId;
        final HTTPResponse response = get(path, path);
        if (response.getStatusCode() == HTTP_NOT_FOUND) {
            return Optional.empty();
        }
        if (!response.isSuccess() || response.getBody() == null) {
            throw RequestFailure.of("Polymarket order lookup", response);
        }
        try (var root = this.jsonDecoder.wrap(response.getBody())) {
            // An order the CLOB doesn't know is answered 200 with a bare null, not 404.
            if (root.isNull()) {
                return Optional.empty();
            }
            try (var order = root.asObject()) {
                return Optional.of(readOrder(order).toVenueOrder());
            }
        } catch (final RuntimeException e) {
            // The decoder trusts its input; a body that isn't an order object is the venue's fault, not ours.
            throw new IOException("Polymarket order lookup answered something unreadable: " + e, e);
        }
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        return cancel(order.venueId());
    }

    /** The CLOB cancels by the order's hash alone, so the market isn't needed. */
    @Override
    public boolean cancel(final AccountOrder order) throws IOException {
        return cancel(order.order().venueId());
    }

    private boolean cancel(final String venueId) throws IOException {
        final byte[] body = ("{\"orderID\":\"" + venueId + "\"}").getBytes(StandardCharsets.US_ASCII);
        final HTTPResponse response =
                PolymarketIntlRequests.cancel(this.httpClient, this.clobHost, this.authHeaders, body, body.length);
        final byte[] id = venueId.getBytes(StandardCharsets.US_ASCII);
        return PolymarketIntlRequests.listsAsCanceled(this.jsonDecoder, response, id, id.length);
    }

    /**
     * Every page of this account's trades in a market since {@code afterSeconds}, as the CLOB returns them, each
     * copied out of the response so the HTTP client's buffer can be reused. Trades are by market, not token: one of
     * our orders can be matched by a taker on the other outcome.
     */
    List<ByteBuffer> tradePages(final String conditionId, final long afterSeconds) throws IOException {
        return trades("?market=" + conditionId + "&after=" + afterSeconds);
    }

    /** As {@link #tradePages}, for every market on the account. */
    List<ByteBuffer> accountTradePages(final long afterSeconds) throws IOException {
        return trades("?after=" + afterSeconds);
    }

    private List<ByteBuffer> trades(final String filter) throws IOException {
        final List<ByteBuffer> pages = new ArrayList<>();
        String cursor = "";
        do {
            final String path = TRADES_PATH + filter + cursorParameter(filter, cursor);
            final HTTPResponse response = get(TRADES_PATH, path);
            if (!response.isSuccess() || response.getBody() == null) {
                throw RequestFailure.of("Polymarket trade list", response);
            }
            final ByteBuffer body = response.getBody();
            final ByteBuffer page = ByteBuffer.allocate(body.remaining());
            page.put(body.duplicate()).flip();
            pages.add(page);
            cursor = readPage(page.duplicate(), null);
        } while (!cursor.isEmpty() && !cursor.equals(END_CURSOR));
        return pages;
    }

    private static String cursorParameter(final String filter, final String cursor) {
        if (cursor.isEmpty()) {
            return "";
        }
        return (filter.isEmpty() ? "?" : "&") + "next_cursor=" + cursor;
    }

    private HTTPResponse get(final String signedPath, final String path) throws IOException {
        return PolymarketIntlRequests.get(this.httpClient, this.clobHost, this.authHeaders, signedPath, path);
    }

    /** Adds the page's orders, unless {@code orders} is null, and returns the cursor to the next page. */
    private String readPage(final ByteBuffer body, final List<OrderFields> orders) {
        String cursor = "";
        try (var root = this.jsonDecoder.wrap(body);
                var page = root.asObject()) {
            while (page.hasNextKey()) {
                try (var entry = page.nextKey()) {
                    final GnomeString name = entry.getName();
                    if (entry.isNull()) {
                        continue;
                    }
                    if (name.equals("data") && orders != null) {
                        readOrders(entry, orders);
                    } else if (name.equals("next_cursor")) {
                        cursor = entry.isNull() ? "" : entry.asString().toString();
                    }
                }
            }
        }
        return cursor;
    }

    private static void readOrders(final JsonDecoder.JsonNode node, final List<OrderFields> orders) {
        try (var list = node.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    orders.add(readOrder(order));
                }
            }
        }
    }

    private static OrderFields readOrder(final JsonDecoder.JsonObject order) {
        final OrderFields fields = new OrderFields();
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                // The decoder reads a null as whatever string comes after it.
                if (!field.isNull()) {
                    fields.read(field.getName(), field);
                }
            }
        }
        return fields;
    }

    private static final class OrderFields {
        String id = "";
        String status = "";
        String tokenId = "";
        long price;
        long matched;
        long createdSeconds;

        void read(final GnomeString name, final JsonDecoder.JsonNode field) {
            if (name.equals("id")) {
                id = field.asString().toString();
            } else if (name.equals("status")) {
                status = field.asString().toString();
            } else if (name.equals("asset_id")) {
                tokenId = field.asString().toString();
            } else if (name.equals("price")) {
                price = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (name.equals("size_matched")) {
                matched = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (name.equals("created_at")) {
                createdSeconds = seconds(field.asRawJson().toString());
            }
        }

        /** 0 if the CLOB didn't say. */
        long createdMillis() {
            return createdSeconds * MILLIS_PER_SECOND;
        }

        VenueOrder toVenueOrder() {
            final long notional = ScaledMath.multiplyDivide(price, matched, Statics.SIZE_SCALING_FACTOR);
            return new VenueOrder(id, id, matched, notional, 0, !WORKING_STATUSES.contains(status.toUpperCase()));
        }

        // Read raw, so a quoted number parses as well as a bare one.
        private static long seconds(final String raw) {
            final String digits = raw.replace("\"", "").trim();
            try {
                return digits.isEmpty() ? 0 : Long.parseLong(digits);
            } catch (final NumberFormatException e) {
                return 0;
            }
        }
    }

    private static String tokenId(final Listing listing) {
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(colon + 1) : exchangeSecurityId;
    }
}
