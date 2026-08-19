package group.gnometrading.gateways.inbound;

import java.nio.ByteBuffer;

public interface RawDataSink {

    /**
     * Capture raw exchange bytes before any parsing.
     *
     * @param recvTimestamp epoch nanos when the message was received
     * @param buffer raw payload; position must NOT be advanced by this call
     */
    void capture(long recvTimestamp, ByteBuffer buffer);

    RawDataSink NO_OP = (recvTimestamp, buffer) -> {};
}
