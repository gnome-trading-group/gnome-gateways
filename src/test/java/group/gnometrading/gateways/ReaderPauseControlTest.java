package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReaderPauseControlTest {

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closeOnNextPass = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);
    private Thread readerThread;

    @AfterEach
    void tearDown() throws InterruptedException {
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread.join(1_000);
        }
    }

    @Test
    void pause_ReturnsOnceTheReaderStops() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        startReader(control);

        control.pause();

        assertTrue(control.isPaused());
    }

    @Test
    void resume_ReturnsOnceTheReaderRuns() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        startReader(control);
        control.pause();

        control.resume();

        assertFalse(control.isPaused());
    }

    @Test
    void resume_ReaderPausesItselfBeforeTheSupervisorLooks_StillReturns() throws Exception {
        // The new socket closes the moment the reader runs (auth refused, reset), and the reader pauses itself
        // again before the supervisor gets to check: the supervisor must still see that it ran.
        final AtomicBoolean holdNextIdle = new AtomicBoolean();
        final AtomicReference<ReaderPauseControl> ref = new AtomicReference<>();
        final ReaderPauseControl control = new ReaderPauseControl(() -> {
            if (holdNextIdle.compareAndSet(true, false)) {
                awaitQuietly(closed);
                while (!ref.get().isPaused()) {
                    Thread.onSpinWait();
                }
            }
            Thread.yield();
        });
        ref.set(control);
        startReader(control);
        control.pause();

        closeOnNextPass.set(true);
        holdNextIdle.set(true);
        final Thread supervisor = new Thread(control::resume, "supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
        supervisor.join(2_000);

        assertFalse(supervisor.isAlive(), "resume waited for a reader that had already run and stopped again");
        control.pause();
        assertTrue(control.isPaused(), "the reader stays paused until the supervisor resumes it");
    }

    @Test
    void pause_ThenResumeAgainAfterSelfPause_HandsBackCleanly() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        startReader(control);
        control.pause();
        control.resume();

        closeOnNextPass.set(true);
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        control.pause();
        control.resume();

        assertFalse(control.isPaused());
    }

    @Test
    void pause_Interrupted_StopsWaiting() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread supervisor = new Thread(
                () -> {
                    try {
                        control.pause(); // no reader thread, so nothing ever acknowledges
                    } catch (final Throwable t) {
                        failure.set(t);
                    }
                },
                "supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
        supervisor.join(100);
        assertTrue(supervisor.isAlive());

        supervisor.interrupt();
        supervisor.join(2_000);

        assertFalse(supervisor.isAlive(), "a connect timeout or shutdown can break the wait");
        assertNotNull(failure.get());
    }

    /** Stands in for a reader's doWork loop: waits while paused, and pauses itself when its socket closes. */
    private void startReader(final ReaderPauseControl control) {
        readerThread = new Thread(
                () -> {
                    while (running.get()) {
                        control.awaitIfPaused();
                        if (closeOnNextPass.compareAndSet(true, false)) {
                            control.pauseSelf();
                            closed.countDown();
                        }
                    }
                },
                "reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
