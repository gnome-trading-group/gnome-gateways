package group.gnometrading.gateways.exchanges.lighter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.lighter.LighterInboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lighter's keep-alive runs both ways: the supervisor pings, and the reader answers the venue's own pings. The two can
 * be sent at the same moment from their two threads, and each must still go out whole.
 */
class LighterKeepAliveTest {

    private static final String PING = "{\"type\":\"ping\"}";
    private static final String PONG = "{\"type\":\"pong\"}";

    private final RecordingClient sent = newRecordingClient();
    private final InboundJsonWebSocketWriter writer = new InboundJsonWebSocketWriter(this.sent, new JsonEncoder());
    private final WebSocketClient socket = mock(WebSocketClient.class);
    private final WebSocketResponse response = mock(WebSocketResponse.class);
    private final LighterInboundReader reader = new LighterInboundReader(
            new NullLogger(),
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence()),
            System::nanoTime,
            this.writer,
            new Listing(
                    1,
                    new Exchange(1, "LIGHTER", "Lighter", "global"),
                    new Security(3, "ETH", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                    "0",
                    "ETH"),
            77,
            this.socket,
            new JsonDecoder());

    LighterKeepAliveTest() throws Exception {
        when(this.response.isSuccess()).thenReturn(true);
        when(this.response.getOpcode()).thenReturn(Opcode.TEXT);
        when(this.response.getBody()).thenAnswer(inv -> ByteBuffer.wrap(PING.getBytes(StandardCharsets.US_ASCII)));
        when(this.socket.read()).thenReturn(this.response);
        this.reader.buffer = false;
        this.reader.pauseControl.release();
    }

    @Test
    void theVenuesPingIsAnsweredWithAPong() throws Exception {
        this.reader.doWork();
        this.writer.doWork();

        assertEquals(List.of(PONG), this.sent.messages);
    }

    @Test
    void theKeepAliveIsAPing() throws Exception {
        keepAlive();
        this.writer.doWork();

        assertEquals(List.of(PING), this.sent.messages);
    }

    @Test
    @Timeout(30)
    void pingsAndPongsSentTogetherFromTheirTwoThreadsEachGoOutWhole() throws Exception {
        final int each = 5_000;
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread drain = new Thread(() -> {
            while (running.get()) {
                this.writer.doWork();
            }
        });
        final AtomicReference<Throwable> supervisorFailure = new AtomicReference<>();
        final Thread supervisor = new Thread(() -> {
            try {
                for (int i = 0; i < each; i++) {
                    sendWhenThereIsRoom(this::keepAliveUnchecked);
                }
            } catch (final Throwable t) {
                supervisorFailure.set(t);
            }
        });
        drain.start();
        supervisor.start();
        for (int i = 0; i < each; i++) {
            sendWhenThereIsRoom(this::readUnchecked);
        }
        supervisor.join();
        running.set(false);
        drain.join();
        assertNull(supervisorFailure.get(), "the supervisor sent every ping");
        // One pass of the writer sends a batch; whatever is still queued goes out on the passes after.
        final long deadline = System.currentTimeMillis() + 5_000;
        while (this.sent.messages.size() < 2 * each && System.currentTimeMillis() < deadline) {
            this.writer.doWork();
        }

        final Map<String, Integer> counts = new ConcurrentHashMap<>();
        this.sent.messages.forEach(m -> counts.merge(m, 1, Integer::sum));
        assertEquals(Map.of(PING, each, PONG, each), counts, "every message whole, none mixed with another");
    }

    /** The writer holds 128 control messages at a time; a sender that finds it full tries again, as a venue's would. */
    private static void sendWhenThereIsRoom(final Runnable send) {
        while (true) {
            try {
                send.run();
                return;
            } catch (final RuntimeException e) {
                final String message = e.getMessage();
                if (message == null || !message.contains("buffer is full")) {
                    throw new AssertionError("a send failed: " + e, e);
                }
                Thread.onSpinWait();
            }
        }
    }

    private void readUnchecked() {
        try {
            this.reader.doWork();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void keepAliveUnchecked() {
        try {
            keepAlive();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** keepAlive is protected; what it throws is rethrown as itself, not wrapped by the reflective call. */
    private void keepAlive() throws Exception {
        final Method keepAlive = this.reader.getClass().getDeclaredMethod("keepAlive");
        keepAlive.setAccessible(true);
        try {
            keepAlive.invoke(this.reader);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static RecordingClient newRecordingClient() {
        try {
            return new RecordingClient();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unmasks each text frame the writer sends and keeps its payload. */
    private static final class RecordingClient extends WebSocketClient {
        final List<String> messages = new CopyOnWriteArrayList<>();

        RecordingClient() throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 16, 1 << 16);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean writeBuffer(final ByteBuffer frame) {
            final int start = frame.position();
            final int length = frame.get(start + 1) & 0x7F;
            final byte[] payload = new byte[length];
            for (int i = 0; i < length; i++) {
                payload[i] = (byte) (frame.get(start + 6 + i) ^ frame.get(start + 2 + (i % 4)));
            }
            this.messages.add(new String(payload, StandardCharsets.US_ASCII));
            frame.position(frame.limit());
            return true;
        }
    }
}
