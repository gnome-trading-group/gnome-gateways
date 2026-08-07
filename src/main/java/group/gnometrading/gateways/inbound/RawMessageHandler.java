package group.gnometrading.gateways.inbound;

import java.nio.ByteBuffer;

/** Receives an unmodified inbound gateway message before venue-specific decoding. */
@FunctionalInterface
public interface RawMessageHandler {

    RawMessageHandler NO_OP = (message, offset, length, receiveTimestampNanos) -> {};

    /**
     * Handles one raw gateway message.
     *
     * <p>The buffer is shared with the decoder. Implementations must not change its position,
     * limit, or contents, and must copy any bytes they retain after this method returns. Passing
     * explicit bounds avoids allocating a duplicate buffer in the socket hot path.
     *
     * @param message raw gateway message
     * @param offset first byte of the message in {@code message}
     * @param length number of message bytes
     * @param receiveTimestampNanos local receive timestamp from the gateway clock
     */
    void onMessage(ByteBuffer message, int offset, int length, long receiveTimestampNanos) throws Exception;
}
