package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;

import java.nio.ByteBuffer;

public class JSONWebSocketWriter extends WebSocketWriter {

    private static final int DEFAULT_JSON_BODY_BUFFER_SIZE = 1 << 11;

    private final JSONEncoder jsonEncoder;
    private final ThreadLocal<ByteBuffer> jsonBodyBuffer;

    public JSONWebSocketWriter(WebSocketClient socketClient, JSONEncoder jsonEncoder) {
        super(socketClient);
        this.jsonEncoder = jsonEncoder;
        this.jsonBodyBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(DEFAULT_JSON_BODY_BUFFER_SIZE));
    }

    public JSONEncoder getJSONEncoder() {
        final var jsonBodyBuffer = this.jsonBodyBuffer.get();
        jsonBodyBuffer.clear();
        this.jsonEncoder.wrap(jsonBodyBuffer);
        return this.jsonEncoder;
    }

    public ByteBuffer getAndFlipJSONBodyBuffer() {
        return this.jsonBodyBuffer.get().flip();
    }

}
