package group.gnometrading.gateways;

import group.gnometrading.annotations.VisibleForTesting;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Hands a reader's socket between its own thread and the supervisor, which stops the reader while it connects or
 * disconnects. The reader starts paused, and pauses itself when it sees its socket close.
 */
public final class ReaderPauseControl {

    // A pause lasts as long as a reconnect: seconds, or minutes while a venue keeps refusing. Yielding all that time
    // spins a core (a shard with a refused venue sat at 100% CPU), so after a short spin the reader parks, backing off
    // to a millisecond at a time: a resume then reaches it within a millisecond.
    private static final int YIELDS_BEFORE_PARKING = 100;
    private static final int MAX_PARK_SHIFT = 20;
    private static final long MAX_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final Runnable supervisorIdle;

    private volatile boolean pause = true;
    private volatile boolean paused;
    // Set by the reader on a socket it found broken; cleared only by the resume that follows a reconnect.
    private volatile boolean pausedItself;
    // Requested by shutdown; the reader acts on it at its next pass.
    private volatile boolean stopped;
    // Set by the reader once it will never touch its socket again: only then may the supervisor treat it as gone.
    private volatile boolean exited;
    // A resume is acknowledged by count, not by the reader's state: the reader may run and pause itself again
    // before the supervisor looks, and its state alone would then read as never having resumed.
    private volatile long resumeGeneration;
    private volatile long resumedGeneration;
    // What else a stop must reach: a reader blocked writing to a full ring never comes back to look at stopped.
    private volatile Runnable onStop = () -> {};

    public ReaderPauseControl() {
        this(Thread::yield);
    }

    @VisibleForTesting
    ReaderPauseControl(final Runnable supervisorIdle) {
        this.supervisorIdle = supervisorIdle;
    }

    /**
     * Reader thread: holds the reader here while a pause is requested.
     *
     * @return false once stopped: the reader must do nothing more, and above all not touch its socket
     */
    public boolean awaitIfPaused() {
        if (this.pause) {
            this.paused = true;
            int idles = 0;
            while (this.pause && !this.stopped) {
                idles = readerIdle(idles);
            }
            if (!this.stopped) {
                this.paused = false;
                this.resumedGeneration = this.resumeGeneration;
            }
        }
        if (this.stopped) {
            this.exited = true;
            return false;
        }
        return true;
    }

    /** Reader thread, once it has left its work loop for good: whatever stopped it, it is done with its socket. */
    public void readerExited() {
        this.exited = true;
    }

    /** Reader thread: stops itself until the supervisor next resumes it. */
    public void pauseSelf() {
        this.pausedItself = true;
        this.pause = true;
    }

    /** Whether the reader stopped itself on a broken socket; it should stay stopped until the socket is reopened. */
    public boolean pausedItself() {
        return this.pausedItself;
    }

    /** Supervisor: returns once the reader has stopped and will not touch its socket until resumed. */
    public void pause() {
        this.pause = true;
        while (!this.paused && !this.exited) {
            idle();
        }
    }

    /**
     * Supervisor: returns once the reader thread has run again. Waiting for it matters: until it notices,
     * {@link #paused} still reads true from before, and a pause straight after would take that stale value as an
     * acknowledgement while the reader is in fact about to run.
     */
    public void resume() {
        final long generation = this.resumeGeneration + 1;
        this.resumeGeneration = generation;
        this.pausedItself = false;
        this.pause = false;
        while (this.resumedGeneration < generation && !this.exited) {
            idle();
        }
    }

    /**
     * Shutdown: asks the reader to stop for good, even mid-pause. The supervisor still waits until the reader has
     * actually stopped, at its next pass, before touching the socket: a read may be in flight when this is called.
     */
    public void stop() {
        this.stopped = true;
        this.onStop.run();
    }

    /**
     * Reader, once, as it is built: what a stop must also release. A reader waiting for room in its output ring, behind
     * a consumer that has stopped polling, never returns to its next pass to see the stop.
     */
    public void onStop(final Runnable action) {
        this.onStop = action;
    }

    private static int readerIdle(final int idles) {
        if (idles < YIELDS_BEFORE_PARKING) {
            Thread.yield();
            return idles + 1;
        }
        final int shift = Math.min(idles - YIELDS_BEFORE_PARKING, MAX_PARK_SHIFT);
        LockSupport.parkNanos(Math.min(1L << shift, MAX_PARK_NANOS));
        return Math.min(idles + 1, YIELDS_BEFORE_PARKING + MAX_PARK_SHIFT);
    }

    /** Leaves the interrupt set, so a shutdown still sees it; a connect timeout clears its own. */
    private void idle() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted waiting for the reader thread");
        }
        this.supervisorIdle.run();
    }

    /** Lets a reader run without a supervisor, for tests that drive it directly. */
    @VisibleForTesting
    public void release() {
        this.pause = false;
    }

    /** Whether the reader is held off its socket: while the supervisor connects, reconnects or shuts it down. */
    public boolean isPauseRequested() {
        return this.pause;
    }

    @VisibleForTesting
    public boolean isPaused() {
        return this.paused;
    }
}
