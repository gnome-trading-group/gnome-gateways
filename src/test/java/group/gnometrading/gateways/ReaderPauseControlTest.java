package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
    void stop_AlsoReleasesWhatTheReaderRegistered() {
        final ReaderPauseControl control = new ReaderPauseControl();
        final AtomicBoolean released = new AtomicBoolean();
        control.onStop(() -> released.set(true));

        control.stop();

        assertTrue(released.get());
        assertFalse(control.awaitIfPaused(), "and the reader itself is stopped");
    }

    @Test
    void pause_ReturnsOnceTheReaderStops() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        startReader(control);

        control.pause();

        assertTrue(control.isPaused());
    }

    @Test
    void awaitIfPaused_LongPause_ReaderBarelyUsesTheCpu() throws Exception {
        // A connection a venue keeps refusing stays paused for minutes; its reader must not spin a core meanwhile.
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadCpuTimeSupported());
        final ReaderPauseControl control = new ReaderPauseControl();
        startReader(control);
        control.pause();
        final long before = threads.getThreadCpuTime(readerThread.getId());

        sleepQuietly(500);

        final long usedMillis = TimeUnit.NANOSECONDS.toMillis(threads.getThreadCpuTime(readerThread.getId()) - before);
        assertTrue(usedMillis < 100, "a paused reader used " + usedMillis + " ms of CPU in 500 ms");
        control.resume();
        assertFalse(control.isPaused(), "and still resumes");
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

    @Test
    void stop_ReleasesAPausedReaderWithoutLettingItRun() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        final AtomicBoolean ranAfterStop = new AtomicBoolean();
        final CountDownLatch returned = new CountDownLatch(1);
        readerThread = new Thread(
                () -> {
                    if (!control.awaitIfPaused()) {
                        returned.countDown();
                        return;
                    }
                    ranAfterStop.set(true);
                },
                "reader");
        readerThread.setDaemon(true);
        readerThread.start();
        control.pause();

        control.stop();

        assertTrue(returned.await(2, TimeUnit.SECONDS), "a paused reader returns once stopped");
        assertFalse(ranAfterStop.get(), "and does nothing more");
    }

    @Test
    void stop_SupervisorStillWaitsForAReadInFlight() throws Exception {
        final ReaderPauseControl control = new ReaderPauseControl();
        final AtomicBoolean reading = new AtomicBoolean();
        final CountDownLatch inRead = new CountDownLatch(1);
        readerThread = new Thread(
                () -> {
                    while (control.awaitIfPaused()) {
                        reading.set(true);
                        inRead.countDown();
                        sleepQuietly(300); // a read in flight on the socket
                        reading.set(false);
                    }
                },
                "reader");
        readerThread.setDaemon(true);
        control.release();
        readerThread.start();
        assertTrue(inRead.await(2, TimeUnit.SECONDS));

        control.stop();
        control.pause(); // as a supervisor reconnecting during shutdown would

        assertFalse(reading.get(), "the supervisor may not touch the socket while the reader is still reading it");
    }

    @Test
    void readerExited_SupervisorNoLongerWaitsOnAReaderThatIsGone() {
        final ReaderPauseControl control = new ReaderPauseControl();
        control.release();
        // The reader's thread has left its loop, as at shutdown, without another pass.
        control.readerExited();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), control::pause);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), control::resume);
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

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aReaderThatStoppedItselfStaysMarkedUntilTheNextResume() {
        final ReaderPauseControl control = new ReaderPauseControl();
        control.release();
        assertFalse(control.pausedItself());

        control.pauseSelf();
        assertTrue(control.pausedItself(), "a handover finding this leaves the reader held");
        assertTrue(control.isPauseRequested());
    }
}
