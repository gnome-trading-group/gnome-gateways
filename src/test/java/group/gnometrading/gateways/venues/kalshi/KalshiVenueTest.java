package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class KalshiVenueTest {

    @Test
    void ordersOutliveAStoppedGateway() {
        assertTrue(KalshiVenue.ordersOutliveGatewayFor().compareTo(Duration.ofDays(365)) > 0);
    }
}
