package group.gnometrading.gateways.inbound;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class is responsible for managing the connection to the socket.
 * <p>
 * This class should only be used by the supervisor thread. It produces garbage on every connect attempt.
 */
public class SocketConnectController {

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    private final Logger logger;
    private final SocketReader<?> socketReader;
    private final Duration connectTimeout, initialBackoff;
    private final int maxReconnectAttempts;

    private Duration backoff;

    public SocketConnectController(
            Logger logger,
            SocketReader<?> socketReader,
            Duration connectTimeout,
            int maxReconnectAttempts,
            Duration initialBackoff
    ) {
        this.logger = logger;
        this.socketReader = socketReader;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.connectTimeout = connectTimeout;
        this.initialBackoff = initialBackoff;
        this.backoff = initialBackoff;
    }

    public void connect() {
        this.logger.log(LogMessage.SOCKET_CONNECTING);
        Exception lastException = null;

        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SocketConnectTimeout");
            t.setDaemon(true);
            return t;
        });

        try {
            for (int i = 0; i < 1 + this.maxReconnectAttempts; i++) {
                Thread connectThread = Thread.currentThread();
                AtomicBoolean timedOut = new AtomicBoolean(false);

                Future<?> timeoutTask = timeoutExecutor.schedule(() -> {
                    timedOut.set(true);
                    connectThread.interrupt();
                }, this.connectTimeout.toMillis(), TimeUnit.MILLISECONDS);

                try {
                    this.socketReader.connect();

                    timeoutTask.cancel(false);

                    if (!timedOut.get()) {
                        this.logger.log(LogMessage.SOCKET_CONNECTED);
                        this.backoff = this.initialBackoff;
                        return;
                    } else {
                        this.logger.log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
                    }

                } catch (Exception e) {
                    timeoutTask.cancel(false);

                    if (timedOut.get()) {
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

}
