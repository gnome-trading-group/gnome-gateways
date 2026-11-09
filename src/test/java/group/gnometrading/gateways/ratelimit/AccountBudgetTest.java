package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccountBudgetTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final String ORDERS = "/trade-api/v2/portfolio/events/orders";

    // Kalshi: write 300 tokens/s, burst 900, a fifth kept for cancels; an order costs 10, a cancel 2. Reads apart.
    private static final long INTERVAL = 3_333_333L;
    private final AccountBudget budget = new AccountBudget(KalshiVenue.rateLimits(), 0);
    private final int order = budget.classify(HTTPMethod.POST, ORDERS);
    private final int cancel = budget.classify(HTTPMethod.DELETE, ORDERS + "/abc");

    @Test
    void requestsAreClassifiedByTheVenuesRules() {
        assertEquals(RequestClass.NEW, budget.type(order));
        assertEquals(RequestClass.AMEND, budget.type(budget.classify(HTTPMethod.POST, ORDERS + "/abc/amend")));
        assertEquals(RequestClass.CANCEL, budget.type(cancel));
        assertEquals(
                RequestClass.CATCH_UP, budget.type(budget.classify(HTTPMethod.GET, "/trade-api/v2/portfolio/orders")));
        assertEquals(RequestClass.BACKGROUND, budget.type(budget.classify(HTTPMethod.GET, "/trade-api/v2/markets/X")));
    }

    @Test
    void itStartsEmptyAndFillsAtTheVenuesRate() {
        assertFalse(budget.tryAcquire(order, 0), "the last process may just have drained the venue's bucket");
        assertEquals(
                190 * INTERVAL, budget.nanosUntilAvailable(order, 0), "its 10 tokens and the 180 kept for cancels");
        assertTrue(budget.tryAcquire(order, 190 * INTERVAL));
    }

    @Test
    void ordersLeaveTheCancelReserveAndCancelsMayUseIt() {
        final long now = 3 * SECOND;
        int orders = 0;
        while (budget.tryAcquire(order, now)) {
            orders++;
        }
        assertEquals(72, orders, "720 of the 900 tokens; 180 are kept for cancels");
        int cancels = 0;
        while (budget.tryAcquire(cancel, now)) {
            cancels++;
        }
        assertEquals(90, cancels, "the reserve's 180 tokens at 2 a cancel");
        assertFalse(budget.tryAcquire(order, now));
    }

    @Test
    void catchUpMayUseHalfTheCancelReserveThatOrdersLeave() {
        // Writes and reads are separate buckets on Kalshi, so this uses a single bucket for both.
        final AccountBudget shared = new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", 100, 100, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(HTTPMethod.GET, "/", null, "all", 1, RequestClass.CATCH_UP),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                0);
        final int newOrder = shared.classify(HTTPMethod.POST, "/o");
        final int lookup = shared.classify(HTTPMethod.GET, "/o");
        final int sharedCancel = shared.classify(HTTPMethod.DELETE, "/o");
        final long now = 2 * SECOND;

        assertEquals(80, drain(shared, newOrder, now), "orders leave the 20 kept for cancels");
        assertEquals(10, drain(shared, lookup, now), "catch-up leaves half of them");
        assertEquals(10, drain(shared, sharedCancel, now), "cancels take the rest");
    }

    private static int drain(final AccountBudget budget, final int rule, final long now) {
        int taken = 0;
        while (budget.tryAcquire(rule, now)) {
            taken++;
        }
        return taken;
    }

    @Test
    void eachRefusalIsDescribedByWhatWasSentBeforeIt() {
        final AccountBudget plain = new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("write", 100, 100, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(
                                        HTTPMethod.DELETE, ORDERS + "/", null, "write", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "write", 1, RequestClass.NEW)),
                        List.of()),
                0);
        final int plainOrder = plain.classify(HTTPMethod.POST, ORDERS);
        final int plainCancel = plain.classify(HTTPMethod.DELETE, ORDERS + "/abc");
        assertTrue(plain.tryAcquire(plainOrder, SECOND));
        assertTrue(plain.tryAcquire(plainCancel, 2_800_000_000L));
        for (int i = 0; i < 3; i++) {
            assertTrue(plain.tryAcquire(plainOrder, 2_950_000_000L));
        }

        plain.onResponse(plainCancel, new HTTPResponse() {}.update(false, 429, null), 3 * SECOND);

        assertEquals(
                "CANCEL refused; the second before: CANCEL=1 NEW=3 (4), the tenth before: 3\n",
                plain.describeRefusals(),
                "the order sent 2 s before is outside the second");
    }

    @Test
    void aRefusalTheLogNoLongerReachesBackToIsSaidSo() {
        final AccountBudget busy = new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("write", 1_000_000, 1_000_000, 0)),
                        List.of(new VenueRateLimits.Rule(null, "", null, "write", 1, RequestClass.NEW)),
                        List.of()),
                0);
        final int any = busy.classify(HTTPMethod.POST, ORDERS);
        // More than the log keeps, all at once: the oldest kept is from then, after the second before the refusal
        // began.
        for (int i = 0; i <= 1 << 14; i++) {
            assertTrue(busy.tryAcquire(any, 2 * SECOND));
        }

        busy.onResponse(any, new HTTPResponse() {}.update(false, 429, null), 2 * SECOND + SECOND / 2);

        assertEquals("NEW refused; what was sent the second before is no longer kept\n", busy.describeRefusals());
    }

    @Test
    void aVenue429HoldsAllButCancelsForItsRetryAfter() {
        // Limits that capture no headers, as the hand-made response here has none: the hold is then a second.
        final AccountBudget plain = new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("write", 100, 100, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(
                                        HTTPMethod.DELETE, ORDERS + "/", null, "write", 2, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "write", 10, RequestClass.NEW)),
                        List.of()),
                0);
        final int plainOrder = plain.classify(HTTPMethod.POST, ORDERS);
        final int plainCancel = plain.classify(HTTPMethod.DELETE, ORDERS + "/abc");
        plain.onResponse(plainOrder, new HTTPResponse() {}.update(false, 429, null), SECOND);

        assertFalse(plain.tryAcquire(plainOrder, SECOND), "held, though its bucket is full");
        assertTrue(plain.tryAcquire(plainCancel, SECOND), "cancels are never held");
        assertEquals(SECOND, plain.nanosUntilAvailable(plainOrder, SECOND));
        assertTrue(plain.tryAcquire(plainOrder, 2 * SECOND));
    }

    @Test
    void otherAnswersHoldNothing() {
        budget.onResponse(order, new HTTPResponse() {}.update(false, 400, null), SECOND);
        assertTrue(budget.tryAcquire(order, SECOND));
    }
}
