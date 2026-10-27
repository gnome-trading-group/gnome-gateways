package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.probe.BookProbeClient;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.gateways.feed.transport.FrameWriter;
import group.gnometrading.gateways.feed.transport.Transport;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketTransport;
import group.gnometrading.gateways.venues.hyperliquid.HyperliquidProtocol;
import group.gnometrading.gateways.venues.kalshi.KalshiProtocol;
import group.gnometrading.gateways.venues.lighter.LighterProtocol;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsProtocol;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reports how long a connection's reader takes per message for each venue, with realistic book depth and many markets
 * on one connection: frame to decoded, book updated and published to every stream. Also the supervisor's pass, a
 * session's 1-second feeds tick and the prober's pass. Prints numbers rather than asserting them, since timings vary by
 * machine; also reports what each phase allocated. Run on demand: {@code mvn test-compile surefire:test@benchmark}.
 */
@Tag("benchmark")
class FeedLatencyBenchmark {

    private static final int WARM_UP = 200_000;
    private static final int MEASURED = 200_000;
    private static final int MESSAGES = WARM_UP + MEASURED;
    private static final int MARKETS = 250;
    private static final StringBuilder REPORT = new StringBuilder();

    private static ByteBuffer ascii(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String cents(final int tenths) {
        return String.format(Locale.ROOT, "%.3f", tenths / 1000.0);
    }

    @Test
    void report() throws Exception {
        kalshi();
        hyperliquid();
        lighter();
        polymarketIntl();
        polymarketUs();
        supervisorPass();
        feedsTick();
        proberPass();
        ringPublish(SequencedRingBuffer.Producers.ONE_THREAD);
        ringPublish(SequencedRingBuffer.Producers.TAKING_TURNS);
        System.out.println(REPORT);
    }

    /** 250 markets on one connection, each with 40 levels a side, and YES and NO streams; deltas spread over them. */
    private static void kalshi() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES + 1 + MARKETS];
        int n = 0;
        messages[n++] = ascii("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"orderbook_delta\",\"sid\":1}}");
        for (int m = 0; m < MARKETS; m++) {
            final StringBuilder yes = new StringBuilder();
            final StringBuilder no = new StringBuilder();
            for (int level = 0; level < 40; level++) {
                yes.append(level == 0 ? "" : ",")
                        .append("[\"")
                        .append(cents(100 + level * 10))
                        .append("0\",\"25.00\"]");
                no.append(level == 0 ? "" : ",")
                        .append("[\"")
                        .append(cents(100 + level * 10))
                        .append("0\",\"25.00\"]");
            }
            messages[n] =
                    ascii("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":" + n + ",\"msg\":{\"market_ticker\":\"T"
                            + m + "\",\"yes_dollars_fp\":[" + yes + "],\"no_dollars_fp\":[" + no + "]}}");
            n++;
        }
        for (int i = 0; n < messages.length; i++, n++) {
            messages[n] = ascii("{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + n
                    + ",\"msg\":{\"market_ticker\":\"T"
                    + (i % MARKETS) + "\",\"price_dollars\":\"" + cents(100 + (i % 40) * 10) + "\",\"delta_fp\":\""
                    + ((i / 3_000) % 2 == 0 ? "1.00" : "-1.00") + "\",\"side\":\"" + (i % 3 == 0 ? "no" : "yes")
                    + "\",\"ts_ms\":1700000000000}}");
        }
        final PrivateKey key =
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        measure(
                "Kalshi delta (250 markets, YES+NO streams)",
                messages,
                1 + MARKETS,
                m -> "T" + m,
                MARKETS,
                table -> new KalshiProtocol(new NullLogger(), table, "key", key, () -> 0),
                Projection.IDENTITY,
                Projection.COMPLEMENT);
    }

    /** 50 coins on one connection; every message is a coin's whole 20-level book. */
    private static void hyperliquid() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        for (int i = 0; i < messages.length; i++) {
            final StringBuilder bids = new StringBuilder();
            final StringBuilder asks = new StringBuilder();
            for (int level = 0; level < 20; level++) {
                bids.append(level == 0 ? "" : ",")
                        .append("{\"px\":\"")
                        .append(2300 - level)
                        .append(".5\",\"sz\":\"")
                        .append(1 + (i + level) % 7)
                        .append(".25\",\"n\":3}");
                asks.append(level == 0 ? "" : ",")
                        .append("{\"px\":\"")
                        .append(2301 + level)
                        .append(".5\",\"sz\":\"")
                        .append(1 + (i + level) % 5)
                        .append(".75\",\"n\":2}");
            }
            messages[i] = ascii("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"C" + (i % 50) + "\",\"time\":"
                    + (1700000000000L + i) + ",\"levels\":[[" + bids + "],[" + asks + "]]}}");
        }
        measure(
                "Hyperliquid whole book (50 coins, 20 levels a side)",
                messages,
                50,
                m -> "C" + m,
                50,
                table -> new HyperliquidProtocol(table, () -> 0),
                Projection.IDENTITY);
    }

    /** 100 markets on one connection: a subscribed book each, then nonce-chained updates of two levels. */
    private static void lighter() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        final long[] nonces = new long[100];
        for (int i = 0; i < messages.length; i++) {
            final int m = i % 100;
            final boolean first = i < 100;
            final StringBuilder asks = new StringBuilder();
            final StringBuilder bids = new StringBuilder();
            final int levels = first ? 50 : 1;
            for (int level = 0; level < levels; level++) {
                asks.append(level == 0 ? "" : ",")
                        .append("{\"price\":\"")
                        .append(4212 + level)
                        .append(".00\",\"size\":\"")
                        .append(first ? "2.0" : (i % 2 == 0 ? "2.0" : "3.0"))
                        .append("\"}");
                bids.append(level == 0 ? "" : ",")
                        .append("{\"price\":\"")
                        .append(4209 - level)
                        .append(".00\",\"size\":\"3.0\"}");
            }
            final long begin = nonces[m];
            nonces[m] = begin + 1;
            messages[i] = ascii("{\"channel\":\"order_book:" + m + "\",\"offset\":" + i + ",\"order_book\":{\"code\":0,"
                    + "\"asks\":[" + asks + "],\"bids\":[" + bids + "],\"offset\":" + i + ",\"nonce\":" + nonces[m]
                    + ",\"begin_nonce\":" + begin + "},\"timestamp\":1761585003001,\"type\":\""
                    + (first ? "subscribed/order_book" : "update/order_book") + "\"}");
        }
        measure("Lighter update (100 markets)", messages, 100, m -> String.valueOf(m), 100, null, Projection.IDENTITY);
    }

    /** 250 tokens on one connection, 30 levels a side; price changes with the best-price check. */
    private static void polymarketIntl() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        final StringBuilder books = new StringBuilder("[");
        for (int m = 0; m < MARKETS; m++) {
            final StringBuilder bids = new StringBuilder();
            final StringBuilder asks = new StringBuilder();
            for (int level = 0; level < 30; level++) {
                bids.append(level == 0 ? "" : ",")
                        .append("{\"price\":\"0.")
                        .append(String.format("%02d", 40 - level))
                        .append("\",\"size\":\"100\"}");
                asks.append(level == 0 ? "" : ",")
                        .append("{\"price\":\"0.")
                        .append(String.format("%02d", 60 + level))
                        .append("\",\"size\":\"100\"}");
            }
            books.append(m == 0 ? "" : ",")
                    .append("{\"market\":\"c\",\"asset_id\":\"tok")
                    .append(m)
                    .append("\",\"timestamp\":\"1782753357000\",\"bids\":[")
                    .append(bids)
                    .append("],\"asks\":[")
                    .append(asks)
                    .append("],\"event_type\":\"book\",\"last_trade_price\":\"0.50\"}");
        }
        messages[0] = ascii(books.append("]").toString());
        for (int i = 1; i < messages.length; i++) {
            messages[i] = ascii("{\"market\":\"c\",\"price_changes\":[{\"asset_id\":\"tok" + (i % MARKETS)
                    + "\",\"price\":\"0.30\",\"size\":\"" + (i % 2 == 0 ? "100" : "50") + "\",\"side\":\"BUY\","
                    + "\"hash\":\"h\",\"best_bid\":\"0.40\",\"best_ask\":\"0.60\"}],\"timestamp\":\""
                    + (1782753357000L + i) + "\",\"event_type\":\"price_change\"}");
        }
        measure(
                "Polymarket Intl price change (250 tokens)",
                messages,
                1,
                m -> "tok" + m,
                MARKETS,
                PolymarketIntlProtocol::new,
                Projection.IDENTITY);
    }

    /** 100 markets on one connection, long and short streams; every message is a market's whole book. */
    private static void polymarketUs() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        for (int i = 0; i < messages.length; i++) {
            final StringBuilder bids = new StringBuilder();
            final StringBuilder offers = new StringBuilder();
            for (int level = 0; level < 10; level++) {
                bids.append(level == 0 ? "" : ",")
                        .append("{\"px\":{\"value\":\"0.")
                        .append(50 - level)
                        .append("\",\"currency\":\"USD\"},\"qty\":\"")
                        .append(10 + (i + level) % 9)
                        .append("\"}");
                offers.append(level == 0 ? "" : ",")
                        .append("{\"px\":{\"value\":\"0.")
                        .append(51 + level)
                        .append("\",\"currency\":\"USD\"},\"qty\":\"")
                        .append(10 + (i + level) % 7)
                        .append("\"}");
            }
            messages[i] = ascii("{\"requestId\":\"m.s" + (i % 100) + "\",\"subscriptionType\":"
                    + "\"SUBSCRIPTION_TYPE_MARKET_DATA\",\"marketData\":{\"marketSlug\":\"s" + (i % 100)
                    + "\",\"bids\":[" + bids + "],\"offers\":[" + offers
                    + "],\"state\":\"MARKET_STATE_OPEN\",\"transactTime\":\"2026-10-26T15:00:00.123Z\"}}");
        }
        final byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate())
                .getBytes()
                .orElseThrow();
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key", PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(seed)));
        measure(
                "Polymarket US whole book (100 markets, long+short)",
                messages,
                100,
                m -> "s" + m,
                100,
                table -> new PolymarketUsProtocol(new NullLogger(), table, signer, () -> 0),
                Projection.IDENTITY,
                Projection.COMPLEMENT);
    }

    /**
     * Times each message through the connection's reader, the stream rings drained between messages outside the
     * timing, and prints percentiles, throughput and what the measured messages allocated.
     */
    private static void measure(
            final String name,
            final ByteBuffer[] messages,
            final int setUp,
            final Function<Integer, String> keys,
            final int markets,
            final Function<SubjectTable, VenueProtocol<WebSocketChannel>> protocols,
            final Projection... projections)
            throws Exception {
        final ReplayClient client = new ReplayClient(messages);
        final WebSocketTransport transport = new WebSocketTransport(client, 1 << 14);
        final SubjectTable table = new SubjectTable();
        final VenueProtocol<WebSocketChannel> made = protocols == null ? null : protocols.apply(table);
        final VenueProtocol<WebSocketChannel> protocol =
                made != null ? made : new LighterProtocol(table, transport.channel());
        final FeedConnection<WebSocketChannel> connection = new FeedConnection<>(
                "bench",
                new NullLogger(),
                transport,
                protocol,
                table,
                new GatewayConfig.Builder().build(),
                () -> 0,
                System::nanoTime,
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                FeedListener.NONE);
        final SequencedPoller[] pollers = new SequencedPoller[projections.length * markets];
        final Subject[] subjects = new Subject[markets];
        for (int m = 0; m < markets; m++) {
            final StreamOutput[] outputs = new StreamOutput[projections.length];
            for (int p = 0; p < projections.length; p++) {
                final SequencedRingBuffer<Mbp10Schema> ring =
                        new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
                pollers[m * projections.length + p] = ring.createPoller((seq, templateId, buffer, length) -> {});
                outputs[p] = new StreamOutput(m * projections.length + p, projections[p], ring);
            }
            subjects[m] = new Subject(keys.apply(m), 1024, outputs);
            connection.add(subjects[m]);
        }
        connection.assumeConnected();
        connection.control(0);
        connection.pauseControl().release();

        for (int i = 0; i < setUp; i++) {
            readOne(connection, pollers);
        }
        for (int i = setUp; i < messages.length - MEASURED; i++) {
            readOne(connection, pollers);
        }
        final long[] nanos = new long[MEASURED];
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final long allocatedBefore = threads.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            final long before = System.nanoTime();
            connection.reader().doWork();
            nanos[i] = System.nanoTime() - before;
            for (SequencedPoller poller : pollers) {
                poller.poll();
            }
        }
        final long elapsed = System.nanoTime() - start;
        final long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
        for (Subject subject : subjects) {
            assertEquals(SubjectState.LIVE, subject.state(), name + ": " + subject.key() + " failed a check");
        }
        row(name, nanos, elapsed, allocated);
    }

    private static void readOne(final FeedConnection<WebSocketChannel> connection, final SequencedPoller[] pollers)
            throws Exception {
        connection.reader().doWork();
        for (SequencedPoller poller : pollers) {
            poller.poll();
        }
    }

    private static void row(final String name, final long[] nanos, final long elapsedNanos, final long allocated) {
        Arrays.sort(nanos);
        REPORT.append(String.format(
                Locale.ROOT,
                "%-52s p50 %6d ns  p99 %6d ns  p99.9 %7d ns  max %8d ns  %,10.0f msg/s (incl. drain)  alloc %d B%n",
                name,
                nanos[nanos.length / 2],
                nanos[(int) (nanos.length * 0.99)],
                nanos[(int) (nanos.length * 0.999)],
                nanos[nanos.length - 1],
                nanos.length / (elapsedNanos / 1e9),
                allocated));
    }

    /** One pass of a connection's supervisor over 250 live markets: timeouts checked, nothing to send. */
    private static void supervisorPass() throws Exception {
        final Idle idle = new Idle();
        final SubjectTable table = new SubjectTable();
        final FeedConnection<Object> connection = new FeedConnection<>(
                "idle",
                new NullLogger(),
                idle,
                idle,
                table,
                new GatewayConfig.Builder().build(),
                () -> 0,
                System::nanoTime,
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                FeedListener.NONE);
        connection.assumeConnected();
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final SequencedPoller drain = ring.createPoller((seq, templateId, buffer, length) -> {});
        for (int m = 0; m < MARKETS; m++) {
            connection.add(new Subject("m" + m, 16, new StreamOutput(m, Projection.IDENTITY, ring)));
        }
        connection.control(System.nanoTime());
        for (Subject subject : table.subjects()) {
            subject.beginSnapshot();
            subject.level(Side.Bid, 40, 10, 1);
            subject.endSnapshot(1, System.nanoTime(), 1);
            drain.poll();
        }
        timeLoop("Supervisor pass (250 live markets)", () -> connection.control(System.nanoTime()));
    }

    /** A session's feeds tick: 1,000 markets on 4 connections, nothing to roll, close or retry. */
    private static void feedsTick() throws Exception {
        final Feeds feeds = new Feeds(new NullLogger(), null);
        feeds.addSource(
                new Feeds.Source("TEST", id -> new SubjectRef(id, Projection.IDENTITY), 16, null, RateBudget.UNLIMITED),
                250,
                (name, table, listener) -> {
                    final Idle idle = new Idle();
                    return new ConnectionGroup.Open<>(
                            new FeedConnection<>(
                                    name,
                                    new NullLogger(),
                                    idle,
                                    idle,
                                    table,
                                    new GatewayConfig.Builder().build(),
                                    () -> 0,
                                    System::nanoTime,
                                    ConnectLimiter.UNLIMITED,
                                    RateBudget.UNLIMITED,
                                    listener),
                            () -> {});
                });
        for (int m = 0; m < 1_000; m++) {
            feeds.addStream(m, "TEST", "m" + m, new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence()), 0);
        }
        final long[] now = {0};
        timeLoop("Feeds tick (1,000 markets, 4 connections)", () -> feeds.tick(now[0] += 1_000_000L));
    }

    /** The prober's pass over 1,000 markets, none due: what it costs to find nothing to do. */
    private static void proberPass() throws Exception {
        final long[] now = {1_000_000_000_000L};
        final BookProber prober = new BookProber(new NullLogger(), () -> now[0], BookProber.Settings.DEFAULT);
        final BookProbeClient client = new BookProbeClient() {
            @Override
            public Request prepare(final Subject subject) {
                return new Request("/book", null);
            }

            @Override
            public boolean fetch(final Request request, final Book into) {
                into.add(Side.Bid, 40, 10, 1);
                return true;
            }
        };
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final SequencedPoller drain = ring.createPoller((seq, templateId, buffer, length) -> {});
        for (int m = 0; m < 1_000; m++) {
            final Subject subject = new Subject("m" + m, 16, new StreamOutput(m, Projection.IDENTITY, ring));
            subject.beginSnapshot();
            subject.level(Side.Bid, 40, 10, 1);
            subject.endSnapshot(1, now[0], 1);
            drain.poll();
            prober.add(subject, client, RateBudget.UNLIMITED);
        }
        // Every market probed once, so the timed passes find none due.
        for (int i = 0; i < 2_000; i++) {
            now[0] += 1_000_000_000L;
            prober.doWork();
        }
        timeLoop("Prober pass (1,000 markets, none due)", () -> {
            now[0] += 1_000L;
            prober.doWork();
        });
    }

    /**
     * One record claimed, published and polled: what every book update pays on its stream's ring, single-producer
     * against the taking-turns ring (Disruptor's multi-producer) that feed streams use.
     */
    private static void ringPublish(final SequencedRingBuffer.Producers producers) throws Exception {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(
                Mbp10Schema::new, new GlobalSequence(), SequencedRingBuffer.DEFAULT_SIZE, producers);
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {});
        timeLoop("Ring publish + poll (" + producers + ")", () -> {
            final Mbp10Schema record = ring.claim();
            record.encoder.bidPrice0(1);
            ring.publish();
            poller.poll();
        });
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void timeLoop(final String name, final Work work) throws Exception {
        for (int i = 0; i < 50_000; i++) {
            work.run();
        }
        final long[] nanos = new long[100_000];
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final long allocatedBefore = threads.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        for (int i = 0; i < nanos.length; i++) {
            final long before = System.nanoTime();
            work.run();
            nanos[i] = System.nanoTime() - before;
        }
        final long elapsed = System.nanoTime() - start;
        row(name, nanos, elapsed, threads.getThreadAllocatedBytes(thread) - allocatedBefore);
    }

    /** A connection that is never opened. */
    private static final class Idle implements Transport<Object>, VenueProtocol<Object> {
        private final FrameWriter writer = new FrameWriter(64) {
            @Override
            protected void send(final ByteBuffer frame) {}

            @Override
            protected boolean isOpen() {
                return false;
            }
        };

        @Override
        public Object channel() {
            return this;
        }

        @Override
        public FrameWriter writer() {
            return writer;
        }

        @Override
        public void configure(final GatewayConfig config) {}

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public ByteBuffer read() {
            return null;
        }

        @Override
        public boolean sawTraffic() {
            return false;
        }

        @Override
        public int maxSubjectsPerCommand() {
            return 10;
        }

        @Override
        public Books books() {
            return Books.CHANGES;
        }

        @Override
        public int framesPerCommand() {
            return 1;
        }

        @Override
        public void onConnected() {}

        @Override
        public void subscribe(final Object channel, final List<Subject> subjects) {}

        @Override
        public void unsubscribe(final Object channel, final List<Subject> subjects) {}

        @Override
        public void resync(final Object channel, final List<Subject> subjects) {}

        @Override
        public void keepAlive(final Object channel) {}

        @Override
        public void onFrame(final ByteBuffer frame, final long receiveNanos) {}
    }

    /** Hands out one prepared message per read, in order, without touching a socket. */
    private static final class ReplayClient extends WebSocketClient {
        private final ByteBuffer[] messages;
        private final Response response = new Response();
        private int served;

        ReplayClient(final ByteBuffer[] messages) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 20, 1 << 16);
            this.messages = messages;
        }

        @Override
        public WebSocketResponse read() {
            if (served == messages.length) {
                return response.update(true, Opcode.TEXT, null, false);
            }
            final ByteBuffer message = messages[served++];
            message.position(0);
            return response.update(true, Opcode.TEXT, message, false);
        }
    }

    private static final class Response extends WebSocketResponse {}
}
