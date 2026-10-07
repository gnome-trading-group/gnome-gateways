package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.agrona.concurrent.EpochClock;

/**
 * Kalshi's side of startup recovery. Kalshi files a listing's YES and NO sides under one ticker, and can't look an
 * order up by our client order id, so both listing and lookup go through the ticker's order list.
 *
 * <p>Orders are read with the same fields the reader takes from Kalshi's order updates.
 */
public final class KalshiVenueOrderQuery implements VenueOrderQuery {

    private static final String CANCEL_PATH = "/trade-api/v2/portfolio/events/orders/";
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
    private final KalshiOrderPages orderPages;
    // Only shards Kalshi named; a failed lookup is tried again on the next cancel.
    private final Map<String, Integer> shards = new HashMap<>();

    public KalshiVenueOrderQuery(
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochClock clock) {
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.clock = clock;
        this.orderPages = new KalshiOrderPages(httpClient, apiHost, authSigner, clock::time);
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return listOrders(listing, "&status=resting");
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        final long minSeconds = createdAfterMs / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS;
        for (final VenueOrder order : listOrders(listing, "&min_ts=" + minSeconds)) {
            if (order.exchangeOrderId().equals(exchangeOrderId)) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        final String ticker = ticker(listing);
        final String path = CANCEL_PATH + order.venueId();
        final String routing = KalshiApiUtil.routingQuery(ticker, shard(ticker));
        // Kalshi signs the path without its query.
        this.authSigner.sign(this.clock.time(), "DELETE", path);
        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.apiHost,
                path + routing,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());
        return response.isSuccess();
    }

    private int shard(final String ticker) {
        final Integer known = this.shards.get(ticker);
        if (known != null) {
            return known;
        }
        int shard;
        try {
            shard = KalshiApiUtil.fetchExchangeIndex(
                    this.httpClient, this.apiHost, this.authSigner, this.clock.time(), ticker);
        } catch (final IOException | RuntimeException e) {
            shard = KalshiApiUtil.SHARD_UNKNOWN;
        }
        if (shard >= 0) {
            this.shards.put(ticker, shard);
        }
        return shard;
    }

    private List<VenueOrder> listOrders(final Listing listing, final String filter) throws IOException {
        final String listingSide = listingSide(listing);
        final List<VenueOrder> orders = new ArrayList<>();
        for (final ByteBuffer page : this.orderPages.fetch(ticker(listing), filter)) {
            readPage(page, listingSide, orders);
        }
        return orders;
    }

    private void readPage(final ByteBuffer body, final String listingSide, final List<VenueOrder> orders) {
        try (var root = this.jsonDecoder.wrap(body);
                var page = root.asObject()) {
            while (page.hasNextKey()) {
                try (var entry = page.nextKey()) {
                    if (entry.getName().equals("orders")) {
                        readOrders(entry, listingSide, orders);
                    }
                }
            }
        }
    }

    private static void readOrders(
            final JsonDecoder.JsonNode node, final String listingSide, final List<VenueOrder> orders) {
        try (var list = node.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    orders.add(readOrder(order, listingSide));
                }
            }
        }
    }

    private static VenueOrder readOrder(final JsonDecoder.JsonObject order, final String listingSide) {
        final OrderFields fields = new OrderFields();
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                fields.read(field.getName(), field);
            }
        }
        return fields.toVenueOrder(listingSide);
    }

    private static final class OrderFields {
        String clientOrderId = "";
        String venueId = "";
        String status = "";
        String outcomeSide = "";
        long filled;
        long cost;
        long fees;

        void read(final GnomeString name, final JsonDecoder.JsonNode field) {
            if (name.equals("client_order_id")) {
                clientOrderId = field.asString().toString();
            } else if (name.equals("order_id")) {
                venueId = field.asString().toString();
            } else if (name.equals("status")) {
                status = field.asString().toString();
            } else if (name.equals("outcome_side")) {
                outcomeSide = field.asString().toString();
            } else {
                readAmount(name, field);
            }
        }

        private void readAmount(final GnomeString name, final JsonDecoder.JsonNode field) {
            if (name.equals("fill_count_fp")) {
                filled = field.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (name.equals("taker_fill_cost_dollars") || name.equals("maker_fill_cost_dollars")) {
                cost += field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (name.equals("taker_fees_dollars") || name.equals("maker_fees_dollars")) {
                fees += field.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            }
        }

        VenueOrder toVenueOrder(final String listingSide) {
            return new VenueOrder(
                    clientOrderId, venueId, filled, listingNotional(listingSide), fees, !status.equals("resting"));
        }

        /**
         * Kalshi's cost is what the side the order bought paid. An order on a listing that bought the other side (an
         * ask) traded the listing at the complement, so its notional on the listing is what the contracts are worth
         * at $1 less that cost.
         */
        private long listingNotional(final String listingSide) {
            if (outcomeSide.isEmpty() || listingSide.isEmpty() || outcomeSide.equals(listingSide)) {
                return cost;
            }
            return ScaledMath.multiplyDivide(Statics.PRICE_SCALING_FACTOR, filled, Statics.SIZE_SCALING_FACTOR) - cost;
        }
    }

    /** The side a Kalshi listing trades: "yes" or "no", or "" if its id has no suffix. */
    private static String listingSide(final Listing listing) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(colon + 1).toLowerCase() : "";
    }

    private static String ticker(final Listing listing) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return colon >= 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
    }
}
