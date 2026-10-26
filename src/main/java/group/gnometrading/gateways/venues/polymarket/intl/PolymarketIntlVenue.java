package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.time.Duration;
import java.util.List;

/** Polymarket International: what its feed and its order entry need to know about it. */
public final class PolymarketIntlVenue {

    public static final VenueFeed FEED = new VenueFeed(
            PolymarketIntlVenue::subjectOf,
            // Down to a tenth of a cent tick: 999 prices a side.
            1000,
            // A quiet market can go a minute without a message.
            new GatewayConfig.Builder()
                    .withMaxSilentInterval(Duration.ofSeconds(60))
                    .build(),
            "polymarket.intl.ws.url",
            // A subscribe of 100 tokens is answered with one array of their books.
            1 << 22,
            300);

    private PolymarketIntlVenue() {}

    /** A listing's market: the outcome token of its {@code condition_id:token_id}; each token is its own book. */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.indexOf(':');
        return new SubjectRef(
                colon < 0 ? exchangeSecurityId : exchangeSecurityId.substring(colon + 1), Projection.IDENTITY);
    }

    /**
     * The standard tier, per signer: an order bucket and a separate cancel bucket. Reads are left to Cloudflare, which
     * delays rather than refuses them. Its order and cancel responses report what is left of the bucket, and a 429
     * says when to retry.
     */
    public static VenueRateLimits rateLimits() {
        return VenueRateLimits.of(
                "Polymarket International",
                List.of(
                        new VenueRateLimits.Bucket("order", 40, 60, 0),
                        new VenueRateLimits.Bucket("cancel", 80, 120, 0)),
                List.of(
                        new VenueRateLimits.Rule(HTTPMethod.POST, "/order", null, "order", 1, RequestClass.ORDER),
                        new VenueRateLimits.Rule(HTTPMethod.DELETE, "", null, "cancel", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(HTTPMethod.GET, "", null, null, 0, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(null, "", null, "order", 1, RequestClass.ORDER)),
                List.of(VenueRateLimits.RETRY_AFTER, VenueRateLimits.POLY_REMAINING, VenueRateLimits.POLY_RESET));
    }
}
