package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import group.gnometrading.networking.http.HTTPMethod;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VenueRateLimitsTest {

    @Test
    void polymarketUsStaysWithinWhatCloudflareAllowsOneAddressASecond() {
        final VenueRateLimits limits = PolymarketUsVenue.rateLimits();
        double perSecond = 0;
        for (final VenueRateLimits.Bucket bucket : limits.buckets()) {
            perSecond += bucket.tokensPerSecond() + bucket.burst();
        }

        assertTrue(perSecond <= 25, "every bucket full and spending at its rate: " + perSecond);
    }

    @Test
    void polymarketUsLooksOrdersUpOnTheirOwnBucketAndSendsEverythingElseOnTheOther() {
        final VenueRateLimits limits = PolymarketUsVenue.rateLimits();
        final RequestRules rules = new RequestRules(limits);
        final int lookup = rules.bucket(rules.classify(HTTPMethod.GET, "/v1/order/42"));
        final int key = rules.bucket(rules.classify(HTTPMethod.POST, "/v1/orders"));

        assertEquals("lookup", limits.buckets().get(lookup).name());
        assertEquals("key", limits.buckets().get(key).name());
        for (final String path :
                new String[] {"/v1/orders/batched", "/v1/orders/batched/cancel", "/v1/order/42/cancel"}) {
            assertEquals(key, rules.bucket(rules.classify(HTTPMethod.POST, path)), path);
        }
        assertEquals(key, rules.bucket(rules.classify(HTTPMethod.GET, "/v1/orders/open")));
    }

    @Test
    void noOverridesKeepsTheDefaults() {
        final VenueRateLimits defaults = KalshiVenue.rateLimits();

        assertSame(defaults, defaults.withOverrides(Map.of()));
    }

    @Test
    void overridesSetBucketRatesAndTheShare() {
        final VenueRateLimits limits = KalshiVenue.rateLimits()
                .withOverrides(Map.of("write.per.second", "200", "read.per.second", " 400 ", "share", "0.5"));

        assertEquals(200, limits.tokensPerSecond("write"));
        assertEquals(400, limits.tokensPerSecond("read"));
        assertEquals(0.5, limits.share());
    }

    @Test
    void overridesSetBucketBurstsForALowerTier() {
        final VenueRateLimits basic = KalshiVenue.rateLimits()
                .withOverrides(Map.of(
                        "write.per.second",
                        "100",
                        "write.burst",
                        "100",
                        "read.per.second",
                        "200",
                        "read.burst",
                        "600"));

        assertEquals(100, limits(basic, "write").burst());
        assertEquals(600, limits(basic, "read").burst());
        assertEquals(100, basic.tokensPerSecond("write"));
        assertThrows(
                IllegalArgumentException.class,
                () -> KalshiVenue.rateLimits().withOverrides(Map.of("write.burst", "15")),
                "too small for an order and the cancels' reserve");
    }

    private static VenueRateLimits.Bucket limits(final VenueRateLimits limits, final String name) {
        return limits.buckets().stream()
                .filter(bucket -> bucket.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void unknownSettingIsRefusedRatherThanIgnored() {
        assertThrows(IllegalArgumentException.class, () -> KalshiVenue.rateLimits()
                .withOverrides(Map.of("write.per.sec", "200")));
        assertThrows(IllegalArgumentException.class, () -> PolymarketUsVenue.rateLimits()
                .withOverrides(Map.of("write.per.second", "200")));
        assertThrows(NumberFormatException.class, () -> PolymarketUsVenue.rateLimits()
                .withOverrides(Map.of("share", "half")));
        assertThrows(IllegalArgumentException.class, () -> PolymarketUsVenue.rateLimits()
                .withOverrides(Map.of("share", "0")));
    }
}
