package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.orders.OrderPathTestKit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Kalshi's order path runs for every order on the account, so once warm none of it may allocate. */
@Tag("allocation")
class KalshiOrderPathAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;

    private final KalshiOrderPath path = new KalshiOrderPath();

    @Test
    void buildingAnOrderAndReadingItsAnswerDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Kalshi order build", WARM_UP, MEASURED, this.path::build);
    }

    @Test
    void signingDoesNotAllocate() {
        // RSA-2048 is slow, so fewer runs: still well past the JIT's warm-up.
        OrderPathTestKit.assertNoAllocation("Kalshi request signing", 2_000, 1_000, this.path::sign);
    }

    @Test
    void applyingTheOrdersEventsDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Kalshi user_order events", WARM_UP, MEASURED, this.path::events);
        assertEquals(WARM_UP + MEASURED, this.path.sink().acks, "each run acknowledged its order");
        assertEquals(WARM_UP + MEASURED, this.path.sink().fills);
        assertEquals(WARM_UP + MEASURED, this.path.sink().releases);
        assertEquals(0, this.path.sink().held());
    }

    @Test
    void amendAndCancelRequestsDoNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Kalshi amend and cancel", WARM_UP, MEASURED, this.path::amendAndCancel);
    }

    @Test
    void theUnknownOutcomePolicyDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("submit policy", WARM_UP, MEASURED, this.path::unknownOutcome);
    }
}
