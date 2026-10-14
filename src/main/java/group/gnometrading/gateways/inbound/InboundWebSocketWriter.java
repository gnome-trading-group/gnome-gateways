package group.gnometrading.gateways.inbound;

import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.IOException;
import java.nio.ByteBuffer;

public class InboundWebSocketWriter extends InboundSocketWriter {

    // A client frame's longest header: 2 bytes, an 8 byte extended length, and the 4 byte mask.
    public static final int MAX_FRAME_HEADER = 14;

    private final WebSocketClient socketClient;

    public InboundWebSocketWriter(WebSocketClient socketClient) {
        super();
        this.socketClient = socketClient;
    }

    @Override
    protected final boolean isOpen() {
        return this.socketClient.isConnected();
    }

    public final void writePong() {
        enqueue(true, Opcode.PONG, null);
    }

    public final void writePing() {
        enqueue(true, Opcode.PING, null);
    }

    public final void writeText(final ByteBuffer payload, boolean isControlMessage) {
        enqueue(isControlMessage, Opcode.TEXT, payload);
    }

    /**
     * Frames a message into a claimed slot and always commits the slot: the writer sends slots in order and stops at
     * the first not committed, so one left claimed by a failed frame would hold back every message after it, for
     * good. A slot whose frame failed is committed empty and skipped.
     */
    private void enqueue(final boolean control, final Opcode opcode, final ByteBuffer payload) {
        if (payload != null && payload.remaining() + MAX_FRAME_HEADER > slotSize()) {
            throw new IllegalArgumentException("A " + payload.remaining() + " byte message can't be framed in a "
                    + slotSize() + " byte write slot");
        }
        final int sequence = control ? this.claimControlWriteBuffer() : this.claimWriteBuffer();
        final ByteBuffer buffer = control ? this.getControlWriteBuffer(sequence) : this.getWriteBuffer(sequence);
        boolean framed = false;
        try {
            if (opcode == Opcode.PING) {
                this.socketClient.wrapPingMessage(buffer);
            } else if (opcode == Opcode.PONG) {
                this.socketClient.wrapPongMessage(buffer);
            } else {
                this.socketClient.wrapMessage(buffer, opcode, payload);
            }
            framed = true;
        } finally {
            if (!framed) {
                buffer.clear();
            }
            if (control) {
                this.publishControlWriteBuffer(sequence);
            } else {
                this.publishWriteBuffer(sequence);
            }
        }
    }

    @Override
    protected final void write(ByteBuffer buffer) throws IOException {
        this.socketClient.writeBuffer(buffer);
    }
}
