package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VenueRateLimitsTest {

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
