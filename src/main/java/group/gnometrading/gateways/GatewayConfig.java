package group.gnometrading.gateways;

import group.gnometrading.networking.websockets.WebSocketClient;
import java.io.IOException;
import java.time.Duration;

/**
 * @param maxReconnectAttempts attempts after the first in one connect cycle, with backoff between them
 * @param maxConnectCycles connect cycles that may fail in a row before the gateway gives up and reports it fatal
 * @param keepAliveInterval how often the gateway sends its application-level keep-alive (e.g. a ping message)
 * @param connectTimeout how long one whole connect attempt may take: socket connect, upgrade, subscribe and
 *     snapshot
 * @param socketConnectTimeout bounds the TCP connect and TLS handshake within a connect attempt
 * @param handshakeTimeout bounds the WebSocket upgrade within a connect attempt
 * @param readTimeout bounds each blocking socket read, so a reader on a silently dead connection still notices
 *     a pause or disconnect request
 * @param tcpKeepAliveIdle silence before the kernel starts probing the peer
 * @param tcpKeepAliveInterval time between kernel probes
 * @param tcpKeepAliveProbes unanswered probes before the kernel declares the peer dead
 * @param spinReads whether inbound readers poll a non-blocking socket instead of sleeping in a blocking read. Only
 *     worth it on a core the reader has to itself: waking from a blocking read cost ~130us per message on c7i
 *     against single-digit microseconds spinning, but a spinning reader on a shared core starves its neighbours.
 *     The read timeout then no longer applies; a silent connection is still caught by {@code maxSilentInterval}.
 */
