package group.gnometrading.gateways;

import group.gnometrading.annotations.VisibleForTesting;

/**
 * Hands a reader's socket between its own thread and the supervisor, which stops the reader while it connects or
 * disconnects. The reader starts paused, and pauses itself when it sees its socket close.
 */
public final class ReaderPauseControl {

    private final Runnable supervisorIdle;

    private volatile boolean pause = true;
    private volatile boolean paused;
    // A resume is acknowledged by count, not by the reader's state: the reader may run and pause itself again
    // before the supervisor looks, and its state alone would then read as never having resumed.
    private volatile long resumeGeneration;
    private volatile long resumedGeneration;

    public ReaderPauseControl() {
        this(Thread::yield);
    }

    @VisibleForTesting
    ReaderPauseControl(final Runnable supervisorIdle) {
        this.supervisorIdle = supervisorIdle;
    }

    /** Reader thread: holds the reader here while a pause is requested. */
    public void awaitIfPaused() {
        if (this.pause) {
            this.paused = true;
            while (this.pause) {
                Thread.yield();
            }
            this.paused = false;
            this.resumedGeneration = this.resumeGeneration;
        }
    }

    /** Reader thread: stops itself until the supervisor next resumes it. */
    public void pauseSelf() {
        this.pause = true;
    }

    /** Supervisor: returns once the reader has stopped and will not touch its socket until resumed. */
    public void pause() {
        this.pause = true;
        while (!this.paused) {
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
        this.pause = false;
        while (this.resumedGeneration < generation) {
            idle();
        }
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

    @VisibleForTesting
    public boolean isPauseRequested() {
        return this.pause;
    }

    @VisibleForTesting
    public boolean isPaused() {
        return this.paused;
    }
}
