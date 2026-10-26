package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.time.Duration;
import java.util.List;

/** Polymarket US: what its feed and its order entry need to know about it. */
public final class PolymarketUsVenue {

    public static final VenueFeed FEED = new VenueFeed(
            PolymarketUsVenue::subjectOf,
            1000,
            // A quiet market can go a minute without a message.
            new GatewayConfig.Builder()
                    .withMaxSilentInterval(Duration.ofSeconds(60))
                    .build(),
            "polymarket.us.ws.url",
            1 << 18,
            // Whole books every message: nothing to probe.
            0);

    private PolymarketUsVenue() {}

    /** A listing's market: its slug, without {@code :long} or {@code :short}; the short listing sees the complement. */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.lastIndexOf(':');
        if (colon < 0) {
            return new SubjectRef(exchangeSecurityId, Projection.IDENTITY);
        }
        final boolean isShort = "short".equalsIgnoreCase(exchangeSecurityId.substring(colon + 1));
        return new SubjectRef(
                exchangeSecurityId.substring(0, colon), isShort ? Projection.COMPLEMENT : Projection.IDENTITY);
    }

    /** One bucket per API key for every call. Its 429 carries no Retry-After. */
    public static VenueRateLimits rateLimits() {
        return VenueRateLimits.of(
                "Polymarket US",
                List.of(new VenueRateLimits.Bucket("key", 20, 20, 0.2)),
                List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/order/", "/cancel", "key", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/order/", "/modify", "key", 1, RequestClass.ORDER),
                        new VenueRateLimits.Rule(HTTPMethod.GET, "", null, "key", 1, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(null, "", null, "key", 1, RequestClass.ORDER)),
                List.of(VenueRateLimits.RETRY_AFTER));
    }
}
