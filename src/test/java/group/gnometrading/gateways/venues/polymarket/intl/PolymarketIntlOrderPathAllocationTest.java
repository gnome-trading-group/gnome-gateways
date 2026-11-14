package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.strings.ViewString;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Polymarket International's order path runs for every order on the account, so once warm none of it may allocate. */
@Tag("allocation")
class PolymarketIntlOrderPathAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;

    private final PolymarketIntlOrderPath path = new PolymarketIntlOrderPath();
    private final ViewString serverTiming = new ViewString("cfEdge;dur=2,cfOrigin;dur=0,cfWorker;dur=27");
    private long timingTotal;

    @Test
    void readingAnAnswersServerTimingDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket Intl server-timing", WARM_UP, MEASURED, this::readTiming);
        assertEquals((WARM_UP + MEASURED) * 29_000L, this.timingTotal);
    }

    private void readTiming() {
        this.timingTotal += PolymarketIntlLaneVenue.serverTimingMicros(this.serverTiming);
    }

    @Test
    void signingAndBuildingAnOrderDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket Intl order build", WARM_UP, MEASURED, this.path::build);
    }

    @Test
    void readingAnswersDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket Intl answers", WARM_UP, MEASURED, this.path::answers);
    }

    @Test
    void signingRequestsDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket Intl L2 headers", WARM_UP, MEASURED, this.path::sign);
    }

    @Test
    void applyingTheOrdersEventsDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket Intl user events", WARM_UP, MEASURED, this.path::events);
        assertEquals(WARM_UP + MEASURED, this.path.sink().acks, "each run acknowledged its order");
        assertEquals(WARM_UP + MEASURED, this.path.sink().fills);
        assertEquals(WARM_UP + MEASURED, this.path.sink().releases);
        assertEquals(0, this.path.sink().held());
    }
}
