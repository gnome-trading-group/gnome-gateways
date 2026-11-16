package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolymarketUsVenueTest {

    @Test
    void ordersOutliveAStoppedGateway() {
        assertTrue(PolymarketUsVenue.ordersOutliveGatewayFor().compareTo(Duration.ofDays(365)) > 0);
    }
}
