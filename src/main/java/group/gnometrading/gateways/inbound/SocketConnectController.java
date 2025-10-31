package group.gnometrading.gateways.inbound;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;

/**
 * This class is responsible for managing the connection to the socket.
 * <p>
 * This class should only be used by the supervisor thread. It produces garbage on every connect attempt.
 */
public class SocketConnectController {

    private static final long MAX_BACKOFF_SECONDS = 10;

    private final Logger logger;
    private final SocketReader<?> socketReader;
    private final long connectTimeoutSeconds;
    private final int maxReconnectAttempts;
    private final long initialBackoffSeconds;

    private long backoffSeconds;
    private volatile Thread connectThread;
    private volatile boolean timeoutOccurred;

    public SocketConnectController(
            Logger logger,
            SocketReader<?> socketReader,
            long connectTimeoutSeconds,
            int maxReconnectAttempts,
            long initialBackoffSeconds
    ) {
        this.logger = logger;
        this.socketReader = socketReader;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.initialBackoffSeconds = initialBackoffSeconds;

        this.backoffSeconds = initialBackoffSeconds;
    }

    public void connect() {
        this.logger.log(LogMessage.SOCKET_CONNECTING);
        Exception lastException = null;
        for (int i = 0; i < this.maxReconnectAttempts; i++) {
            this.connectThread = Thread.currentThread();
            this.timeoutOccurred = false;

            final var watchdog = createWatchdogThread();

            try {
                this.socketReader.connect();

                // Success - cancel watchdog
                watchdog.interrupt();
                this.connectThread = null;

                if (!this.timeoutOccurred) {
                    this.logger.log(LogMessage.SOCKET_CONNECTED);
                    this.backoffSeconds = this.initialBackoffSeconds;
                    // Clear interrupt flag if it was set
                    Thread.interrupted();
                    return;
                } else {
                    this.logger.log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
                }
            } catch (Exception e) {
                lastException = e;

                watchdog.interrupt();
                this.connectThread = null;

                if (this.timeoutOccurred) {
                    this.logger.log(LogMessage.SOCKET_CONNECT_TIMED_OUT);
                } else {
                    this.logger.log(LogMessage.SOCKET_CONNECT_FAILED);
                }
            }

            try {
                Thread.sleep(this.backoffSeconds * 1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }

            this.backoffSeconds = Math.min(this.backoffSeconds * 2, MAX_BACKOFF_SECONDS);
        }

        throw new RuntimeException(lastException);
    }

    private Thread createWatchdogThread() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(this.connectTimeoutSeconds * 1000);
                // Timeout occurred - interrupt the connect thread
                Thread targetThread = this.connectThread;
                if (targetThread != null) {
                    this.timeoutOccurred = true;
                    targetThread.interrupt();
                }
            } catch (InterruptedException e) {
                // Watchdog was interrupted (connect succeeded), this is normal
            }
        }, "SocketConnectWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

}
