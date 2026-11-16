package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolymarketIntlVenueTest {

    @Test
    void ordersLastOnlyAsLongAsTheHeartbeatAllows() {
        assertEquals(Duration.ofSeconds(10), PolymarketIntlVenue.ordersOutliveGatewayFor());
    }
}
