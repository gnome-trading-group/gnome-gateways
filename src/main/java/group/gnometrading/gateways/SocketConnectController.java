package group.gnometrading.gateways;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is responsible for managing the connection to the socket.
 * <p>
 * This class should only be used by the supervisor thread. It produces garbage on every connect attempt.
 */
public final class SocketConnectController {

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    private final Logger logger;
    private final Connectable connectable;
    private final Duration connectTimeout;
    private final Duration initialBackoff;
    private final int maxReconnectAttempts;

    private Duration backoff;

    public SocketConnectController(
            Logger logger,
            Connectable connectable,
            Duration connectTimeout,
            int maxReconnectAttempts,
            Duration initialBackoff) {
        this.logger = logger;
        this.connectable = connectable;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.connectTimeout = connectTimeout;
        this.initialBackoff = initialBackoff;
        this.backoff = initialBackoff;
    }

    public void connect() {
        this.logger.log(LogMessage.SOCKET_CONNECTING);
        Exception lastException = null;

        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread socketThread = new Thread(r, "SocketConnectTimeout");
            socketThread.setDaemon(true);
            return socketThread;
        });

        try {
            for (int i = 0; i < 1 + this.maxReconnectAttempts; i++) {
                final ConnectTimeout timeout = new ConnectTimeout(Thread.currentThread());
                final Future<?> timeoutTask =
                        timeoutExecutor.schedule(timeout, this.connectTimeout.toMillis(), TimeUnit.MILLISECONDS);

                try {
                    this.connectable.connect();

                    timeoutTask.cancel(false);
                    if (!timeout.stop()) {
                        this.logger.log(LogMessage.SOCKET_CONNECTED);
                        this.backoff = this.initialBackoff;
                        return;
                    } else {
                        this.logger.log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
                    }

                } catch (Exception e) {
                    timeoutTask.cancel(false);
                    // stop() clears the timeout's own interrupt; any other interrupt is a shutdown and is kept.
                    final boolean timedOut = timeout.stop();

                    if (timedOut) {
                        this.logger.log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
                    } else {
                        this.logger.log(LogMessage.SOCKET_CONNECT_FAILED);
                    }
                    lastException = e;
                }

                try {
                    Thread.sleep(this.backoff.toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }

                this.backoff = this.backoff.multipliedBy(2);
                if (this.backoff.compareTo(MAX_BACKOFF) > 0) {
                    this.backoff = MAX_BACKOFF;
                }
            }

            throw new RuntimeException(lastException);

        } finally {
            timeoutExecutor.shutdown();
        }
    }

    /** Interrupts the connecting thread once an attempt has run too long, unless stopped first. */
    static final class ConnectTimeout implements Runnable {
        private static final int PENDING = 0;
        private static final int FIRING = 1;
        private static final int FIRED = 2;
        private static final int STOPPED = 3;

        private final Thread connectThread;
        private final AtomicInteger state = new AtomicInteger(PENDING);

        ConnectTimeout(final Thread connectThread) {
            this.connectThread = connectThread;
        }

        @Override
        public void run() {
            if (this.state.compareAndSet(PENDING, FIRING)) {
                this.connectThread.interrupt();
                this.state.set(FIRED);
            }
        }

        /**
         * Stops the timeout, returning whether it fired. If it fired, waits for its interrupt to land and clears
         * it: cleared any earlier, the interrupt would arrive later and hit the backoff sleep, stopping the
         * supervisor for good. Cancelling the scheduled task cannot ensure this, as a task already running counts
         * as cancelled.
         */
        boolean stop() {
            if (this.state.compareAndSet(PENDING, STOPPED)) {
                return false;
            }
            while (this.state.get() != FIRED) {
                Thread.onSpinWait();
            }
            Thread.interrupted();
            return true;
        }
    }
}
