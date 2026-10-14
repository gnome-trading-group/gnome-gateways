package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import java.nio.ByteBuffer;

public final class InboundJsonWebSocketWriter extends InboundWebSocketWriter {

    private final JsonEncoder jsonEncoder;
    private final ThreadLocal<ByteBuffer> jsonBodyBuffer;

    public InboundJsonWebSocketWriter(WebSocketClient socketClient, JsonEncoder jsonEncoder) {
        super(socketClient);
        this.jsonEncoder = jsonEncoder;
        // No bigger than a write slot holds once framed: a body too long to send then fails as it is written, where
        // the cause is plain.
        final int bodySize = slotSize() - MAX_FRAME_HEADER;
        this.jsonBodyBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(bodySize));
    }

    public JsonEncoder getJsonEncoder() {
        final var jsonBodyBuffer = this.jsonBodyBuffer.get();
        jsonBodyBuffer.clear();
        this.jsonEncoder.wrap(jsonBodyBuffer);
        return this.jsonEncoder;
    }

    public ByteBuffer getAndFlipJsonBodyBuffer() {
        return this.jsonBodyBuffer.get().flip();
    }
}
