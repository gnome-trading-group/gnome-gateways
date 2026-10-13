package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
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
        final List<VenueOrder> orders = new ArrayList<>();
        String cursor = "";
        do {
            final String path =
                    ORDERS_PATH + "?asset_id=" + tokenId(listing) + (cursor.isEmpty() ? "" : "&next_cursor=" + cursor);
            final HTTPResponse response = get(ORDERS_PATH, path);
            if (!response.isSuccess() || response.getBody() == null) {
                throw new IOException("Polymarket order list failed with status " + response.getStatusCode());
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
            throw new IOException("Polymarket order lookup failed with status " + response.getStatusCode());
        }
        try (var root = this.jsonDecoder.wrap(response.getBody());
                var order = root.asObject()) {
            return Optional.of(readOrder(order));
        }
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        final byte[] body = ("{\"orderID\":\"" + order.venueId() + "\"}").getBytes(StandardCharsets.US_ASCII);
        final HTTPResponse response =
                PolymarketIntlRequests.cancel(this.httpClient, this.clobHost, this.authHeaders, body, body.length);
        final byte[] id = order.venueId().getBytes(StandardCharsets.US_ASCII);
        return PolymarketIntlRequests.listsAsCanceled(this.jsonDecoder, response, id, id.length);
    }

    /**
     * Every page of this account's trades in a market since {@code afterSeconds}, as the CLOB returns them, each
     * copied out of the response so the HTTP client's buffer can be reused. Trades are by market, not token: one of
     * our orders can be matched by a taker on the other outcome.
     */
    List<ByteBuffer> tradePages(final String conditionId, final long afterSeconds) throws IOException {
        final List<ByteBuffer> pages = new ArrayList<>();
        String cursor = "";
        do {
            final String path = TRADES_PATH + "?market=" + conditionId + "&after=" + afterSeconds
                    + (cursor.isEmpty() ? "" : "&next_cursor=" + cursor);
            final HTTPResponse response = get(TRADES_PATH, path);
            if (!response.isSuccess() || response.getBody() == null) {
                throw new IOException("Polymarket trade list failed with status " + response.getStatusCode());
            }
            final ByteBuffer body = response.getBody();
            final ByteBuffer page = ByteBuffer.allocate(body.remaining());
            page.put(body.duplicate()).flip();
            pages.add(page);
            cursor = readPage(page.duplicate(), null);
        } while (!cursor.isEmpty() && !cursor.equals(END_CURSOR));
        return pages;
    }

    private HTTPResponse get(final String signedPath, final String path) throws IOException {
        return PolymarketIntlRequests.get(this.httpClient, this.clobHost, this.authHeaders, signedPath, path);
    }

    /** Adds the page's orders, unless {@code orders} is null, and returns the cursor to the next page. */
    private String readPage(final ByteBuffer body, final List<VenueOrder> orders) {
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

    private static void readOrders(final JsonDecoder.JsonNode node, final List<VenueOrder> orders) {
        try (var list = node.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    orders.add(readOrder(order));
                }
            }
        }
    }

    private static VenueOrder readOrder(final JsonDecoder.JsonObject order) {
        String id = "";
        String status = "";
        long price = 0;
        long matched = 0;
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                // The decoder reads a null as whatever string comes after it.
                if (field.isNull()) {
                    continue;
                }
                final GnomeString name = field.getName();
                if (name.equals("id")) {
                    id = field.asString().toString();
                } else if (name.equals("status")) {
                    status = field.asString().toString();
                } else if (name.equals("price")) {
                    price = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (name.equals("size_matched")) {
                    matched = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                }
            }
        }
        final long notional = ScaledMath.multiplyDivide(price, matched, Statics.SIZE_SCALING_FACTOR);
        return new VenueOrder(id, id, matched, notional, 0, !WORKING_STATUSES.contains(status.toUpperCase()));
    }

    private static String tokenId(final Listing listing) {
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(colon + 1) : exchangeSecurityId;
    }
}
