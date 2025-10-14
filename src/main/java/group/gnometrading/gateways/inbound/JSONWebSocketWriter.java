package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;

import java.nio.ByteBuffer;

public class JSONWebSocketWriter extends WebSocketWriter {

    private final JSONEncoder jsonEncoder;

    public JSONWebSocketWriter(WebSocketClient socketClient, JSONEncoder jsonEncoder) {
        super(socketClient);
        this.jsonEncoder = jsonEncoder;
    }

    public JSONEncoder getWrappedJSONEncoder(boolean isControlMessage) {
        final ByteBuffer buffer = isControlMessage ? this.getControlWriteBuffer() : this.getWriteBuffer();
        this.jsonEncoder.wrap(buffer);
        return this.jsonEncoder;
    }

}
