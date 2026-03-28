package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import java.nio.ByteBuffer;

public final class JsonWebSocketWriter extends WebSocketWriter {

    private static final int DEFAULT_Json_BODY_BUFFER_SIZE = 1 << 11;

    private final JsonEncoder jsonEncoder;
    private final ThreadLocal<ByteBuffer> jsonBodyBuffer;

    public JsonWebSocketWriter(WebSocketClient socketClient, JsonEncoder jsonEncoder) {
        super(socketClient);
        this.jsonEncoder = jsonEncoder;
        this.jsonBodyBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(DEFAULT_Json_BODY_BUFFER_SIZE));
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
