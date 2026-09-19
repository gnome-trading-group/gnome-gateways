package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundWebSocketReaderTest {

    private static final Listing LISTING = new Listing(
            1, new Exchange(2, "test", "global", SchemaType.MBP_10), new Security(3, "TEST", 3), "test-id", "TEST");

    private WebSocketClient client;
    private WebSocketResponse response;
    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private TestOutboundWebSocketReader reader;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(client.read()).thenReturn(response);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);

        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        execReportBuffer.start();

        final ManyToOneRingBuffer<OrderContext> contextQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> rejectQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> completionQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        reader = new TestOutboundWebSocketReader(execReportBuffer, contextQueue, rejectQueue, completionQueue, client);
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    // ========== readSocket dispatch ==========

    @Test
    void readSocket_TextOpcode_ReturnsBody() throws Exception {
        final ByteBuffer body = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
        when(response.getOpcode()).thenReturn(Opcode.TEXT);
        when(response.getBody()).thenReturn(body);

        reader.doWork();

        assertEquals(1, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_BinaryOpcode_ReturnsBody() throws Exception {
        final ByteBuffer body = ByteBuffer.wrap(new byte[] {1, 2, 3});
        when(response.getOpcode()).thenReturn(Opcode.BINARY);
        when(response.getBody()).thenReturn(body);

        reader.doWork();

        assertEquals(1, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_NotSuccess_DoesNotCallHandleMessage() throws Exception {
        when(response.isSuccess()).thenReturn(false);

        reader.doWork();

        assertEquals(0, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_Closed_ThrowsRuntimeException() {
        when(response.isClosed()).thenReturn(true);

        assertThrows(RuntimeException.class, () -> reader.doWork());
    }

    @Test
    void readSocket_PingOpcode_SendsPongAndDoesNotCallHandleMessage() throws Exception {
        final ByteBuffer pingBody = ByteBuffer.wrap(new byte[] {1, 2});
        when(response.getOpcode()).thenReturn(Opcode.PING);
        when(response.getBody()).thenReturn(pingBody);

        reader.doWork();

        verify(client).writeMessage(eq(Opcode.PONG), any(ByteBuffer.class));
        assertEquals(0, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_PingWithNullBody_SendsEmptyPong() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PING);
        when(response.getBody()).thenReturn(null);

        reader.doWork();

        verify(client).writeMessage(eq(Opcode.PONG), any(ByteBuffer.class));
    }

    @Test
    void readSocket_PingWithEmptyBody_SendsEmptyPong() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PING);
        when(response.getBody()).thenReturn(ByteBuffer.allocate(0));

        reader.doWork();

        verify(client).writeMessage(eq(Opcode.PONG), any(ByteBuffer.class));
    }

    @Test
    void readSocket_PingOpcode_UpdatesRecvTimestamp() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PING);
        when(response.getBody()).thenReturn(ByteBuffer.allocate(0));

        reader.doWork();

        assertTrue(reader.recvTimestamp > 0);
    }

    @Test
    void readSocket_OtherOpcode_DoesNotCallHandleMessage() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PONG);

        reader.doWork();

        assertEquals(0, reader.handleMessageCallCount);
    }

    // ========== attachSocket ==========

    @Test
    void attachSocket_ConnectsAndConfiguresSocket() throws Exception {
        reader.testAttachSocket();

        verify(client).connect();
        verify(client).configureBlocking(false);
        verify(client).setTcpNoDelay(true);
        verify(client).setKeepAlive(true);
    }

    @Test
    void attachSocket_CallsSubscribe() throws Exception {
        reader.testAttachSocket();
        assertTrue(reader.subscribeCalled);
    }

    // ========== disconnectSocket ==========

    @Test
    void disconnectSocket_ClosesClient() throws Exception {
        reader.testDisconnectSocket();
        verify(client).close();
    }

    // ========== skipWhitespace ==========

    @Test
    void skipWhitespace_AdvancesPastLeadingSpaces() {
        final ByteBuffer buf = ByteBuffer.wrap("  hello".getBytes(StandardCharsets.UTF_8));
        TestOutboundWebSocketReader.testSkipWhitespace(buf);
        assertEquals('h', (char) buf.get(buf.position()));
    }

    @Test
    void skipWhitespace_AdvancesPastMixedWhitespace() {
        final ByteBuffer buf = ByteBuffer.wrap("\r\n\t data".getBytes(StandardCharsets.UTF_8));
        TestOutboundWebSocketReader.testSkipWhitespace(buf);
        assertEquals('d', (char) buf.get(buf.position()));
    }

    @Test
    void skipWhitespace_NoWhitespace_PositionUnchanged() {
        final ByteBuffer buf = ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8));
        TestOutboundWebSocketReader.testSkipWhitespace(buf);
        assertEquals('d', (char) buf.get(buf.position()));
    }

    @Test
    void skipWhitespace_AllWhitespace_BufferEmpty() {
        final ByteBuffer buf = ByteBuffer.wrap("   ".getBytes(StandardCharsets.UTF_8));
        TestOutboundWebSocketReader.testSkipWhitespace(buf);
        assertFalse(buf.hasRemaining());
    }

    @Test
    void skipWhitespace_EmptyBuffer_NoOp() {
        final ByteBuffer buf = ByteBuffer.allocate(0);
        assertDoesNotThrow(() -> TestOutboundWebSocketReader.testSkipWhitespace(buf));
    }

    // ========== Test subclass ==========

    static class TestOutboundWebSocketReader extends OutboundWebSocketReader {

        int handleMessageCallCount = 0;
        boolean subscribeCalled = false;

        TestOutboundWebSocketReader(
                SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
                ManyToOneRingBuffer<OrderContext> contextQueue,
                ManyToOneRingBuffer<OrderContext> rejectQueue,
                ManyToOneRingBuffer<OrderContext> completionQueue,
                WebSocketClient socketClient) {
            super(
                    new NullLogger(),
                    execReportBuffer,
                    contextQueue,
                    rejectQueue,
                    completionQueue,
                    System::nanoTime,
                    LISTING,
                    socketClient);
        }

        @Override
        protected void handleGatewayMessage(final ByteBuffer buffer) throws Exception {
            handleMessageCallCount++;
        }

        @Override
        protected void subscribe() throws IOException {
            subscribeCalled = true;
        }

        @Override
        public void keepAlive() throws IOException {}

        void testAttachSocket() throws IOException {
            attachSocket();
        }

        void testDisconnectSocket() throws Exception {
            disconnectSocket();
        }

        static void testSkipWhitespace(final ByteBuffer buffer) {
            ByteBufferUtils.skipWhitespace(buffer);
        }
    }
}
