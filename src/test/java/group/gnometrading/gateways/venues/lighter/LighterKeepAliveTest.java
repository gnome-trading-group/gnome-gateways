package group.gnometrading.gateways.venues.lighter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.gateways.feed.RecordingClient;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lighter's keep-alive goes out from two threads: the reader answers the venue's pings with a pong, the supervisor
 * sends its own ping. Each must reach the socket whole, never mixed with the other.
 */
class LighterKeepAliveTest {

    private static final String PING = "{\"type\":\"ping\"}";
    private static final String PONG = "{\"type\":\"pong\"}";

    private final RecordingClient sent = newRecordingClient();
    private final WebSocketWriter writer = new WebSocketWriter(sent, 1 << 10);
    private final WebSocketChannel channel = new WebSocketChannel(sent, writer);
    private final LighterProtocol protocol = new LighterProtocol(new SubjectTable(), channel);

    private void venuePings() {
        protocol.onFrame(ByteBuffer.wrap(PING.getBytes(StandardCharsets.US_ASCII)), 1);
    }

    @Test
    void theVenuesPingIsAnsweredWithAPong() throws Exception {
        venuePings();
        writer.doWork();

        assertEquals(List.of(PONG), sent.messages);
    }

    @Test
    void theKeepAliveIsAPing() throws Exception {
        protocol.keepAlive(channel);
        writer.doWork();

        assertEquals(List.of(PING), sent.messages);
    }

    @Test
    @Timeout(30)
    void pingsAndPongsSentTogetherFromTheirTwoThreadsEachGoOutWhole() throws Exception {
        final int each = 5_000;
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread drain = new Thread(() -> {
            while (running.get()) {
                writer.doWork();
            }
        });
        final AtomicReference<Throwable> supervisorFailure = new AtomicReference<>();
        final Thread supervisor = new Thread(() -> {
            try {
                for (int i = 0; i < each; i++) {
                    sendWhenThereIsRoom(() -> protocol.keepAlive(channel));
                }
            } catch (final Throwable t) {
                supervisorFailure.set(t);
            }
        });
        drain.start();
        supervisor.start();
        for (int i = 0; i < each; i++) {
            sendWhenThereIsRoom(this::venuePings);
        }
        supervisor.join();
        running.set(false);
        drain.join();
        assertNull(supervisorFailure.get(), "the supervisor sent every ping");
        // One pass of the writer sends a batch; whatever is still queued goes out on the passes after.
        final long deadline = System.currentTimeMillis() + 5_000;
        while (sent.messages.size() < 2 * each && System.currentTimeMillis() < deadline) {
            writer.doWork();
        }

        final Map<String, Integer> counts = new ConcurrentHashMap<>();
        sent.messages.forEach(m -> counts.merge(m, 1, Integer::sum));
        assertEquals(Map.of(PING, each, PONG, each), counts, "every message whole, none mixed with another");
    }

    @FunctionalInterface
    private interface Send {
        void run() throws Exception;
    }

    /** A sender that finds the queue full tries again, as the venue's would. */
    private static void sendWhenThereIsRoom(final Send send) {
        while (true) {
            try {
                send.run();
                return;
            } catch (final IllegalStateException e) {
                if (e.getMessage() == null || !e.getMessage().contains("queue is full")) {
                    throw new AssertionError("a send failed: " + e, e);
                }
                Thread.onSpinWait();
            } catch (final Exception e) {
                throw new AssertionError("a send failed: " + e, e);
            }
        }
    }

    private static RecordingClient newRecordingClient() {
        try {
            return new RecordingClient();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
