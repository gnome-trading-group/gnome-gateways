package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.http.RequestGate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NanoClock;
import org.junit.jupiter.api.Test;

class BudgetGateTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_WAIT = TimeUnit.SECONDS.toNanos(5);

    // One bucket of 100 at 100 a second, 20 kept for cancels and 30 more for orders; every request costs 1.
    private final AccountBudget budget = new AccountBudget(
            VenueRateLimits.of(
                    "Test",
                    List.of(new VenueRateLimits.Bucket("all", 100, 100, 0.2)),
                    List.of(
                            new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                            new VenueRateLimits.Rule(HTTPMethod.GET, "/", null, "all", 1, RequestClass.BACKGROUND),
                            new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                    List.of()),
            0);
    private final FakeClock clock = new FakeClock();

    @Test
    void aRequestWaitsForItsTokensThenGoes() {
        final BudgetGate gate = gate(RequestClass.CATCH_UP);

        assertNotEquals(RequestGate.REFUSE, gate.beforeRequest(HTTPMethod.GET, "/orders"));
        assertTrue(this.clock.now > 0, "the budget starts empty, so the first request waited");
    }

    @Test
    void aRequestThatWouldWaitTooLongIsRefusedWithoutTakingTokens() {
        final BudgetGate gate = new BudgetGate(this.budget, RequestClass.CATCH_UP, 1, this.clock, this.clock::sleep);

        assertEquals(RequestGate.REFUSE, gate.beforeRequest(HTTPMethod.GET, "/orders"));
        assertEquals(0, this.clock.now, "refused at once rather than after a wait that couldn't be enough");
    }

    @Test
    void catchUpGoesDeeperIntoTheBudgetThanBackgroundWorkWhateverThePath() {
        final int order = this.budget.classify(HTTPMethod.POST, "/orders");
        this.clock.now = 2 * SECOND;
        final BudgetGate background = gate(RequestClass.BACKGROUND);
        final BudgetGate catchUp = gate(RequestClass.CATCH_UP);

        assertEquals(50, admitWithoutWaiting(background), "leaves the 20 for cancels and 30 for orders");
        assertEquals(40, admitWithoutWaiting(catchUp), "the same path leaves only half the cancels' 20");
        assertFalse(this.budget.tryAcquire(order, this.clock.now), "orders now find only what cancels keep");
    }

    @Test
    void theVenuesRefusalHoldsTheLaneToo() {
        final int order = this.budget.classify(HTTPMethod.POST, "/orders");
        this.clock.now = 2 * SECOND;
        final BudgetGate gate = gate(RequestClass.CATCH_UP);

        final int tag = gate.beforeRequest(HTTPMethod.GET, "/orders");
        gate.afterResponse(tag, new HTTPResponse() {}.update(false, 429, null));

        assertFalse(this.budget.tryAcquire(order, this.clock.now), "a full bucket, but held for the venue's penalty");
    }

    @Test
    void cancelsNeverGoThroughAGate() {
        assertThrows(IllegalArgumentException.class, () -> gate(RequestClass.CANCEL));
    }

    private BudgetGate gate(final RequestClass type) {
        return new BudgetGate(this.budget, type, MAX_WAIT, this.clock, this.clock::sleep);
    }

    private int admitWithoutWaiting(final BudgetGate gate) {
        int admitted = 0;
        while (true) {
            final long before = this.clock.now;
            gate.beforeRequest(HTTPMethod.GET, "/orders");
            if (this.clock.now != before) {
                return admitted;
            }
            admitted++;
        }
    }

    private static final class FakeClock implements NanoClock {
        long now;

        @Override
        public long nanoTime() {
            return this.now;
        }

        void sleep(final long nanos) {
            this.now += nanos;
        }
    }
}
