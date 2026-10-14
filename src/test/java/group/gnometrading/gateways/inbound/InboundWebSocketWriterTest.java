package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Outgoing messages are sent in order from claimed slots; one that fails to frame must not hold back the rest. */
class InboundWebSocketWriterTest {

    private final FramingClient client = newClient();
    private final InboundWebSocketWriter writer = new InboundWebSocketWriter(this.client);

    @Test
    void aMessageTooLongForASlotIsRefusedAndClaimsNothing() {
        final int tooLong = this.writer.slotSize() - InboundWebSocketWriter.MAX_FRAME_HEADER + 1;

        assertThrows(IllegalArgumentException.class, () -> this.writer.writeText(text(tooLong), false));
        this.writer.writeText(text(10), false);
        this.writer.doWork();

        assertEquals(List.of(Opcode.TEXT.code), this.client.sentOpcodes);
    }

    @Test
    void theLongestMessageThatFitsIsSent() {
        this.writer.writeText(text(this.writer.slotSize() - InboundWebSocketWriter.MAX_FRAME_HEADER), false);
        this.writer.doWork();

        assertEquals(1, this.client.sentOpcodes.size());
    }

    @Test
    void aMessageThatFailsToFrameDoesNotHoldBackTheNext() {
        this.client.failNextFrame = true;
        assertThrows(IllegalStateException.class, () -> this.writer.writeText(text(10), false));

        this.writer.writeText(text(20), false);
        this.writer.doWork();

        assertEquals(List.of(Opcode.TEXT.code), this.client.sentOpcodes);
        assertEquals(List.of(20), this.client.sentPayloadLengths);
    }

    @Test
    void pingsAndPongsStillGoOutAfterAControlMessageFailsToFrame() {
        this.client.failNextFrame = true;
        assertThrows(IllegalStateException.class, () -> this.writer.writeText(text(10), true));

        this.writer.writePing();
        this.writer.writePong();
        this.writer.doWork();

        assertEquals(List.of(Opcode.PING.code, Opcode.PONG.code), this.client.sentOpcodes);
    }

    /** Failed slots are released, not leaked: many more failures than there are slots still leave room. */
    @Test
    void failuresDoNotUseUpTheSlots() {
        for (int i = 0; i < 1_000; i++) {
            this.client.failNextFrame = true;
            assertThrows(IllegalStateException.class, () -> this.writer.writeText(text(10), false));
            this.writer.doWork();
        }

        this.writer.writeText(text(10), false);
        this.writer.doWork();

        assertEquals(List.of(Opcode.TEXT.code), this.client.sentOpcodes);
    }

    private static ByteBuffer text(final int length) {
        return ByteBuffer.wrap("x".repeat(length).getBytes(StandardCharsets.US_ASCII));
    }

    private static FramingClient newClient() {
        try {
            return new FramingClient();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Frames with the real encoder, can fail one frame on request, and records what reached the socket. */
    private static final class FramingClient extends WebSocketClient {
        boolean failNextFrame;
        final List<Integer> sentOpcodes = new ArrayList<>();
        final List<Integer> sentPayloadLengths = new ArrayList<>();

        FramingClient() throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 16, 1 << 16);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void wrapMessage(final ByteBuffer output, final Opcode opcode, final ByteBuffer payload) {
            if (this.failNextFrame) {
                this.failNextFrame = false;
                // Partway through framing, as an encoder that runs out of room would be.
                output.put((byte) 0x81);
                throw new IllegalStateException("framing failed");
            }
            super.wrapMessage(output, opcode, payload);
        }

        @Override
        public boolean writeBuffer(final ByteBuffer buffer) {
            this.sentOpcodes.add(buffer.get(buffer.position()) & 0x0F);
            // Masked client frames: the length byte's low 7 bits, for payloads under 126 bytes.
            final int length = buffer.get(buffer.position() + 1) & 0x7F;
            if (length < 126) {
                this.sentPayloadLengths.add(length);
            }
            buffer.position(buffer.limit());
            return true;
        }
    }
}
