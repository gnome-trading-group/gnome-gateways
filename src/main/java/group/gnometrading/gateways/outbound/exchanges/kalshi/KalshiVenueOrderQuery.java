package group.gnometrading.gateways.outbound.exchanges.kalshi;

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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.agrona.concurrent.EpochClock;

/**
 * Kalshi's side of startup recovery. Kalshi files a listing's YES and NO sides under one ticker, and can't look an
 * order up by our client order id, so both listing and lookup go through the ticker's order list.
 *
 * <p>Orders are read with the same fields the reader takes from Kalshi's order updates. Side and price are left
 * unknown: recovery identifies Kalshi orders by their client order id, never by their terms.
 */
public final class KalshiVenueOrderQuery implements VenueOrderQuery {

    private static final String ORDERS_PATH = "/trade-api/v2/portfolio/orders";
    private static final String CANCEL_PATH = "/trade-api/v2/portfolio/events/orders/";
    private static final int PAGE_LIMIT = 200;
    // Placed shortly before the time given, by a clock that may run a little behind Kalshi's.
    private static final long LOOKUP_SLACK_SECONDS = 60;
    private static final long MILLIS_PER_SECOND = 1_000;

    private static final String HEADER_KEY = "KALSHI-ACCESS-KEY";
    private static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    private static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";

    private final HTTPClient httpClient;
    private final String apiHost;
    private final KalshiAuthSigner authSigner;
    private final EpochClock clock;
    private final JsonDecoder jsonDecoder = new JsonDecoder();

    public KalshiVenueOrderQuery(
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochClock clock) {
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.clock = clock;
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return listOrders(ticker(listing), "&status=resting");
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        final long minSeconds = createdAfterMs / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS;
        for (final VenueOrder order : listOrders(ticker(listing), "&min_ts=" + minSeconds)) {
            if (order.exchangeOrderId().equals(exchangeOrderId)) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        final String path = CANCEL_PATH + order.venueId();
        this.authSigner.sign(this.clock.time(), "DELETE", path);
        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.apiHost,
                path,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());
        return response.isSuccess();
    }

    private List<VenueOrder> listOrders(final String ticker, final String filter) throws IOException {
        final List<VenueOrder> orders = new ArrayList<>();
        String cursor = "";
        do {
            final String path = ORDERS_PATH + "?ticker=" + ticker + filter + "&limit=" + PAGE_LIMIT
                    + (cursor.isEmpty() ? "" : "&cursor=" + cursor);
            // Kalshi signs the path without its query string.
            this.authSigner.sign(this.clock.time(), "GET", ORDERS_PATH);
            final HTTPResponse response = this.httpClient.get(
                    HTTPProtocol.HTTPS,
                    this.apiHost,
                    path,
                    HEADER_KEY,
                    this.authSigner.apiKey(),
                    HEADER_TIMESTAMP,
                    this.authSigner.timestamp(),
                    HEADER_SIGNATURE,
                    this.authSigner.signature());
            if (!response.isSuccess() || response.getBody() == null) {
                throw new IOException("Kalshi order list failed with status " + response.getStatusCode());
            }
            cursor = readPage(response.getBody(), orders);
        } while (!cursor.isEmpty());
        return orders;
    }

    /** Adds the page's orders and returns the cursor to the next page, or "" after the last. */
    private String readPage(final ByteBuffer body, final List<VenueOrder> orders) {
        String cursor = "";
        try (var root = this.jsonDecoder.wrap(body);
                var page = root.asObject()) {
            while (page.hasNextKey()) {
                try (var entry = page.nextKey()) {
                    final GnomeString name = entry.getName();
                    if (name.equals("orders")) {
                        readOrders(entry, orders);
                    } else if (name.equals("cursor")) {
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
        final OrderFields fields = new OrderFields();
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                fields.read(field.getName(), field);
            }
        }
        return fields.toVenueOrder();
    }

    private static final class OrderFields {
        String clientOrderId = "";
        String venueId = "";
        String status = "";
        long filled;
        long remaining;
        long cost;
        long fees;

        void read(final GnomeString name, final JsonDecoder.JsonNode field) {
            if (name.equals("client_order_id")) {
                clientOrderId = field.asString().toString();
            } else if (name.equals("order_id")) {
                venueId = field.asString().toString();
            } else if (name.equals("status")) {
                status = field.asString().toString();
            } else {
                readAmount(name, field);
            }
        }

        private void readAmount(final GnomeString name, final JsonDecoder.JsonNode field) {
            if (name.equals("fill_count_fp")) {
                filled = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (name.equals("remaining_count_fp")) {
                remaining = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (name.equals("taker_fill_cost_dollars") || name.equals("maker_fill_cost_dollars")) {
                cost += field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (name.equals("taker_fees_dollars") || name.equals("maker_fees_dollars")) {
                fees += field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            }
        }

        VenueOrder toVenueOrder() {
            return new VenueOrder(
                    clientOrderId,
                    venueId,
                    Side.None,
                    0,
                    filled + remaining,
                    filled,
                    cost,
                    fees,
                    !status.equals("resting"));
        }
    }

    private static String ticker(final Listing listing) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
    }
}
