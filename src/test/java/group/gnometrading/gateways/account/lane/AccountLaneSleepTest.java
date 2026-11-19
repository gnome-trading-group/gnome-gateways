package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.networking.sockets.SocketWaker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A lane that sleeps when idle, over real connections to a local server: it sleeps far longer than any of this takes,
 * so only the wake (a command handed over) or a socket (an answer) can account for how soon it acts.
 */
class AccountLaneSleepTest {

    private static final long A = 1;
    private static final long LONG_SLEEP_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long PROMPT_MILLIS = 500;

    private LaneHarness<String> harness;
    private SocketWaker waker;

    @BeforeEach
    void start() throws Exception {
        this.harness = new LaneHarness<>(
                new TestLaneVenue(),
                0,
                TimeUnit.SECONDS.toNanos(10),
                A,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
        this.harness.server.script(request -> request.method().equals("POST")
                ? Reply.ok("v-" + request.body()).after(50)
                : Reply.ok(""));
        this.harness.session(A, 1, 0);
        // The pool connects before the lane is put to sleep: a fresh connection is no wake.
        this.harness.runFor(300);
        this.waker = SocketWaker.create();
        this.harness.lane.sleepWhenIdle(this.waker, LONG_SLEEP_NANOS, 0);
    }

    @AfterEach
    void stop() {
        this.harness.close();
        this.waker.close();
    }

    @Test
    void anIdlePassSleepsUpToItsBound() {
        this.harness.lane.sleepWhenIdle(this.waker, TimeUnit.MILLISECONDS.toNanos(200), 0);
        final long start = System.nanoTime();
        assertEquals(1, this.harness.lane.doWork(), "a sleep is the pass's idling, not nothing done");
        final long sleptMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(sleptMillis >= 150 && sleptMillis < 1_000, "slept " + sleptMillis + " ms");
    }

    @Test
    void aCommandHandedOverWithAWakeIsSentAtOnce() throws Exception {
        final AtomicLong handedAt = new AtomicLong();
        final Thread sessions = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handedAt.set(System.nanoTime());
            this.harness.submit(1);
            this.harness.lane.wake();
        });
        sessions.start();
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1, 5_000);
        sessions.join();
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(
                this.harness.requests("POST").get(0).receivedNanos() - handedAt.get());
        assertTrue(tookMillis < PROMPT_MILLIS, "sent " + tookMillis + " ms after it was handed over");
    }

    @Test
    void anAnswerEndsTheSleepAsItArrives() throws Exception {
        this.harness.submit(1);
        final long sent = System.nanoTime();
        this.harness.runUntil(() -> this.harness.answered("POST", 1), 5_000);
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
        assertTrue(tookMillis < 50 + PROMPT_MILLIS, "answered after " + tookMillis + " ms");
        assertEquals(1, this.harness.requests("POST").size());
    }

    @Test
    void aCommandHandedOverWithoutAWakeWaitsOutTheSleepAtMost() throws Exception {
        this.harness.lane.sleepWhenIdle(this.waker, TimeUnit.MILLISECONDS.toNanos(200), 0);
        final Thread sessions = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.harness.submit(1);
        });
        sessions.start();
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1, 2_000);
        sessions.join();
    }
}
