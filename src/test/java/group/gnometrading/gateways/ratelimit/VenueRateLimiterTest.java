package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.http.RequestGate;
import group.gnometrading.strings.ViewString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NanoClock;
import org.junit.jupiter.api.Test;

class VenueRateLimiterTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long EPOCH_MILLIS = 1_791_643_200_000L;

    private final FakeClock clock = new FakeClock();
    private final RecordingLogger logger = new RecordingLogger();

    // ========== Classifying ==========

    @Test
    void kalshiRequestsDrawOnTheirBucketsAtTheirCost() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.kalshi());
        final String orders = "/trade-api/v2/portfolio/events/orders";

        assertRule(limiter, HTTPMethod.POST, orders, RequestClass.ORDER, 10);
        assertRule(limiter, HTTPMethod.POST, orders + "/abc-123/amend", RequestClass.ORDER, 10);
        assertRule(limiter, HTTPMethod.DELETE, orders + "/abc-123?subaccount=0", RequestClass.CANCEL, 2);
        assertRule(limiter, HTTPMethod.GET, "/trade-api/v2/portfolio/orders?ticker=X", RequestClass.BACKGROUND, 10);
        assertRule(limiter, HTTPMethod.PUT, "/trade-api/v2/anything", RequestClass.ORDER, 10);
    }

    @Test
    void polymarketUsTellsCancelsFromModifiesByTheirEnding() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        assertRule(limiter, HTTPMethod.POST, "/v1/orders", RequestClass.ORDER, 1);
        assertRule(limiter, HTTPMethod.POST, "/v1/order/42/cancel", RequestClass.CANCEL, 1);
        assertRule(limiter, HTTPMethod.POST, "/v1/order/42/modify", RequestClass.ORDER, 1);
        assertRule(limiter, HTTPMethod.GET, "/v1/orders/open?slug=x", RequestClass.BACKGROUND, 1);
    }

    @Test
    void suffixIsMatchedBeforeTheQuery() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        assertRule(limiter, HTTPMethod.POST, "/v1/order/42/cancel?x=1", RequestClass.CANCEL, 1);
        assertRule(limiter, HTTPMethod.POST, "/v1/order/cancel/42", RequestClass.ORDER, 1);
    }

    @Test
    void polymarketIntlReadsAreNotLimited() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketIntl());

        for (int i = 0; i < 10_000; i++) {
            assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.GET, "/data/orders"));
        }
        assertEquals(0, clock.now - FakeClock.START);
    }

    // ========== Orders ==========

    @Test
    void ordersAreRefusedOnceOnlyTheCancelReserveIsLeft() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        assertEquals(16, admitAll(limiter, HTTPMethod.POST, "/v1/orders"));
        assertEquals(0, clock.now - FakeClock.START, "an order never waits");
        assertEquals(4, admitWithoutWaiting(limiter, clock, HTTPMethod.POST, "/v1/order/1/cancel", 100));
    }

    @Test
    void ordersGoAgainOnceTheBucketRefills() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        admitAll(limiter, HTTPMethod.POST, "/v1/orders");

        // 80% of 20/s is a token every 62.5 ms.
        clock.now += 63 * MILLIS;
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"));
        assertEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"));
    }

    @Test
    void throttlingIsLoggedWhenItStartsAndEnds() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        admitAll(limiter, HTTPMethod.POST, "/v1/orders");
        limiter.beforeRequest(HTTPMethod.POST, "/v1/orders");
        limiter.beforeRequest(HTTPMethod.POST, "/v1/orders");

        clock.now += SECOND;
        limiter.beforeRequest(HTTPMethod.POST, "/v1/orders");

        assertEquals(List.of(LogMessage.RATE_LIMIT_THROTTLING, LogMessage.RATE_LIMIT_RECOVERED), logger.messages);
        assertTrue(logger.lines.get(0).contains("refusing requests locally"));
        assertTrue(logger.lines.get(1).contains("after 3 requests refused locally"), logger.lines.get(1));
    }

    // ========== Cancels ==========

    @Test
    void cancelWaitsBrieflyForAToken() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        assertEquals(20, admitWithoutWaiting(limiter, clock, HTTPMethod.POST, "/v1/order/1/cancel", 20));

        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"));
        assertTrue(clock.now - FakeClock.START > 0 && clock.now - FakeClock.START < 100 * MILLIS);
    }

    @Test
    void cancelGoesAnywayRatherThanWaitLong() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs().withRate("key", 1));
        assertEquals(20, admitWithoutWaiting(limiter, clock, HTTPMethod.POST, "/v1/order/1/cancel", 20));

        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"));
        assertEquals(0, clock.now - FakeClock.START, "it doesn't wait when the wait would be too long");
    }

    @Test
    void cancelIgnoresAPenalty() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"), FakeResponse.status(429));

        assertEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"));
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"));
    }

    // ========== Background ==========

    @Test
    void backgroundRequestsLeaveRoomForOrdersAndWaitTheirTurn() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        // A burst of 20 less the cancel reserve (4) and the share kept for orders (6).
        for (int i = 0; i < 10; i++) {
            limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open");
        }
        assertEquals(0, clock.now - FakeClock.START);
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open"));
        assertTrue(clock.now - FakeClock.START > 0, "the 11th waited");
        assertEquals(6, admitAll(limiter, HTTPMethod.POST, "/v1/orders"));
    }

    @Test
    void backgroundRequestIsRefusedRatherThanWaitTooLong() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        limiter.afterResponse(
                limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open"),
                FakeResponse.status(429).header(VenueRateLimits.RETRY_AFTER, "9"));

        assertEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open"));
        assertEquals(0, clock.now - FakeClock.START);
    }

    @Test
    void backgroundRequestWaitsOutAShortPenalty() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open"), FakeResponse.status(429));

        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.GET, "/v1/orders/open"));
        assertEquals(SECOND, clock.now - FakeClock.START);
    }

    // ========== The venue's 429 ==========

    @Test
    void venue429HoldsOrdersForRetryAfter() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.kalshi());
        final String orders = "/trade-api/v2/portfolio/events/orders";
        limiter.afterResponse(
                limiter.beforeRequest(HTTPMethod.POST, orders),
                FakeResponse.status(429).header(VenueRateLimits.RETRY_AFTER, "2"));

        clock.now += 2 * SECOND - 1;
        assertEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, orders));
        clock.now += 1;
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, orders));
    }

    @Test
    void venue429IsLoggedAsTheVenuesRateLimit() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"), FakeResponse.status(429));

        assertEquals(List.of(LogMessage.VENUE_RATE_LIMITED), logger.messages);
    }

    @Test
    void venue429HalvesTheBudgetOncePerBurst() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        assertEquals(0.8, limiter.budget("key"), 1e-9);

        for (int i = 0; i < 5; i++) {
            limiter.afterResponse(
                    limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));
        }
        assertEquals(0.4, limiter.budget("key"), 1e-9);

        clock.now += SECOND;
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));
        assertEquals(0.2, limiter.budget("key"), 1e-9);
    }

    @Test
    void budgetNeverFallsBelowItsFloor() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        for (int i = 0; i < 10; i++) {
            clock.now += SECOND;
            limiter.afterResponse(
                    limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));
        }

        assertEquals(VenueRateLimiter.MIN_BUDGET, limiter.budget("key"), 1e-9);
    }

    @Test
    void budgetRecoversAStepForEachSecondItHeldRequestsBackUpToTheShare() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs().withShare(0.9));
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));
        assertEquals(0.4, limiter.budget("key"), 1e-9);

        // Each second the orders outrun the budget, and the venue doesn't object.
        for (int second = 0; second < 4; second++) {
            clock.now += SECOND;
            admitAll(limiter, HTTPMethod.POST, "/v1/orders");
        }
        assertEquals(0.4 + 3 * VenueRateLimiter.BUDGET_STEP, limiter.budget("key"), 1e-9);

        for (int second = 0; second < 40; second++) {
            clock.now += SECOND;
            admitAll(limiter, HTTPMethod.POST, "/v1/orders");
        }
        assertEquals(0.9, limiter.budget("key"), 1e-9);
    }

    @Test
    void secondsThatHeldNothingBackGiveNothingBack() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        clock.now += 60 * SECOND;
        limiter.beforeRequest(HTTPMethod.POST, "/v1/orders");
        clock.now += 60 * SECOND;
        limiter.beforeRequest(HTTPMethod.POST, "/v1/orders");

        assertEquals(VenueRateLimiter.INITIAL_BUDGET, limiter.budget("key"), 1e-9);
    }

    @Test
    void secondWithA429GivesNothingBack() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());
        admitAll(limiter, HTTPMethod.POST, "/v1/orders");
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));

        clock.now += SECOND;
        limiter.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel");

        assertEquals(0.4, limiter.budget("key"), 1e-9);
    }

    @Test
    void eachBucketAdaptsOnItsOwn() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.kalshi());
        final String orders = "/trade-api/v2/portfolio/events/orders";

        // Reads wait on the read bucket, second after second, without a 429...
        for (int second = 0; second < 5; second++) {
            clock.now += SECOND;
            for (int i = 0; i < 20; i++) {
                limiter.beforeRequest(HTTPMethod.GET, "/trade-api/v2/portfolio/orders");
            }
        }
        // ...which says nothing about the write bucket.
        assertEquals(VenueRateLimiter.INITIAL_BUDGET, limiter.budget("write"), 1e-9);
        assertTrue(limiter.budget("read") > VenueRateLimiter.INITIAL_BUDGET);

        // A 429 to an order lowers only the write bucket.
        final double read = limiter.budget("read");
        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.POST, orders), FakeResponse.status(429));
        assertEquals(VenueRateLimiter.INITIAL_BUDGET / 2, limiter.budget("write"), 1e-9);
        assertEquals(read, limiter.budget("read"), 1e-9);
    }

    @Test
    void venue429ToAnUnlimitedRequestHoldsNothing() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketIntl());

        limiter.afterResponse(limiter.beforeRequest(HTTPMethod.GET, "/data/orders"), FakeResponse.status(429));

        assertEquals(List.of(LogMessage.VENUE_RATE_LIMITED), logger.messages);
        assertEquals(VenueRateLimiter.INITIAL_BUDGET, limiter.budget("order"), 1e-9);
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/order"));
    }

    @Test
    void shareBelowTheStartingBudgetCapsItFromTheStart() {
        assertEquals(0.5, limiter(VenueRateLimits.polymarketUs().withShare(0.5)).budget("key"), 1e-9);
    }

    @Test
    void lowerBudgetRefillsSlower() {
        final FakeClock healthyClock = new FakeClock();
        final VenueRateLimiter healthy = limiter(VenueRateLimits.polymarketUs(), healthyClock);
        final VenueRateLimiter limited = limiter(VenueRateLimits.polymarketUs(), clock);
        limited.afterResponse(limited.beforeRequest(HTTPMethod.POST, "/v1/order/1/cancel"), FakeResponse.status(429));
        assertEquals(0.4, limited.budget("key"), 1e-9);
        admitWithoutWaiting(healthy, healthyClock, HTTPMethod.POST, "/v1/order/1/cancel", 100);
        admitWithoutWaiting(limited, clock, HTTPMethod.POST, "/v1/order/1/cancel", 100);

        healthyClock.now += SECOND / 2;
        clock.now += SECOND / 2;

        // Half a second at 80% and at 40% of 20/s.
        assertEquals(8, admitWithoutWaiting(healthy, healthyClock, HTTPMethod.POST, "/v1/order/1/cancel", 100));
        assertEquals(4, admitWithoutWaiting(limited, clock, HTTPMethod.POST, "/v1/order/1/cancel", 100));
    }

    @Test
    void responseToALocallyRefusedRequestChangesNothing() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketUs());

        limiter.afterResponse(0, FakeResponse.status(429).refusedLocally());

        assertEquals(0.8, limiter.budget("key"), 1e-9);
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/v1/orders"));
    }

    // ========== Polymarket International's headers ==========

    @Test
    void emptiedVenueBucketHoldsRequestsUntilItsReset() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketIntl());
        limiter.afterResponse(
                limiter.beforeRequest(HTTPMethod.POST, "/order"),
                FakeResponse.status(200)
                        .header(VenueRateLimits.POLY_REMAINING, "0")
                        .header(VenueRateLimits.POLY_RESET, String.valueOf(EPOCH_MILLIS / 1000 + 1)));

        assertEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/order"));
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.DELETE, "/order"));
        clock.now += SECOND;
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/order"));
    }

    @Test
    void resetFarAwayIsCappedInCaseTheClocksDisagree() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketIntl());
        limiter.afterResponse(
                limiter.beforeRequest(HTTPMethod.POST, "/order"),
                FakeResponse.status(200)
                        .header(VenueRateLimits.POLY_REMAINING, "0")
                        .header(VenueRateLimits.POLY_RESET, String.valueOf(EPOCH_MILLIS / 1000 + 3600)));

        clock.now += VenueRateLimiter.MAX_HEADER_BLOCK_NANOS;
        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/order"));
    }

    @Test
    void remainingAboveZeroHoldsNothing() {
        final VenueRateLimiter limiter = limiter(VenueRateLimits.polymarketIntl());
        limiter.afterResponse(
                limiter.beforeRequest(HTTPMethod.POST, "/order"),
                FakeResponse.status(200)
                        .header(VenueRateLimits.POLY_REMAINING, "59")
                        .header(VenueRateLimits.POLY_RESET, String.valueOf(EPOCH_MILLIS / 1000 + 1)));

        assertNotEquals(RequestGate.REFUSE, limiter.beforeRequest(HTTPMethod.POST, "/order"));
    }

    // ========== Limits and registry ==========

    @Test
    void limitsMustEndWithACatchAllAndFitTheirBuckets() {
        assertThrows(
                IllegalArgumentException.class, () -> VenueRateLimits.kalshi().withShare(0));
        assertThrows(
                IllegalArgumentException.class, () -> VenueRateLimits.kalshi().withShare(1.5));
        assertThrows(
                IllegalArgumentException.class, () -> VenueRateLimits.kalshi().withRate("nope", 10));
    }

    @Test
    void oneLimiterPerAccount() {
        final VenueRateLimiters limiters = new VenueRateLimiters(logger, clock, () -> EPOCH_MILLIS);

        final VenueRateLimiter first = limiters.forAccount(VenueRateLimits.polymarketUs(), "key-1");

        assertSame(first, limiters.forAccount(VenueRateLimits.polymarketUs(), "key-1"));
        assertNotSame(first, limiters.forAccount(VenueRateLimits.polymarketUs(), "key-2"));
        assertNotSame(first, limiters.forAccount(VenueRateLimits.kalshi(), "key-1"));
    }

    @Test
    void capturedHeadersAreTheOnesTheLimiterReads() {
        assertEquals(
                List.of("Retry-After", "Poly-RateLimit-Remaining", "Poly-RateLimit-Reset"),
                List.of(VenueRateLimits.polymarketIntl().capturedHeaders()));
        assertEquals(List.of("Retry-After"), List.of(VenueRateLimits.kalshi().capturedHeaders()));
    }

    // ========== Helpers ==========

    private VenueRateLimiter limiter(final VenueRateLimits limits) {
        return limiter(limits, clock);
    }

    private VenueRateLimiter limiter(final VenueRateLimits limits, final FakeClock fakeClock) {
        return new VenueRateLimiter(limits, logger, fakeClock, () -> EPOCH_MILLIS, nanos -> fakeClock.now += nanos);
    }

    /**
     * How many requests go straight through, up to {@code max}. Stops at the first that had to wait, which is let
     * through once it has.
     */
    private static int admitWithoutWaiting(
            final VenueRateLimiter limiter,
            final FakeClock fakeClock,
            final HTTPMethod method,
            final String path,
            final int max) {
        int admitted = 0;
        while (admitted < max) {
            final long before = fakeClock.now;
            if (limiter.beforeRequest(method, path) == RequestGate.REFUSE || fakeClock.now != before) {
                return admitted;
            }
            admitted++;
        }
        return admitted;
    }

    private static int admitAll(final VenueRateLimiter limiter, final HTTPMethod method, final String path) {
        int admitted = 0;
        while (limiter.beforeRequest(method, path) != RequestGate.REFUSE) {
            admitted++;
            if (admitted > 10_000) {
                throw new AssertionError("never refused");
            }
        }
        return admitted;
    }

    private static void assertRule(
            final VenueRateLimiter limiter,
            final HTTPMethod method,
            final String path,
            final RequestClass type,
            final long cost) {
        final int rule = limiter.classify(method, path);
        assertEquals(rule, limiter.classify(method, new ViewString(path)), path);
        final VenueRateLimits.Rule spec = limiterRules(limiter).get(rule);
        assertEquals(type, spec.type(), path);
        assertEquals(cost, spec.cost(), path);
    }

    private static List<VenueRateLimits.Rule> limiterRules(final VenueRateLimiter limiter) {
        try {
            final var field = VenueRateLimiter.class.getDeclaredField("limits");
            field.setAccessible(true);
            return ((VenueRateLimits) field.get(limiter)).rules();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakeClock implements NanoClock {
        static final long START = 5_000 * SECOND;
        long now = START;

        @Override
        public long nanoTime() {
            return this.now;
        }
    }

    private static final class RecordingLogger extends NullLogger {
        final List<LogMessage> messages = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void logf(final LogMessage msg, final String format, final Object... args) {
            this.messages.add(msg);
            this.lines.add(String.format(format, args));
        }
    }

    /** A response with whatever status and headers a test needs, headers indexed as the venue's limits list them. */
    private static final class FakeResponse extends HTTPResponse {
        private final int status;
        private final Map<String, String> headers = new HashMap<>();
        private boolean refused;

        private FakeResponse(final int status) {
            this.status = status;
        }

        static FakeResponse status(final int status) {
            return new FakeResponse(status);
        }

        FakeResponse header(final String name, final String value) {
            this.headers.put(name, value);
            return this;
        }

        FakeResponse refusedLocally() {
            this.refused = true;
            return this;
        }

        @Override
        public int getStatusCode() {
            return this.status;
        }

        @Override
        public boolean isSuccess() {
            return this.status >= 200 && this.status < 300;
        }

        @Override
        public boolean isRefusedLocally() {
            return this.refused;
        }

        @Override
        public boolean hasHeader(final int index) {
            return this.headers.containsKey(name(index));
        }

        @Override
        public long headerAsLong(final int index, final long absent) {
            final String value = this.headers.get(name(index));
            return value == null ? absent : Long.parseLong(value);
        }

        private static String name(final int index) {
            return VenueRateLimits.polymarketIntl().capturedHeaders()[index];
        }
    }
}
