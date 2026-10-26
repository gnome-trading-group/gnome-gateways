package group.gnometrading.gateways.feed.transport;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sends on a websocket: text frames, pings and pongs. Its {@link #json} encoder builds a protocol's commands and is used
 * only by the connection's supervisor; any thread may queue a frame.
 */
public final class WebSocketWriter extends FrameWriter {

    // A client frame's longest header: 2 bytes, an 8 byte extended length, and the 4 byte mask.
    private static final int MAX_FRAME_HEADER = 14;

    private final WebSocketClient client;
    private final JsonEncoder json = new JsonEncoder();
    // No bigger than a slot holds once framed, so a command too long to send fails as it is built.
    private final ByteBuffer jsonBody;

    public WebSocketWriter(final WebSocketClient client, final int slotSize) {
        super(slotSize);
        this.client = client;
        this.jsonBody = ByteBuffer.allocate(slotSize - MAX_FRAME_HEADER);
    }

    /** Supervisor thread: an empty encoder to build one command in, then {@link #sendJson}. */
    public JsonEncoder json() {
        jsonBody.clear();
        json.wrap(jsonBody);
        return json;
    }

    /** Supervisor thread: sends the command {@link #json} built as one text frame. */
    public void sendJson() {
        sendText(jsonBody.flip(), false);
    }

    public void sendText(final ByteBuffer payload, final boolean isControl) {
        enqueue(isControl, Opcode.TEXT, payload);
    }

    public void sendPing() {
        enqueue(true, Opcode.PING, null);
    }

    public void sendPong() {
        enqueue(true, Opcode.PONG, null);
    }

    private void enqueue(final boolean isControl, final Opcode opcode, final ByteBuffer payload) {
        if (payload != null && payload.remaining() + MAX_FRAME_HEADER > slotSize()) {
            throw new IllegalArgumentException(
                    "A " + payload.remaining() + " byte message can't be framed in a " + slotSize() + " byte slot");
        }
        final int sequence = claim(isControl);
        final ByteBuffer slot = slot(isControl, sequence);
        boolean framed = false;
        try {
            if (opcode == Opcode.PING) {
                client.wrapPingMessage(slot);
            } else if (opcode == Opcode.PONG) {
                client.wrapPongMessage(slot);
            } else {
                client.wrapMessage(slot, opcode, payload);
            }
            framed = true;
        } finally {
            if (!framed) {
                slot.clear();
            }
            commit(isControl, sequence);
        }
    }

    @Override
    protected void send(final ByteBuffer frame) throws IOException {
        client.writeBuffer(frame);
    }

    @Override
    protected boolean isOpen() {
        return client.isConnected();
    }
}
