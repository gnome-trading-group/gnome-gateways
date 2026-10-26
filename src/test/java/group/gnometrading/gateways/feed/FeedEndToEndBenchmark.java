package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketTransport;
import group.gnometrading.gateways.venues.kalshi.KalshiProtocol;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Frame-to-consumer latency of a Kalshi market read through a {@link FeedConnection}, with the reader and a consumer
 * spinning on threads of their own as in a low-latency session: from the reader stamping a frame received to the
 * consumer seeing its record. Deltas arrive one every 20 microseconds, so each is measured alone, not queued. Compare
 * with the same harness run against the reader it replaced. Run on demand: {@code mvn test-compile
 * surefire:test@benchmark -Dtest=group.gnometrading.gateways.feed.FeedEndToEndBenchmark -DenableAssertions=false}.
 */
@Tag("benchmark")
class FeedEndToEndBenchmark {

    private static final int WARM_UP = 200_000;
    private static final int MEASURED = 200_000;
    private static final long INTERVAL_NANOS = 20_000;

    private static String cents(final int tenths) {
        return String.format(Locale.ROOT, "%.3f", tenths / 1000.0);
    }

    /** A 40-level snapshot, then deltas cycling over its levels, each level's net change zero. */
    static ByteBuffer[] frames() {
        final ByteBuffer[] frames = new ByteBuffer[1 + WARM_UP + MEASURED];
        final StringBuilder side = new StringBuilder();
        for (int level = 0; level < 40; level++) {
            side.append(level == 0 ? "" : ",")
                    .append("[\"")
                    .append(cents(100 + level * 10))
                    .append("0\",\"25.00\"]");
        }
        frames[0] = ascii("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"T\","
                + "\"yes_dollars_fp\":[" + side + "],\"no_dollars_fp\":[" + side + "]}}");
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            frames[i + 1] =
                    ascii("{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + (i + 2) + ",\"msg\":{\"market_ticker\":"
                            + "\"T\",\"price_dollars\":\"" + cents(100 + (i % 40) * 10) + "\",\"delta_fp\":\""
                            + ((i / 80) % 2 == 0 ? "1.00" : "-1.00") + "\",\"side\":\"" + (i % 2 == 0 ? "yes" : "no")
                            + "\",\"ts_ms\":1700000000000}}");
        }
        return frames;
    }

    private static ByteBuffer ascii(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Hands out the frames in order: the warm-up as fast as asked, then one every INTERVAL_NANOS. */
    static final class PacedClient extends WebSocketClient {
        private final ByteBuffer[] frames;
        private final Response response = new Response();
        private int served;
        private long nextDue;

        PacedClient(final ByteBuffer[] frames) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 20, 1 << 16);
            this.frames = frames;
        }

        @Override
        public WebSocketResponse read() {
            if (served == frames.length) {
                return response.update(true, Opcode.TEXT, null, false);
            }
            if (served > WARM_UP) {
                final long now = System.nanoTime();
                if (now < nextDue) {
                    return response.update(true, Opcode.TEXT, null, false);
                }
                nextDue = now + INTERVAL_NANOS;
            }
            final ByteBuffer frame = frames[served++];
            frame.position(0);
            return response.update(true, Opcode.TEXT, frame, false);
        }
    }

    static final class Response extends WebSocketResponse {}

    /**
     * The reader spins on one thread and a consumer on another, as a low-latency session's strategy does; each record's
     * latency is from the reader stamping the frame received to the consumer seeing it.
     */
    static String run(
            final String name,
            final ReaderStep reader,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final EpochNanoClock clock)
            throws Exception {
        final long[] latencies = new long[MEASURED];
        final int[] seen = {0};
        final Mbp10Schema view = new Mbp10Schema();
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            view.buffer.putBytes(0, buffer, 0, length);
            view.wrap(view.buffer);
            final long latency = clock.nanoTime() - view.decoder.timestampRecv();
            final int index = seen[0]++ - (WARM_UP - 1);
            if (index >= 0 && index < MEASURED) {
                latencies[index] = latency;
            }
        });
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread readerThread = new Thread(() -> {
            try {
                while (running.get()) {
                    reader.step();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        readerThread.start();
        final long deadline = System.nanoTime() + 120_000_000_000L;
        while (seen[0] < WARM_UP - 1 + MEASURED && System.nanoTime() < deadline) {
            poller.poll();
        }
        running.set(false);
        readerThread.join();
        final int got = Math.min(MEASURED, Math.max(0, seen[0] - (WARM_UP - 1)));
        final long[] measured = Arrays.copyOf(latencies, got);
        Arrays.sort(measured);
        return String.format(
                Locale.ROOT,
                "%-40s n=%d  p50 %6d ns  p90 %6d ns  p99 %6d ns  p99.9 %7d ns  max %8d ns",
                name,
                got,
                measured[got / 2],
                measured[(int) (got * 0.9)],
                measured[(int) (got * 0.99)],
                measured[(int) (got * 0.999)],
                measured[got - 1]);
    }

    @FunctionalInterface
    interface ReaderStep {
        void step() throws Exception;
    }

    private static String connection(final SequencedRingBuffer.Producers producers) throws Exception {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(
                Mbp10Schema::new, new GlobalSequence(), SequencedRingBuffer.DEFAULT_SIZE, producers);
        final EpochNanoClock clock = System::nanoTime;
        final WebSocketTransport transport = new WebSocketTransport(new PacedClient(frames()), 1 << 14);
        final SubjectTable table = new SubjectTable();
        final FeedConnection<WebSocketChannel> connection = new FeedConnection<>(
                "bench",
                new NullLogger(),
                transport,
                new KalshiProtocol(
                        new NullLogger(),
                        table,
                        "key",
                        KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate(),
                        clock),
                table,
                new GatewayConfig.Builder().build(),
                () -> 0,
                clock,
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                FeedListener.NONE);
        connection.add(new Subject("T", 1000, new StreamOutput(77, Projection.IDENTITY, ring)));
        connection.assumeConnected();
        connection.control(0);
        connection.pauseControl().release();
        return run("FeedConnection + KalshiProtocol (" + producers + ")", connection.reader()::doWork, ring, clock);
    }

    @Test
    void report() throws Exception {
        System.out.println("#### " + connection(SequencedRingBuffer.Producers.TAKING_TURNS));
        System.out.println("#### " + connection(SequencedRingBuffer.Producers.ONE_THREAD));
    }
}
