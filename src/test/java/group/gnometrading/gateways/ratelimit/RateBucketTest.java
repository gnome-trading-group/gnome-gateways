package group.gnometrading.gateways.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateBucketTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long START = 1_000 * SECOND;

    @Test
    void fullBucketGivesItsBurstThenNothing() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);

        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryAcquire(START, 1, 0), "token " + i);
        }
        assertFalse(bucket.tryAcquire(START, 1, 0));
        assertEquals(0, bucket.availableTokens(START));
    }

    @Test
    void refillsAtItsRate() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        drain(bucket, START);

        assertFalse(bucket.tryAcquire(START + SECOND / 10 - 1, 1, 0));
        assertTrue(bucket.tryAcquire(START + SECOND / 10, 1, 0));
        assertEquals(5, bucket.availableTokens(START + SECOND / 10 + SECOND / 2));
    }

    @Test
    void neverHoldsMoreThanItsBurst() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);

        assertEquals(10, bucket.availableTokens(START + 100 * SECOND));
        drain(bucket, START + 100 * SECOND);
        assertFalse(bucket.tryAcquire(START + 100 * SECOND, 1, 0));
    }

    @Test
    void costTakesThatManyTokens() {
        final RateBucket bucket = new RateBucket("b", 100, 100, 0, 0, START);

        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryAcquire(START, 10, 0));
        }
        assertFalse(bucket.tryAcquire(START, 10, 0));
        assertFalse(bucket.tryAcquire(START, 1, 0));
    }

    @Test
    void headroomIsLeftForOthers() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 2, 3, START);

        assertEquals(0, bucket.headroomFor(RequestClass.CANCEL));
        assertEquals(2, bucket.headroomFor(RequestClass.ORDER));
        assertEquals(5, bucket.headroomFor(RequestClass.BACKGROUND));

        int background = 0;
        while (bucket.tryAcquire(START, 1, bucket.headroomFor(RequestClass.BACKGROUND))) {
            background++;
        }
        int orders = 0;
        while (bucket.tryAcquire(START, 1, bucket.headroomFor(RequestClass.ORDER))) {
            orders++;
        }
        int cancels = 0;
        while (bucket.tryAcquire(START, 1, bucket.headroomFor(RequestClass.CANCEL))) {
            cancels++;
        }
        assertEquals(5, background);
        assertEquals(3, orders);
        assertEquals(2, cancels);
    }

    @Test
    void nanosUntilAvailableMatchesTryAcquire() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        drain(bucket, START);

        final long wait = bucket.nanosUntilAvailable(START, 3, 0);
        assertEquals(3 * SECOND / 10, wait);
        assertFalse(bucket.tryAcquire(START + wait - 1, 3, 0));
        assertTrue(bucket.tryAcquire(START + wait, 3, 0));
        assertEquals(0, new RateBucket("c", 10, 10, 0, 0, START).nanosUntilAvailable(START, 3, 0));
    }

    @Test
    void forcedTokensAreDebtThatLaterRequestsWaitOut() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        drain(bucket, START);

        bucket.forceAcquire(START, 5);

        assertFalse(bucket.tryAcquire(START + SECOND / 2, 1, 0));
        assertTrue(bucket.tryAcquire(START + SECOND / 2 + SECOND / 10, 1, 0));
    }

    @Test
    void slowerRateRefillsSlower() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        drain(bucket, START);

        bucket.setRate(5, START);

        assertFalse(bucket.tryAcquire(START + SECOND / 10, 1, 0));
        assertTrue(bucket.tryAcquire(START + SECOND / 5, 1, 0));
    }

    @Test
    void slowerRateDoesNotMakeRoomForMoreTokens() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        drain(bucket, START);

        bucket.setRate(5, START);

        assertFalse(bucket.tryAcquire(START, 1, 0));
        assertEquals(0, bucket.availableTokens(START));
    }

    @Test
    void halfSpentBucketStaysHalfSpentAcrossARateChange() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(START, 1, 0));
        }

        bucket.setRate(2, START);

        assertEquals(5, bucket.availableTokens(START));
        assertEquals(6, bucket.availableTokens(START + SECOND / 2));
    }

    @Test
    void blockingOnlyEverExtends() {
        final RateBucket bucket = new RateBucket("b", 10, 10, 0, 0, START);

        bucket.blockUntil(START + SECOND);
        bucket.blockUntil(START + SECOND / 2);

        assertEquals(SECOND, bucket.nanosBlocked(START));
        assertEquals(0, bucket.nanosBlocked(START + 2 * SECOND));
    }

    @Test
    void rejectsBucketsThatLeaveNothingForBackgroundRequests() {
        assertThrows(IllegalArgumentException.class, () -> new RateBucket("b", 10, 10, 5, 5, START));
        assertThrows(IllegalArgumentException.class, () -> new RateBucket("b", 0, 10, 0, 0, START));
        assertThrows(IllegalArgumentException.class, () -> new RateBucket("b", 10, 0, 0, 0, START));
    }

    @Test
    void threadsSharingABucketNeverOverspend() throws Exception {
        // A rate so slow nothing refills during the test, so exactly the burst can ever be taken.
        final RateBucket bucket = new RateBucket("b", 1e-6, 1_000, 0, 0, START);
        final int threads = 8;
        final AtomicLong taken = new AtomicLong();
        final CountDownLatch go = new CountDownLatch(1);
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    go.await();
                } catch (final InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 100_000; i++) {
                    if (bucket.tryAcquire(START, 1, 0)) {
                        taken.incrementAndGet();
                    }
                }
            });
            workers[t].start();
        }
        go.countDown();
        for (final Thread worker : workers) {
            worker.join();
        }

        assertEquals(1_000, taken.get());
    }

    private static void drain(final RateBucket bucket, final long now) {
        while (bucket.tryAcquire(now, 1, 0)) {
            // Empties the bucket.
        }
    }
}
