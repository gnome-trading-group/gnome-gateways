package group.gnometrading.gateways.inbound;

import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class WebSocketWriter extends SocketWriter {

    private final WebSocketClient socketClient;

    public WebSocketWriter(WebSocketClient socketClient) {
        super();
        this.socketClient = socketClient;
    }

    @Override
    protected void write(ByteBuffer buffer) throws IOException {
        this.socketClient.send(Opcode.TEXT, buffer);
    }

}
