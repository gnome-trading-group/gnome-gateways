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

    public void writePong() {
        final int controlWriteSequence = this.claimControlWriteBuffer();
        final ByteBuffer buffer = this.getControlWriteBuffer(controlWriteSequence);
        this.socketClient.wrapPongMessage(buffer);
        this.publishControlWriteBuffer(controlWriteSequence);
    }

    public void writePing() {
        final int controlWriteSequence = this.claimControlWriteBuffer();
        final ByteBuffer buffer = this.getControlWriteBuffer(controlWriteSequence);
        this.socketClient.wrapPingMessage(buffer);
        this.publishControlWriteBuffer(controlWriteSequence);
    }

    public void writeText(final ByteBuffer payload, boolean isControlMessage) {
        final int writeSequence = isControlMessage ? this.claimControlWriteBuffer() : this.claimWriteBuffer();
        final ByteBuffer buffer = isControlMessage ? this.getControlWriteBuffer(writeSequence) : this.getWriteBuffer(writeSequence);

        this.socketClient.wrapMessage(buffer, Opcode.TEXT, payload);

        if (isControlMessage) {
            this.publishControlWriteBuffer(writeSequence);
        } else {
            this.publishWriteBuffer(writeSequence);
        }
    }

    @Override
    protected void write(ByteBuffer buffer) throws IOException {
        this.socketClient.writeBuffer(buffer);
    }

}
