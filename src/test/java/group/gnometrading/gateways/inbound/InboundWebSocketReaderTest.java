package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import group.gnometrading.gateways.inbound.mbp.Mbp10SchemaFactory;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
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

class InboundWebSocketReaderTest {

    private static final Listing LISTING = new Listing(
            1, new Exchange(2, "test", "global", SchemaType.MBP_10), new Security(3, "TEST", 3), "test-id", "TEST");

    private WebSocketClient client;
    private WebSocketResponse response;
    private SequencedRingBuffer<Mbp10Schema> outputBuffer;
    private InboundWebSocketWriter socketWriter;
    private TestWebSocketReader reader;

    @BeforeEach
    void setUp() throws IOException {
        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(client.read()).thenReturn(response);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);

        outputBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        outputBuffer.start();

        socketWriter = new InboundWebSocketWriter(client);
        reader = new TestWebSocketReader(outputBuffer, socketWriter, client);
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        outputBuffer.shutdown();
    }

    // ========== readSocket dispatch ==========

    @Test
    void readSocket_TextOpcode_CallsHandleGatewayMessage() throws Exception {
        final ByteBuffer body = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
        when(response.getOpcode()).thenReturn(Opcode.TEXT);
        when(response.getBody()).thenReturn(body);

        reader.doWork();

        assertEquals(1, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_BinaryOpcode_CallsHandleGatewayMessage() throws Exception {
        final ByteBuffer body = ByteBuffer.wrap(new byte[] {1, 2, 3});
        when(response.getOpcode()).thenReturn(Opcode.BINARY);
        when(response.getBody()).thenReturn(body);

        reader.doWork();

        assertEquals(1, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_NotSuccess_DoesNotCallHandleGatewayMessage() throws Exception {
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
    void readSocket_PingOpcode_CallsWrapPongMessage_DoesNotCallHandleGatewayMessage() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PING);

        reader.doWork();

        verify(client).wrapPongMessage(any(ByteBuffer.class));
        assertEquals(0, reader.handleMessageCallCount);
    }

    @Test
    void readSocket_PingOpcode_UpdatesRecvTimestamp() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PING);

        reader.doWork();

        assertTrue(reader.recvTimestamp > 0);
    }

    @Test
    void readSocket_PongOpcode_DoesNotCallHandleGatewayMessage() throws Exception {
        when(response.getOpcode()).thenReturn(Opcode.PONG);

        reader.doWork();

        assertEquals(0, reader.handleMessageCallCount);
    }

    // ========== attachSocket ==========

    @Test
    void attachSocket_CallsConnectAndConfigures() throws Exception {
        reader.testAttachSocket();

        verify(client).connect();
        verify(client).configureBlocking(true);
        verify(client).setTcpNoDelay(true);
        verify(client).setKeepAlive(true);
    }

    @Test
    void attachSocket_ConfiguresBlockingTrue_NotFalse() throws Exception {
        reader.testAttachSocket();

        verify(client).configureBlocking(true);
        verify(client, never()).configureBlocking(false);
    }

    @Test
    void attachSocket_CallsBeforeConnectThenSubscribe() throws Exception {
        reader.testAttachSocket();

        assertTrue(reader.beforeConnectCalled);
        assertTrue(reader.subscribeCalled);
        assertTrue(reader.beforeConnectCalledFirst);
    }

    // ========== disconnectSocket ==========

    @Test
    void disconnectSocket_CallsClose() throws Exception {
        reader.testDisconnectSocket();

        verify(client).close();
    }

    // ========== beforeConnect ==========

    @Test
    void beforeConnect_DefaultIsNoOp() {
        assertDoesNotThrow(() -> reader.testSuperBeforeConnect());
    }

    // ========== Test subclass ==========

    static class TestWebSocketReader extends InboundWebSocketReader<Mbp10Schema> implements Mbp10SchemaFactory {

        int handleMessageCallCount = 0;
        boolean subscribeCalled = false;
        boolean beforeConnectCalled = false;
        boolean beforeConnectCalledFirst = false;

        TestWebSocketReader(
                SequencedRingBuffer<Mbp10Schema> outputBuffer,
                InboundWebSocketWriter socketWriter,
                WebSocketClient socketClient) {
            super(new NullLogger(), outputBuffer, System::nanoTime, socketWriter, LISTING, socketClient);
        }

        @Override
        protected void handleGatewayMessage(final ByteBuffer buffer) {
            buffer.position(buffer.limit());
            handleMessageCallCount++;
        }

        @Override
        protected void subscribe() throws IOException {
            beforeConnectCalledFirst = beforeConnectCalled;
            subscribeCalled = true;
        }

        @Override
        public Book<Mbp10Schema> fetchSnapshot() {
            return null;
        }

        @Override
        public void keepAlive() throws IOException {}

        @Override
        protected void beforeConnect() throws IOException {
            beforeConnectCalled = true;
            super.beforeConnect();
        }

        void testAttachSocket() throws IOException {
            attachSocket();
        }

        void testDisconnectSocket() throws Exception {
            disconnectSocket();
        }

        void testSuperBeforeConnect() throws IOException {
            super.beforeConnect();
        }
    }
}
