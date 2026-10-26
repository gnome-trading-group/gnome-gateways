package group.gnometrading.gateways.feed;

import java.nio.ByteBuffer;

/** Where a connection's raw frames go, each as received, before it is decoded. */
public interface RawDataSink {

    RawDataSink NO_OP = (recvTimestamp, buffer) -> {};

    /**
     * Capture raw exchange bytes before any parsing.
     *
     * @param recvTimestamp epoch nanos when the message was received
     * @param buffer raw payload; position must NOT be advanced by this call
     */
    void capture(long recvTimestamp, ByteBuffer buffer);
}
