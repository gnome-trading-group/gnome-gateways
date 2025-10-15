package group.gnometrading.gateways.inbound;

import java.util.concurrent.TimeUnit;

public record MarketInboundGatewayConfig(
        long reconnectIntervalSeconds,
        long keepAliveIntervalSeconds,
        long sanityCheckIntervalSeconds
) {

    static long DEFAULT_RECONNECT_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(12);
    static long DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS = TimeUnit.SECONDS.toSeconds(30);
    static long DEFAULT_SANITY_CHECK_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(1);

    public static final class Builder implements group.gnometrading.utils.Builder<MarketInboundGatewayConfig> {

        private long reconnectIntervalSeconds = DEFAULT_RECONNECT_INTERVAL_SECONDS;
        private long keepAliveIntervalSeconds = DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS;
        private long sanityCheckIntervalSeconds = DEFAULT_SANITY_CHECK_INTERVAL_SECONDS;

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

        @Override
        public MarketInboundGatewayConfig build() {
            return new MarketInboundGatewayConfig(
                    this.reconnectIntervalSeconds,
                    this.keepAliveIntervalSeconds,
                    this.sanityCheckIntervalSeconds
            );
        }

    }

}
