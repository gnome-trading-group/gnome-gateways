package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolymarketUsTimingTest {

    @Test
    void theLaneCapturesTheServerLatencyHeader() {
        assertTrue(List.of(PolymarketUsVenue.rateLimits().capturedHeaders())
                .contains(PolymarketUsVenue.SERVER_LATENCY_HEADER));
    }
}
