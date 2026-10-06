package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
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
    private static final String ORDER_PATH = "/data/order/";
    private static final String CANCEL_PATH = "/order";
    private static final GnomeString CANCEL_PATH_GS = new ViewString(CANCEL_PATH);
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
        final String path = ORDER_PATH + exchangeOrderId;
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
        this.authHeaders.sign("DELETE", CANCEL_PATH, body, 0, body.length);
        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.clobHost,
                CANCEL_PATH_GS,
                body,
                body.length,
                PolymarketIntlAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketIntlAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketIntlAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketIntlAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketIntlAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());
        // The venue answers 200 even when it refuses, listing the order under not_canceled instead.
        return response.isSuccess()
                && response.getBody() != null
                && listsAsCanceled(response.getBody(), order.venueId());
    }

    private HTTPResponse get(final String signedPath, final String path) throws IOException {
        this.authHeaders.sign("GET", signedPath, null, 0, 0);
        return this.httpClient.get(
                HTTPProtocol.HTTPS,
                this.clobHost,
                path,
                PolymarketIntlAuthHeaders.API_KEY_HEADER,
                this.authHeaders.apiKey(),
                PolymarketIntlAuthHeaders.SIGNATURE_HEADER,
                this.authHeaders.signature(),
                PolymarketIntlAuthHeaders.TIMESTAMP_HEADER,
                this.authHeaders.timestamp(),
                PolymarketIntlAuthHeaders.PASSPHRASE_HEADER,
                this.authHeaders.passphrase(),
                PolymarketIntlAuthHeaders.ADDRESS_HEADER,
                this.authHeaders.address());
    }

    /** Adds the page's orders and returns the cursor to the next page. */
    private String readPage(final ByteBuffer body, final List<VenueOrder> orders) {
        String cursor = "";
        try (var root = this.jsonDecoder.wrap(body);
                var page = root.asObject()) {
            while (page.hasNextKey()) {
                try (var entry = page.nextKey()) {
                    final GnomeString name = entry.getName();
                    if (name.equals("data")) {
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

    private static boolean contains(final JsonDecoder.JsonNode node, final String venueId) {
        boolean found = false;
        try (var ids = node.asArray()) {
            while (ids.hasNextItem()) {
                try (var id = ids.nextItem()) {
                    found |= id.asString().toString().equals(venueId);
                }
            }
        }
        return found;
    }

    private static VenueOrder readOrder(final JsonDecoder.JsonObject order) {
        String id = "";
        String status = "";
        Side side = Side.None;
        long price = 0;
        long size = 0;
        long matched = 0;
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                final GnomeString name = field.getName();
                if (name.equals("id")) {
                    id = field.asString().toString();
                } else if (name.equals("status")) {
                    status = field.asString().toString();
                } else if (name.equals("side")) {
                    side = field.asString().toString().equals("BUY") ? Side.Bid : Side.Ask;
                } else if (name.equals("price")) {
                    price = field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                } else if (name.equals("original_size")) {
                    size = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                } else if (name.equals("size_matched")) {
                    matched = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                }
            }
        }
        final long notional = ScaledMath.multiplyDivide(price, matched, Statics.SIZE_SCALING_FACTOR);
        return new VenueOrder(
                id, id, side, price, size, matched, notional, 0, !WORKING_STATUSES.contains(status.toUpperCase()));
    }

    private boolean listsAsCanceled(final ByteBuffer body, final String venueId) {
        boolean canceled = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("canceled")) {
                        canceled = contains(entry, venueId);
                    }
                }
            }
        }
        return canceled;
    }

    private static String tokenId(final Listing listing) {
        // exchangeSecurityId format: "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(colon + 1) : exchangeSecurityId;
    }
}
