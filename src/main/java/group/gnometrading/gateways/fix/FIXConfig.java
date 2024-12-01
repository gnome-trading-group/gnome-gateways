package group.gnometrading.gateways.fix;

public record FIXConfig(
        int maxTagCapacity,
        int valueBufferCapacity,
        int writeBufferCapacity,
        boolean enableChecksum,
        FIXVersion sessionVersion,
        FIXVersion applicationVersion,
        String senderCompID,
        String targetCompID,
        int heartbeatSeconds,
        FIXTimestampPrecision defaultPrecision
) {

    static final int DEFAULT_MAX_TAG_CAPACITY = 100;
    static final int DEFAULT_VALUE_BUFFER_CAPACITY = 128;
    static final int DEFAULT_WRITE_BUFFER_CAPACITY = 1024; // 1kb
    static final boolean DEFAULT_ENABLE_CHECKSUM = false;
    static final FIXVersion DEFAULT_SESSION_VERSION = FIXVersion.FIXT_1_1;
    static final FIXVersion DEFAULT_APPLICATION_VERSION = FIXVersion.FIX_5_0SP2;
    static final int DEFAULT_HEARTBEAT_SECONDS = 30;
    static final FIXTimestampPrecision DEFAULT_PRECISION = FIXTimestampPrecision.MILLISECONDS;

    public static final class Builder implements group.gnometrading.utils.Builder<FIXConfig> {

        private int maxTagCapacity = DEFAULT_MAX_TAG_CAPACITY;
        private int valueBufferCapacity = DEFAULT_VALUE_BUFFER_CAPACITY;
        private int writeBufferCapacity = DEFAULT_WRITE_BUFFER_CAPACITY;
        private boolean enableChecksum = DEFAULT_ENABLE_CHECKSUM;
        private FIXVersion sessionVersion = DEFAULT_SESSION_VERSION;
        private FIXVersion applicationVersion = DEFAULT_APPLICATION_VERSION;
        private String senderCompID = null;
        private String targetCompID = null;
        private int heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS;
        private FIXTimestampPrecision defaultPrecision = DEFAULT_PRECISION;

        public Builder withDefaultPrecision(FIXTimestampPrecision precision) {
            this.defaultPrecision = precision;
            return this;
        }

        public Builder withHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
            return this;
        }

        public Builder withSenderCompID(String senderCompID) {
            this.senderCompID = senderCompID;
            return this;
        }

        public Builder withTargetCompID(String targetCompID) {
            this.targetCompID = targetCompID;
            return this;
        }

        public Builder withSessionVersion(FIXVersion sessionVersion) {
            this.sessionVersion = sessionVersion;
            return this;
        }

        public Builder withApplicationVersion(FIXVersion applicationVersion) {
            this.applicationVersion = applicationVersion;
            return this;
        }

        public Builder withWriteBufferCapacity(int writeBufferCapacity) {
            this.writeBufferCapacity = writeBufferCapacity;
            return this;
        }

        public Builder withMaxTagCapacity(int maxTagCapacity) {
            this.maxTagCapacity = maxTagCapacity;
            return this;
        }

        public Builder withValueBufferCapacity(int valueBufferCapacity) {
            this.valueBufferCapacity = valueBufferCapacity;
            return this;
        }

        public Builder withEnableChecksum(boolean enableChecksum) {
            this.enableChecksum = enableChecksum;
            return this;
        }

        @Override
        public FIXConfig build() {
            return new FIXConfig(
                    this.maxTagCapacity,
                    this.valueBufferCapacity,
                    this.writeBufferCapacity,
                    this.enableChecksum,
                    this.sessionVersion,
                    this.applicationVersion,
                    this.senderCompID,
                    this.targetCompID,
                    this.heartbeatSeconds,
                    this.defaultPrecision
            );
        }
    }
}