public record GatewayConfig(
        Duration reconnectInterval,
        Duration keepAliveInterval,
        Duration sanityCheckInterval,
        int maxReconnectAttempts,
        int maxConnectCycles,
        Duration maxSilentInterval,
        Duration initialBackoff,
        Duration connectTimeout,
        Duration socketConnectTimeout,
        Duration handshakeTimeout,
        Duration readTimeout,
        Duration tcpKeepAliveIdle,
        Duration tcpKeepAliveInterval,
        int tcpKeepAliveProbes,
        boolean spinReads) {

    public GatewayConfig {
        // On a quiet feed the keep-alive reply is the only traffic, so replies must land well inside the silence
        // window; at equal intervals every round trip pushes the gap past it and triggers a spurious reconnect.
        requireAtMostHalf(keepAliveInterval, maxSilentInterval, "keepAliveInterval", "maxSilentInterval");
        // A reader blocked in a read only notices a pause once the read returns.
        requireAtMostHalf(readTimeout, maxSilentInterval, "readTimeout", "maxSilentInterval");
        // A connect attempt that outlives the controller's timeout is treated as failed even if it then succeeds,
        // and the next attempt tears it down, so the socket's own timeouts must leave room inside it.
        if (socketConnectTimeout.plus(handshakeTimeout).compareTo(connectTimeout) >= 0) {
            throw new IllegalArgumentException("socketConnectTimeout (" + socketConnectTimeout
                    + ") plus handshakeTimeout (" + handshakeTimeout + ") must be less than connectTimeout ("
                    + connectTimeout + ")");
        }
        if (maxReconnectAttempts < 0) {
            throw new IllegalArgumentException("maxReconnectAttempts must not be negative");
        }
        if (maxConnectCycles < 1) {
            throw new IllegalArgumentException("maxConnectCycles must be at least 1");
        }
        if (readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        if (tcpKeepAliveProbes <= 0 || tcpKeepAliveIdle.getSeconds() <= 0 || tcpKeepAliveInterval.getSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "TCP keep-alive idle, interval (whole seconds) and probes must be positive");
        }
    }

    /** A copy that spins or blocks on reads; set by whoever knows the reader's core placement, not per exchange. */
    public GatewayConfig withSpinReads(final boolean spin) {
        return new GatewayConfig(
                reconnectInterval,
                keepAliveInterval,
                sanityCheckInterval,
                maxReconnectAttempts,
                maxConnectCycles,
                maxSilentInterval,
                initialBackoff,
                connectTimeout,
                socketConnectTimeout,
                handshakeTimeout,
                readTimeout,
                tcpKeepAliveIdle,
                tcpKeepAliveInterval,
                tcpKeepAliveProbes,
                spin);
    }

    /** Applies these socket settings to a WebSocket client, which keeps them for every connection it makes. */
    public void configure(final WebSocketClient client, final boolean blocking) throws IOException {
        client.setConnectTimeout((int) this.socketConnectTimeout.toMillis());
        client.setHandshakeTimeout((int) this.handshakeTimeout.toMillis());
        client.setReadTimeout((int) this.readTimeout.toMillis());
        client.configureBlocking(blocking);
        client.setTcpNoDelay(true);
        client.setKeepAlive(
                (int) this.tcpKeepAliveIdle.getSeconds(),
                (int) this.tcpKeepAliveInterval.getSeconds(),
                this.tcpKeepAliveProbes);
    }

    private static void requireAtMostHalf(Duration part, Duration whole, String partName, String wholeName) {
        if (part.multipliedBy(2).compareTo(whole) > 0) {
            throw new IllegalArgumentException(
                    partName + " (" + part + ") must be at most half of " + wholeName + " (" + whole + ")");
        }
    }

    static final Duration DEFAULT_RECONNECT_INTERVAL = Duration.ofHours(12);
    static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(10);
    static final Duration DEFAULT_SANITY_CHECK_INTERVAL = Duration.ofHours(1);
    static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    static final int DEFAULT_MAX_CONNECT_CYCLES = 3;
    static final Duration DEFAULT_MAX_SILENT_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_SOCKET_CONNECT_TIMEOUT = Duration.ofSeconds(4);
    static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(3);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(1);
    // Kernel defaults wait over two hours before probing an idle peer; these give up on a dead one in about 25s.
    static final Duration DEFAULT_TCP_KEEP_ALIVE_IDLE = Duration.ofSeconds(10);
    static final Duration DEFAULT_TCP_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(5);
    static final int DEFAULT_TCP_KEEP_ALIVE_PROBES = 3;

    public static final class Builder implements group.gnometrading.utils.Builder<GatewayConfig> {

        private Duration reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
        private Duration keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;
        private Duration sanityCheckInterval = DEFAULT_SANITY_CHECK_INTERVAL;
        private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        private int maxConnectCycles = DEFAULT_MAX_CONNECT_CYCLES;
        private Duration maxSilentInterval = DEFAULT_MAX_SILENT_INTERVAL;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration socketConnectTimeout = DEFAULT_SOCKET_CONNECT_TIMEOUT;
        private Duration handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration tcpKeepAliveIdle = DEFAULT_TCP_KEEP_ALIVE_IDLE;
        private Duration tcpKeepAliveInterval = DEFAULT_TCP_KEEP_ALIVE_INTERVAL;
        private int tcpKeepAliveProbes = DEFAULT_TCP_KEEP_ALIVE_PROBES;

        public Builder withConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder withSocketConnectTimeout(Duration socketConnectTimeout) {
            this.socketConnectTimeout = socketConnectTimeout;
            return this;
        }

        public Builder withHandshakeTimeout(Duration handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder withReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder withTcpKeepAlive(Duration idle, Duration interval, int probes) {
            this.tcpKeepAliveIdle = idle;
            this.tcpKeepAliveInterval = interval;
            this.tcpKeepAliveProbes = probes;
            return this;
        }

        public Builder withInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder withReconnectInterval(Duration reconnectInterval) {
            this.reconnectInterval = reconnectInterval;
            return this;
        }

        public Builder withKeepAliveInterval(Duration keepAliveInterval) {
            this.keepAliveInterval = keepAliveInterval;
            return this;
        }

        public Builder withSanityCheckInterval(Duration sanityCheckInterval) {
            this.sanityCheckInterval = sanityCheckInterval;
            return this;
        }

        public Builder withMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder withMaxConnectCycles(int maxConnectCycles) {
            this.maxConnectCycles = maxConnectCycles;
            return this;
        }

        public Builder withMaxSilentInterval(Duration maxSilentInterval) {
            this.maxSilentInterval = maxSilentInterval;
            return this;
        }

        @Override
        public GatewayConfig build() {
            return new GatewayConfig(
                    this.reconnectInterval,
                    this.keepAliveInterval,
                    this.sanityCheckInterval,
                    this.maxReconnectAttempts,
                    this.maxConnectCycles,
                    this.maxSilentInterval,
                    this.initialBackoff,
                    this.connectTimeout,
                    this.socketConnectTimeout,
                    this.handshakeTimeout,
                    this.readTimeout,
                    this.tcpKeepAliveIdle,
                    this.tcpKeepAliveInterval,
                    this.tcpKeepAliveProbes,
                    false);
        }
    }
}
