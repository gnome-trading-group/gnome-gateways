package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A connection reads every message of every market it carries, so reading, decoding, updating books and publishing
 * must not allocate once warm. Each venue is fed a correctly sequenced stream through a stub socket and measured; so is
 * the prober's pass over its markets.
 */
@Tag("allocation")
class FeedAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;
    private static final int MESSAGES = 1 + WARM_UP + MEASURED;

    private static ByteBuffer ascii(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void kalshiWithYesAndNoStreamsDoesNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES + 2];
        messages[0] = ascii("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"orderbook_delta\",\"sid\":1}}");
        messages[1] = ascii("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"T\","
                + "\"yes_dollars_fp\":[[\"0.5500\",\"100.00\"]],\"no_dollars_fp\":[[\"0.6000\",\"50.00\"]]}}");
        for (int i = 2; i < messages.length; i++) {
            messages[i] = ascii("{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + i + ",\"msg\":{\"market_ticker\":"
                    + "\"T\",\"price_dollars\":\"0.550\",\"delta_fp\":\"" + (i % 2 == 0 ? "-1.00" : "1.00")
                    + "\",\"side\":\"yes\",\"ts_ms\":1700000000000}}");
        }
        final PrivateKey key =
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        assertNoAllocation(
                messages,
                "T",
                table -> new KalshiProtocol(new NullLogger(), table, "key", key, () -> 0),
                Projection.IDENTITY,
                Projection.COMPLEMENT);
    }

    @Test
    void lighterNonceChainDoesNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = ascii("{\"channel\":\"order_book:0\",\"offset\":" + i + ",\"order_book\":{\"code\":0,"
                    + "\"asks\":[{\"price\":\"4212.00\",\"size\":\"" + (i % 2 == 0 ? "2.0" : "3.0") + "\"}],"
                    + "\"bids\":[{\"price\":\"4209.00\",\"size\":\"3.0\"}],\"offset\":" + i + ",\"nonce\":" + (i + 1)
                    + ",\"begin_nonce\":" + (i == 0 ? 0 : i) + "},\"timestamp\":1761585003001,\"type\":\""
                    + (i == 0 ? "subscribed/order_book" : "update/order_book") + "\"}");
        }
        assertNoAllocation(messages, "0", table -> null, Projection.IDENTITY);
    }

    @Test
    void polymarketIntlBestPriceCheckDoesNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        messages[0] = ascii("[{\"market\":\"c\",\"asset_id\":\"token\",\"timestamp\":\"1782753357000\","
                + "\"bids\":[{\"price\":\"0.08\",\"size\":\"3\"}],\"asks\":[{\"price\":\"0.10\",\"size\":\"6\"}],"
                + "\"event_type\":\"book\",\"last_trade_price\":\"0.09\"}]");
        for (int i = 1; i < messages.length; i++) {
            messages[i] = ascii("{\"market\":\"c\",\"price_changes\":[{\"asset_id\":\"token\",\"price\":\"0.07\","
                    + "\"size\":\"" + (i % 2 == 0 ? "0" : "5") + "\",\"side\":\"BUY\",\"hash\":\"h\","
                    + "\"best_bid\":\"0.08\",\"best_ask\":\"0.10\"}],\"timestamp\":\"" + (1782753357000L + i)
                    + "\",\"event_type\":\"price_change\"}");
        }
        assertNoAllocation(messages, "token", PolymarketIntlProtocol::new, Projection.IDENTITY);
    }

    @Test
    void hyperliquidWholeBooksDoNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = ascii("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":" + (1700000000000L + i)
                    + ",\"levels\":[[{\"px\":\"2300.5\",\"sz\":\"" + (i % 2 == 0 ? "1.5" : "2.5") + "\",\"n\":3}],"
                    + "[{\"px\":\"2301\",\"sz\":\"0.25\",\"n\":1}]]}}");
        }
        assertNoAllocation(messages, "ETH", table -> new HyperliquidProtocol(table, () -> 0), Projection.IDENTITY);
    }

    @Test
    void theProbersPassDoesNotAllocate() {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        ring.createPoller((seq, templateId, buffer, length) -> {});
        final long[] now = {1_000_000_000_000L};
        // Every market probed every second, so warming up compiles the probe itself, not only the pass that skips it.
        final BookProber prober = new BookProber(
                new NullLogger(),
                () -> now[0],
                new BookProber.Settings(1_000_000_000L, BookProber.Settings.DEFAULT.silenceMinNanos(), 20));
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
        for (int i = 0; i < 100; i++) {
            final Subject subject = new Subject("m" + i, 16);
            subject.beginSnapshot();
            subject.level(Side.Bid, 40, 10, 1);
            subject.endSnapshot(1, now[0], 1);
            prober.add(subject, client, RateBudget.UNLIMITED);
        }
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARM_UP; i++) {
            now[0] += 100_000_000L;
            prober.doWork();
        }
        final long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) {
            now[0] += 100_000_000L;
            prober.doWork();
        }
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertTrue(prober.probes() > 100, "the markets were probed");
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over " + MEASURED + " passes");
    }

    /** A connection that is never opened: markets wait on it and nothing runs on another thread. */
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
        public Books books() {
            return Books.CHANGES;
        }

        @Override
        public int framesPerCommand() {
            return 1;
        }

        @Override
        public int maxSubjectsPerCommand() {
            return 10;
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

    /** A connection's supervisor checks every market's timeouts on each pass. */
    @Test
    void theSupervisorsPassDoesNotAllocate() throws Exception {
        final Idle idle = new Idle();
        final SubjectTable table = new SubjectTable();
        final long[] now = {1_000_000_000_000L};
        final FeedConnection<Object> connection = new FeedConnection<>(
                "idle",
                new NullLogger(),
                idle,
                idle,
                table,
                new GatewayConfig.Builder().build(),
                () -> now[0] / 1_000_000,
                () -> now[0],
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                FeedListener.NONE);
        connection.assumeConnected();
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final SequencedPoller drain = ring.createPoller((seq, templateId, buffer, length) -> {});
        for (int i = 0; i < 50; i++) {
            final Subject subject = new Subject("m" + i, 16, new StreamOutput(i, Projection.IDENTITY, ring));
            connection.add(subject);
        }
        connection.control(now[0]);
        for (Subject subject : table.subjects()) {
            subject.beginSnapshot();
            subject.level(Side.Bid, 40, 10, 1);
            subject.endSnapshot(1, now[0], 1);
            drain.poll();
        }
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARM_UP; i++) {
            now[0] += 1_000_000L;
            connection.control(now[0]);
        }
        final long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) {
            now[0] += 1_000_000L;
            connection.control(now[0]);
        }
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertEquals(50, connection.size());
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over " + MEASURED + " passes");
    }

    /** A trading session ticks its feeds every second inside its own JVM. */
    @Test
    void aFeedsTickDoesNotAllocate() throws Exception {
        final Feeds feeds = new Feeds(new NullLogger(), null);
        feeds.addSource(
                new Feeds.Source("TEST", id -> new SubjectRef(id, Projection.IDENTITY), 16, null, RateBudget.UNLIMITED),
                20,
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
                                    System::currentTimeMillis,
                                    System::nanoTime,
                                    ConnectLimiter.UNLIMITED,
                                    RateBudget.UNLIMITED,
                                    listener),
                            () -> {});
                });
        for (int i = 0; i < 50; i++) {
            feeds.addStream(i, "TEST", "m" + i, new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence()), 0);
        }
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final long second = 1_000_000_000L;
        long now = 0;
        for (int i = 0; i < WARM_UP; i++) {
            feeds.tick(now += second);
        }
        final long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) {
            feeds.tick(now += second);
        }
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertEquals(3, feeds.groups().get(0).connections().size(), "no roll in the time measured");
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over " + MEASURED + " ticks");
    }

    /**
     * Feeds the messages through a real connection and transport to one market with a stream per projection. A null
     * protocol means Lighter, whose protocol needs the connection's channel.
     */
    private static void assertNoAllocation(
            final ByteBuffer[] messages,
            final String key,
            final Function<SubjectTable, VenueProtocol<WebSocketChannel>> protocols,
            final Projection... projections)
            throws Exception {
        final ReplayClient client = new ReplayClient(messages);
        final WebSocketTransport transport = new WebSocketTransport(client, 1 << 14);
        final SubjectTable table = new SubjectTable();
        final VenueProtocol<WebSocketChannel> made = protocols.apply(table);
        final VenueProtocol<WebSocketChannel> protocol =
                made != null ? made : new LighterProtocol(table, transport.channel());
        final FeedConnection<WebSocketChannel> connection = new FeedConnection<>(
                "test",
                new NullLogger(),
                transport,
                protocol,
                table,
                new GatewayConfig.Builder().build(),
                () -> 0,
                () -> 9_000_000_000L,
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                FeedListener.NONE);
        final StreamOutput[] outputs = new StreamOutput[projections.length];
        final SequencedPoller[] pollers = new SequencedPoller[projections.length];
        final long[] published = new long[1];
        for (int i = 0; i < projections.length; i++) {
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            pollers[i] = ring.createPoller((seq, templateId, buffer, length) -> published[0]++);
            outputs[i] = new StreamOutput(i + 1, projections[i], ring);
        }
        final Subject subject = new Subject(key, 1024, outputs);
        connection.add(subject);
        connection.control(0);
        connection.pauseControl().release();

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        read(connection, pollers, messages.length - MEASURED);
        final long before = threads.getThreadAllocatedBytes(thread);
        read(connection, pollers, MEASURED);
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertEquals(messages.length, client.served, "every message was read");
        assertEquals(SubjectState.LIVE, subject.state(), "no message failed a check");
        assertTrue(published[0] > MEASURED, "the book was published");
        // Measuring can itself allocate a little; anything per message would be hundreds of kilobytes here.
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over " + MEASURED + " messages");
    }

    private static void read(
            final FeedConnection<WebSocketChannel> connection, final SequencedPoller[] pollers, final int count)
            throws Exception {
        for (int i = 0; i < count; i++) {
            connection.reader().doWork();
            for (SequencedPoller poller : pollers) {
                poller.poll();
            }
        }
    }

    /** Hands out one prepared message per read, in order, without touching a socket. */
    private static final class ReplayClient extends WebSocketClient {
        private final ByteBuffer[] messages;
        private final Response response = new Response();
        int served;

        ReplayClient(final ByteBuffer[] messages) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 16, 1 << 16);
            this.messages = messages;
        }

        @Override
        public WebSocketResponse read() {
            if (this.served == this.messages.length) {
                return this.response.update(true, Opcode.TEXT, null, false);
            }
            final ByteBuffer message = this.messages[this.served++];
            message.position(0);
            return this.response.update(true, Opcode.TEXT, message, false);
        }
    }

    private static final class Response extends WebSocketResponse {}
}
