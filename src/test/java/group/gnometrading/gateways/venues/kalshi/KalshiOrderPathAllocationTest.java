package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.orders.OrderPathTestKit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Kalshi's order path runs for every order on the account, so once warm none of it may allocate. */
@Tag("allocation")
class KalshiOrderPathAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;

    private final KalshiOrderPath path = new KalshiOrderPath();
    private final KalshiOrderCodec codec = new KalshiOrderCodec("maker");
    private final ByteBuffer answer =
            ByteBuffer.wrap("{\"order_id\":\"o-1\",\"reduced_by\":\"1.00\",\"ts_ms\":1794671966452}"
                    .getBytes(StandardCharsets.US_ASCII));
    private long tsTotal;

    @Test
    void readingAnAnswersVenueTimeDoesNotAllocate() {
        OrderPathTestKit.assertNoAllocation("Kalshi answer ts_ms", WARM_UP, MEASURED, this::readTs);
        assertEquals((WARM_UP + MEASURED) * 1794671966452L, this.tsTotal);
    }

    private void readTs() {
        this.tsTotal += this.codec.tsMillis(this.answer);
    }

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
