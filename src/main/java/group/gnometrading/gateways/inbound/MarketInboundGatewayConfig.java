package group.gnometrading.gateways.inbound;

import java.time.Duration;

public record MarketInboundGatewayConfig(
        Duration reconnectInterval,
        Duration keepAliveInterval,
        Duration sanityCheckInterval,
        int maxReconnectAttempts,
        Duration maxSilentInterval,
        Duration initialBackoff,
        Duration connectTimeout
) {

    static Duration DEFAULT_RECONNECT_INTERVAL = Duration.ofHours(12);
    static Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(30);
    static Duration DEFAULT_SANITY_CHECK_INTERVAL = Duration.ofHours(1);
    static int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    static Duration DEFAULT_MAX_SILENT_INTERVAL = Duration.ofSeconds(30);
    static Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    static Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public static final class Builder implements group.gnometrading.utils.Builder<MarketInboundGatewayConfig> {

        private Duration reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
        private Duration keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;
        private Duration sanityCheckInterval = DEFAULT_SANITY_CHECK_INTERVAL;
        private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        private Duration maxSilentInterval = DEFAULT_MAX_SILENT_INTERVAL;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        public Builder withConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
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

        public Builder withMaxSilentInterval(Duration maxSilentInterval) {
            this.maxSilentInterval = maxSilentInterval;
            return this;
        }

        @Override
        public MarketInboundGatewayConfig build() {
            return new MarketInboundGatewayConfig(
                    this.reconnectInterval,
                    this.keepAliveInterval,
                    this.sanityCheckInterval,
                    this.maxReconnectAttempts,
                    this.maxSilentInterval,
                    this.initialBackoff,
                    this.connectTimeout
            );
        }

    }

}
