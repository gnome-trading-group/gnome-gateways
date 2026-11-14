package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.orders.CrossMarket;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.time.Duration;
import java.util.List;

/** Polymarket US: what its feed and its order entry need to know about it. */
public final class PolymarketUsVenue {

    /**
     * Its own processing time for a request, in whole milliseconds, on every answer (probed on order writes 2026-11-14:
     * a round trip less this was steady at the network's).
     */
    public static final String SERVER_LATENCY_HEADER = "x-pm-server-latency";

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
            0,
            // It closes a connection at its max connection age (1001), seen anywhere from 50 to 57 minutes.
            Duration.ofMinutes(30));

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

    /** Where a listing's orders can trade against others: its market, the same one its book is. */
    public static CrossMarket crossMarketOf(final String exchangeSecurityId) {
        final SubjectRef subject = subjectOf(exchangeSecurityId);
        return new CrossMarket(subject.key(), subject.projection() == Projection.COMPLEMENT);
    }

    /**
     * The documented 25 requests a second is counted per source IP by Cloudflare over windows short enough that a full
     * burst on top of the rate draws 429s (error code 1015), so every bucket's rate and burst together stay within 25.
     * Looking up one order by id is refused at 15 a second with nothing else sent, and at 5 beside 15 cancels, so
     * lookups have a bucket of their own at 2; every other call shares the other. A 429 carries no Retry-After.
     */
    public static VenueRateLimits rateLimits() {
        return VenueRateLimits.of(
                "Polymarket US",
                List.of(new VenueRateLimits.Bucket("key", 15, 6, 0.2), new VenueRateLimits.Bucket("lookup", 2, 2, 0)),
                List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/order/", "/cancel", "key", 1, RequestClass.CANCEL),
                        // Up to 20 to a request, counted once.
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/orders/batched/cancel", null, "key", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/orders/batched", null, "key", 1, RequestClass.NEW),
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/v1/order/", "/modify", "key", 1, RequestClass.AMEND),
                        new VenueRateLimits.Rule(
                                HTTPMethod.GET, "/v1/orders/open", null, "key", 1, RequestClass.CATCH_UP),
                        new VenueRateLimits.Rule(
                                HTTPMethod.GET, "/v1/order/", null, "lookup", 1, RequestClass.CATCH_UP),
                        new VenueRateLimits.Rule(
                                HTTPMethod.GET, "/v1/portfolio/activities", null, "key", 1, RequestClass.CATCH_UP),
                        new VenueRateLimits.Rule(HTTPMethod.GET, "", null, "key", 1, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(null, "", null, "key", 1, RequestClass.NEW)),
                List.of(VenueRateLimits.RETRY_AFTER, SERVER_LATENCY_HEADER));
    }
}
