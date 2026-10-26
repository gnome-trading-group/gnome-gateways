package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.util.List;

/** Kalshi: what its feed and its order entry need to know about it. */
public final class KalshiVenue {

    public static final VenueFeed FEED = new VenueFeed(
            KalshiVenue::subjectOf,
            // Prices in tenths of a cent: 999 a side.
            1000,
            new GatewayConfig.Builder().build(),
            "kalshi.ws.url",
            1 << 18,
            // The Basic tier reads 20 a second; the probe takes a small share of one.
            300);

    private static final String ORDERS = "/trade-api/v2/portfolio/events/orders";

    private KalshiVenue() {}

    /**
     * A listing's market: its ticker, without the {@code :yes} or {@code :no} a binary market's two listings carry.
     * One subscription covers both; the NO listing sees the complement.
     */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.indexOf(':');
        if (colon < 0) {
            return new SubjectRef(exchangeSecurityId, Projection.IDENTITY);
        }
        final boolean no = "no".equalsIgnoreCase(exchangeSecurityId.substring(colon + 1));
        return new SubjectRef(exchangeSecurityId.substring(0, colon), no ? Projection.COMPLEMENT : Projection.IDENTITY);
    }

    /**
     * The Basic tier: separate read and write token buckets per account. Orders cost 10 tokens, cancels 2, everything
     * else 10. Its 429 carries no Retry-After.
     */
    public static VenueRateLimits rateLimits() {
        return VenueRateLimits.of(
                "Kalshi",
                List.of(
                        new VenueRateLimits.Bucket("write", 100, 100, 0.2),
                        new VenueRateLimits.Bucket("read", 200, 200, 0)),
                List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, ORDERS + "/", "/amend", "write", 10, RequestClass.ORDER),
                        new VenueRateLimits.Rule(HTTPMethod.POST, ORDERS, null, "write", 10, RequestClass.ORDER),
                        new VenueRateLimits.Rule(
                                HTTPMethod.DELETE, ORDERS + "/", null, "write", 2, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(HTTPMethod.GET, "", null, "read", 10, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(null, "", null, "write", 10, RequestClass.ORDER)),
                List.of(VenueRateLimits.RETRY_AFTER));
    }
}
