package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.orders.OrderPathTestKit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Polymarket US's order path runs for every order on the account, so once warm none of it may allocate. */
@Tag("allocation")
class PolymarketUsOrderPathAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;

    private final PolymarketUsOrderPath path = new PolymarketUsOrderPath();

    @Test
    void buildingRequestsAndReadingAnswersDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket US order build", WARM_UP, MEASURED, this.path::build);
    }

    @Test
    void readingABatchsAnswerDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket US batch answer", WARM_UP, MEASURED, this.path::batch);
        assertEquals(3, this.path.batchCtxs()[19].exchangeOrderIdLength, "the last entry got its id, V19");
    }

    @Test
    void signingDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket US request signing", WARM_UP, MEASURED, this.path::sign);
    }

    @Test
    void applyingTheOrdersEventsDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Polymarket US executions", WARM_UP, MEASURED, this.path::events);
        assertEquals(WARM_UP + MEASURED, this.path.sink().acks, "each run acknowledged its order");
        assertEquals(WARM_UP + MEASURED, this.path.sink().fills);
        assertEquals(WARM_UP + MEASURED, this.path.sink().releases);
        assertEquals(0, this.path.sink().held());
    }
}
