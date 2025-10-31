package group.gnometrading.gateways.inbound;

import java.util.concurrent.TimeUnit;

public record MarketInboundGatewayConfig(
        long reconnectIntervalSeconds,
        long keepAliveIntervalSeconds,
        long sanityCheckIntervalSeconds,
        int maxReconnectAttempts,
        long maxSilentIntervalSeconds,
        long initialBackoffSeconds,
        long connectTimeoutSeconds
) {

    static long DEFAULT_RECONNECT_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(12);
    static long DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS = TimeUnit.SECONDS.toSeconds(30);
    static long DEFAULT_SANITY_CHECK_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(1);
    static int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    static long DEFAULT_MAX_SILENT_INTERVAL_SECONDS = TimeUnit.MINUTES.toSeconds(30);
    static long DEFAULT_INITIAL_BACKOFF_SECONDS = TimeUnit.SECONDS.toSeconds(1);
    static long DEFAULT_CONNECT_TIMEOUT_SECONDS = TimeUnit.SECONDS.toSeconds(10);

    public static final class Builder implements group.gnometrading.utils.Builder<MarketInboundGatewayConfig> {

        private long reconnectIntervalSeconds = DEFAULT_RECONNECT_INTERVAL_SECONDS;
        private long keepAliveIntervalSeconds = DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS;
        private long sanityCheckIntervalSeconds = DEFAULT_SANITY_CHECK_INTERVAL_SECONDS;
        private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        private long maxSilentIntervalSeconds = DEFAULT_MAX_SILENT_INTERVAL_SECONDS;
        private long initialBackoffSeconds = DEFAULT_INITIAL_BACKOFF_SECONDS;
        private long connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;

        public Builder withConnectTimeoutSeconds(long connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
            return this;
        }

        public Builder withInitialBackoffSeconds(long initialBackoffSeconds) {
            this.initialBackoffSeconds = initialBackoffSeconds;
            return this;
        }

        public Builder withReconnectIntervalSeconds(long reconnectIntervalSeconds) {
            this.reconnectIntervalSeconds = reconnectIntervalSeconds;
            return this;
        }

        public Builder withKeepAliveIntervalSeconds(long keepAliveIntervalSeconds) {
            this.keepAliveIntervalSeconds = keepAliveIntervalSeconds;
            return this;
        }

        public Builder withSanityCheckIntervalSeconds(long sanityCheckIntervalSeconds) {
            this.sanityCheckIntervalSeconds = sanityCheckIntervalSeconds;
            return this;
        }

        public Builder withMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder withMaxSilentIntervalSeconds(long maxSilentIntervalSeconds) {
            this.maxSilentIntervalSeconds = maxSilentIntervalSeconds;
            return this;
        }

        @Override
        public MarketInboundGatewayConfig build() {
            return new MarketInboundGatewayConfig(
                    this.reconnectIntervalSeconds,
                    this.keepAliveIntervalSeconds,
                    this.sanityCheckIntervalSeconds,
                    this.maxReconnectAttempts,
                    this.maxSilentIntervalSeconds,
                    this.initialBackoffSeconds,
                    this.connectTimeoutSeconds
            );
        }

    }

}
