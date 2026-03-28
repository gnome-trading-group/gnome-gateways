package group.gnometrading.gateways.fix;

public record FixConfig(
        int maxTagCapacity,
        int valueBufferCapacity,
        int writeBufferCapacity,
        boolean enableChecksum,
        FixVersion sessionVersion,
        FixVersion applicationVersion,
        String senderCompID,
        String targetCompID,
        int heartbeatSeconds,
        FixTimestampPrecision defaultPrecision) {

    static final int DEFAULT_MAX_TAG_CAPACITY = 100;
    static final int DEFAULT_VALUE_BUFFER_CAPACITY = 128;
    static final int DEFAULT_WRITE_BUFFER_CAPACITY = 1024; // 1kb
    static final boolean DEFAULT_ENABLE_CHECKSUM = false;
    static final FixVersion DEFAULT_SESSION_VERSION = FixVersion.FIXT_1_1;
    static final FixVersion DEFAULT_APPLICATION_VERSION = FixVersion.FIX_5_0SP2;
    static final int DEFAULT_HEARTBEAT_SECONDS = 30;
    static final FixTimestampPrecision DEFAULT_PRECISION = FixTimestampPrecision.MILLISECONDS;

    public static final class Builder implements group.gnometrading.utils.Builder<FixConfig> {

        private int maxTagCapacity = DEFAULT_MAX_TAG_CAPACITY;
        private int valueBufferCapacity = DEFAULT_VALUE_BUFFER_CAPACITY;
        private int writeBufferCapacity = DEFAULT_WRITE_BUFFER_CAPACITY;
        private boolean enableChecksum = DEFAULT_ENABLE_CHECKSUM;
        private FixVersion sessionVersion = DEFAULT_SESSION_VERSION;
        private FixVersion applicationVersion = DEFAULT_APPLICATION_VERSION;
        private String senderCompID = null;
        private String targetCompID = null;
        private int heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS;
        private FixTimestampPrecision defaultPrecision = DEFAULT_PRECISION;

        public Builder withDefaultPrecision(FixTimestampPrecision precision) {
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

        public Builder withSessionVersion(FixVersion sessionVersion) {
            this.sessionVersion = sessionVersion;
            return this;
        }

        public Builder withApplicationVersion(FixVersion applicationVersion) {
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
        public FixConfig build() {
            return new FixConfig(
                    this.maxTagCapacity,
                    this.valueBufferCapacity,
                    this.writeBufferCapacity,
                    this.enableChecksum,
                    this.sessionVersion,
                    this.applicationVersion,
                    this.senderCompID,
                    this.targetCompID,
                    this.heartbeatSeconds,
                    this.defaultPrecision);
        }
    }
}
