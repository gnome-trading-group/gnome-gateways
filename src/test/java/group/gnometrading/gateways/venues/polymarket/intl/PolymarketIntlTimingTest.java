package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.strings.ViewString;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Polymarket Intl's {@code server-timing} header, as the probe of 2026-11-14 saw it on order writes. */
class PolymarketIntlTimingTest {

    @Test
    void theEdgeAndWorkerDurationsAreSummed() {
        assertEquals(
                29_000,
                PolymarketIntlLaneVenue.serverTimingMicros(
                        new ViewString("cfEdge;dur=2,cfOrigin;dur=0,cfWorker;dur=27")));
    }

    @Test
    void decimalMillisecondsKeepTheirMicroseconds() {
        assertEquals(
                3_250, PolymarketIntlLaneVenue.serverTimingMicros(new ViewString("cfEdge;dur=1.5,cfWorker;dur=1.75")));
    }

    @Test
    void aHeaderWithNoDurationHasNone() {
        assertEquals(-1, PolymarketIntlLaneVenue.serverTimingMicros(new ViewString("cfEdge;desc=\"x\"")));
        assertEquals(-1, PolymarketIntlLaneVenue.serverTimingMicros(new ViewString("cfEdge;dur=")));
    }

    @Test
    void theLaneCapturesTheHeader() {
        assertTrue(List.of(PolymarketIntlVenue.rateLimits().capturedHeaders())
                .contains(PolymarketIntlVenue.SERVER_TIMING_HEADER));
    }
}
