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
    // Requested by shutdown; the reader acts on it at its next pass.
    private volatile boolean stopped;
    // Set by the reader once it will never touch its socket again: only then may the supervisor treat it as gone.
    private volatile boolean exited;
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

    /**
     * Reader thread: holds the reader here while a pause is requested.
     *
     * @return false once stopped: the reader must do nothing more, and above all not touch its socket
     */
    public boolean awaitIfPaused() {
        if (this.pause) {
            this.paused = true;
            while (this.pause && !this.stopped) {
                Thread.yield();
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
        this.pause = true;
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
