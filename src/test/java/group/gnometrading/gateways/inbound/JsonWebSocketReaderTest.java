package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.inbound.mbp.Mbp10SchemaFactory;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JsonWebSocketReaderTest {

    private static final Listing LISTING = new Listing(
            1, new Exchange(2, "test", "global", SchemaType.MBP_10), new Security(3, "TEST", 3), "test-id", "TEST");

    private SequencedRingBuffer<Mbp10Schema> outputBuffer;
    private TestJsonWebSocketReader reader;

    @BeforeEach
    void setUp() {
        outputBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        outputBuffer.start();

        reader = new TestJsonWebSocketReader(outputBuffer);
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        outputBuffer.shutdown();
    }

    // ========== handleGatewayMessage — empty/whitespace ==========

    @Test
    void handleGatewayMessage_EmptyBuffer_NoProcessing() {
        reader.testHandleGatewayMessage(ByteBuffer.allocate(0));

        assertEquals(0, reader.handleJsonMessageCallCount);
        assertEquals(0, reader.handleNonJsonCallCount);
    }

    @Test
    void handleGatewayMessage_WhitespaceOnlyBuffer_NoProcessing() {
        final ByteBuffer buf = ByteBuffer.wrap("   \n\r\t".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals(0, reader.handleJsonMessageCallCount);
        assertEquals(0, reader.handleNonJsonCallCount);
    }

    // ========== handleGatewayMessage — non-JSON dispatch ==========

    @Test
    void handleGatewayMessage_NonJsonMessageReturnsTrue_JsonHandlerNotCalled() {
        reader.shouldReturnTrueFromNonJson = true;
        final ByteBuffer buf = ByteBuffer.wrap("PONG".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals(1, reader.handleNonJsonCallCount);
        assertEquals(0, reader.handleJsonMessageCallCount);
    }

    @Test
    void handleGatewayMessage_NonJsonMessageReturnsFalse_JsonHandlerCalled() {
        reader.shouldReturnTrueFromNonJson = false;
        final ByteBuffer buf = ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals(1, reader.handleNonJsonCallCount);
        assertEquals(1, reader.handleJsonMessageCallCount);
    }

    // ========== handleGatewayMessage — JSON path ==========

    @Test
    void handleGatewayMessage_JsonMessage_HandlerReceivesNonNullNode() {
        final ByteBuffer buf = ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals(1, reader.handleJsonMessageCallCount);
        assertNotNull(reader.lastNode);
    }

    @Test
    void handleGatewayMessage_JsonMessage_NodeClosedAfterHandler() {
        final ByteBuffer buf = ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> reader.testHandleGatewayMessage(buf));
        assertEquals(1, reader.handleJsonMessageCallCount);
    }

    // ========== handleGatewayMessage — whitespace stripping ==========

    @Test
    void handleGatewayMessage_LeadingWhitespaceStripped_NonJsonSeesFirstNonWhitespaceByte() {
        reader.shouldReturnTrueFromNonJson = true;
        final ByteBuffer buf = ByteBuffer.wrap("  {}".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals((byte) '{', buf.get(reader.bufferPositionOnNonJsonCall));
    }

    @Test
    void handleGatewayMessage_MixedLeadingWhitespaceStripped() {
        reader.shouldReturnTrueFromNonJson = true;
        final ByteBuffer buf = ByteBuffer.wrap("\r\n\t {}".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals((byte) '{', buf.get(reader.bufferPositionOnNonJsonCall));
    }

    @Test
    void handleGatewayMessage_NoLeadingWhitespace_PositionUnchanged() {
        reader.shouldReturnTrueFromNonJson = true;
        final ByteBuffer buf = ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8));

        reader.testHandleGatewayMessage(buf);

        assertEquals(0, reader.bufferPositionOnNonJsonCall);
    }

    // ========== handleNonJsonMessage default ==========

    @Test
    void handleNonJsonMessage_DefaultReturnsFalse() {
        final ByteBuffer buf = ByteBuffer.wrap("anything".getBytes(StandardCharsets.UTF_8));

        assertFalse(reader.callDefaultHandleNonJsonMessage(buf));
    }

    @Test
    void handleNonJsonMessage_DefaultDoesNotAdvanceBuffer() {
        final ByteBuffer buf = ByteBuffer.wrap("anything".getBytes(StandardCharsets.UTF_8));
        final int before = buf.position();

        reader.callDefaultHandleNonJsonMessage(buf);

        assertEquals(before, buf.position());
    }

    // ========== Test subclass ==========

    static class TestJsonWebSocketReader extends JsonWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

        int handleJsonMessageCallCount = 0;
        JsonDecoder.JsonNode lastNode = null;
        boolean shouldReturnTrueFromNonJson = false;
        int handleNonJsonCallCount = 0;
        int bufferPositionOnNonJsonCall = -1;

        TestJsonWebSocketReader(SequencedRingBuffer<Mbp10Schema> outputBuffer) {
            super(
                    new NullLogger(),
                    outputBuffer,
                    System::nanoTime,
                    null,
                    LISTING,
                    Mockito.mock(WebSocketClient.class),
                    new JsonDecoder());
        }

        @Override
        protected boolean handleNonJsonMessage(final ByteBuffer buffer) {
            handleNonJsonCallCount++;
            bufferPositionOnNonJsonCall = buffer.position();
            return shouldReturnTrueFromNonJson;
        }

        @Override
        protected void handleJsonMessage(final JsonDecoder.JsonNode node) {
            handleJsonMessageCallCount++;
            lastNode = node;
        }

        @Override
        protected void subscribe() throws IOException {}

        @Override
        public Book<Mbp10Schema> fetchSnapshot() {
            return null;
        }

        @Override
        public void keepAlive() throws IOException {}

        void testHandleGatewayMessage(final ByteBuffer buffer) {
            handleGatewayMessage(buffer);
        }

        boolean callDefaultHandleNonJsonMessage(final ByteBuffer buffer) {
            return super.handleNonJsonMessage(buffer);
        }
    }
}
