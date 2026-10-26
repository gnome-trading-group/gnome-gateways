package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConnectLimiterTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void connectsAreSpacedByTheRate() {
        ConnectLimiter limiter = new ConnectLimiter("test", 2, 0);

        assertEquals(0, limiter.reserve(0));
        assertEquals(500 * MS, limiter.reserve(0));
        assertEquals(1000 * MS, limiter.reserve(0));
        assertEquals(1400 * MS, limiter.reserve(100 * MS), "the wait counts from when the caller asks");
    }

    @Test
    void aQuietLimiterDoesNotSaveUpABurst() {
        ConnectLimiter limiter = new ConnectLimiter("test", 2, 0);

        assertEquals(0, limiter.reserve(10_000 * MS));
        assertEquals(500 * MS, limiter.reserve(10_000 * MS));
    }

    @Test
    void anUnlimitedLimiterNeverWaits() {
        for (int i = 0; i < 10; i++) {
            assertEquals(0, ConnectLimiter.UNLIMITED.reserve(0));
        }
    }

    @Test
    void everyGatewayOnASourceSharesOneLimiter() {
        assertSame(ConnectLimiter.forSource("KALSHI"), ConnectLimiter.forSource("KALSHI"));
    }

    @Test
    void otherSourcesConnectAtFiveASecond() {
        assertEquals(200 * MS, spacing(ConnectLimiter.forSource("SOME_OTHER_VENUE")));
    }

    private static long spacing(ConnectLimiter limiter) {
        long now = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
        limiter.reserve(now);
        return limiter.reserve(now);
    }

    @Test
    void kalshiConnectsAtTwoASecond() {
        assertEquals(500 * MS, spacing(ConnectLimiter.forSource("KALSHI")));
    }
}
