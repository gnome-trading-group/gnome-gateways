package group.gnometrading.gateways.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebSocketTransportTest {

    @Test
    void aCloseFrameNamesItsCodeAndReason() throws Exception {
        assertEquals(
                "The venue closed the websocket (code 1001: going away)", closeMessage(closeFrame(1001, "going away")));
    }

    @Test
    void aCloseFrameWithoutAReasonNamesItsCode() throws Exception {
        assertEquals("The venue closed the websocket (code 1000)", closeMessage(closeFrame(1000, "")));
    }

    @Test
    void aCloseFrameWithNoPayloadSaysSo() throws Exception {
        assertEquals(
                "The venue closed the websocket with no status code",
                closeMessage(response(true, Opcode.CLOSING, ByteBuffer.allocate(0))));
    }

    @Test
    void aDroppedConnectionSaysThereWasNoCloseFrame() throws Exception {
        assertEquals(
                "The venue dropped the connection without a close frame", closeMessage(response(false, null, null)));
    }

    @Test
    void aCloseFrameIsAVenueCloseCarryingItsCodeAndReason() throws Exception {
        final VenueClosedException closed =
                assertInstanceOf(VenueClosedException.class, closeError(closeFrame(1001, "max connection age")));
        assertEquals(1001, closed.code());
        assertEquals("max connection age", closed.reason());
    }

    @Test
    void aDroppedConnectionIsNotAVenueClose() throws Exception {
        assertFalse(closeError(response(false, null, null)) instanceof VenueClosedException);
    }

    private static String closeMessage(final WebSocketResponse closed) throws IOException {
        return closeError(closed).getMessage();
    }

    private static EOFException closeError(final WebSocketResponse closed) throws IOException {
        final WebSocketTransport transport = new WebSocketTransport(new ClosingClient(closed), 1 << 10);
        return assertThrows(EOFException.class, transport::read);
    }

    private static WebSocketResponse closeFrame(final int code, final String reason) {
        final byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer payload = ByteBuffer.allocate(2 + text.length)
                .putShort((short) code)
                .put(text)
                .flip();
        return response(true, Opcode.CLOSING, payload);
    }

    private static WebSocketResponse response(final boolean success, final Opcode opcode, final ByteBuffer body) {
        return new WebSocketResponse() {}.update(success, opcode, body, true);
    }

    /** A websocket that is never opened and whose next read is the venue hanging up. */
    private static final class ClosingClient extends WebSocketClient {

        private final WebSocketResponse closed;

        ClosingClient(final WebSocketResponse closed) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 10, 1 << 10);
            this.closed = closed;
        }

        @Override
        public WebSocketResponse read() {
            return closed;
        }
    }
}
