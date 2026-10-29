package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.FrameWriter;
import group.gnometrading.gateways.feed.transport.Transport;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Side;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FeedConnectionTest {

    /** The commands a protocol sent, as text. */
    static final class Sent {
        final List<String> commands = new ArrayList<>();
    }

    /** Frames to hand the reader, one per read. */
    static final class ScriptedTransport implements Transport<Sent> {
        final Sent sent = new Sent();
        // Concurrent: some tests feed a reader running on its own thread.
        final Deque<String> frames = new ConcurrentLinkedDeque<>();
        int opens;
        int closes;
        private boolean traffic;
        private final FrameWriter writer = new FrameWriter(64) {
            @Override
            protected void send(final ByteBuffer frame) {}

            @Override
            protected boolean isOpen() {
                return true;
            }
        };

        @Override
        public Sent channel() {
            return sent;
        }

        @Override
        public FrameWriter writer() {
            return writer;
        }

        @Override
        public void configure(final GatewayConfig config) {}

        // Set: the venue refuses the connection.
        volatile boolean refuse;

        @Override
        public void open() throws IOException {
            opens++;
            if (refuse) {
                throw new IOException("refused");
            }
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public ByteBuffer read() {
            final String frame = frames.poll();
            traffic = frame != null;
            return frame == null ? null : ByteBuffer.wrap(frame.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public boolean sawTraffic() {
            return traffic;
        }
    }

    /** "S key bid ask" snapshot, "U key price size" bid update, "G key" sequence gap. */
    static final class TextProtocol implements VenueProtocol<Sent> {
        private final SubjectTable table;
        private final int perCommand;
        int connects;

        TextProtocol(final SubjectTable table, final int perCommand) {
            this.table = table;
            this.perCommand = perCommand;
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
            return perCommand;
        }

        @Override
        public void onConnected() {
            connects++;
        }

        private static String keys(final List<Subject> subjects) {
            return String.join(",", subjects.stream().map(Subject::key).toList());
        }

        @Override
        public void subscribe(final Sent channel, final List<Subject> subjects) {
            channel.commands.add("sub " + keys(subjects));
        }

        @Override
        public void unsubscribe(final Sent channel, final List<Subject> subjects) {
            channel.commands.add("unsub " + keys(subjects));
        }

        @Override
        public void resync(final Sent channel, final List<Subject> subjects) {
            channel.commands.add("resync " + keys(subjects));
        }

        @Override
        public void keepAlive(final Sent channel) {}

        @Override
        public void onFrame(final ByteBuffer frame, final long receiveNanos) {
            final String[] parts =
                    StandardCharsets.US_ASCII.decode(frame).toString().split(" ");
            final Subject subject = table.get(parts[1]);
            if (subject == null) {
                return;
            }
            switch (parts[0]) {
                case "S" -> {
                    subject.beginSnapshot();
                    subject.level(Side.Bid, Long.parseLong(parts[2]), 1, 1);
                    subject.level(Side.Ask, Long.parseLong(parts[3]), 1, 1);
                    subject.endSnapshot(1, receiveNanos, 1);
                }
                case "U" -> {
                    subject.set(Side.Bid, Long.parseLong(parts[2]), Long.parseLong(parts[3]), 1);
                    subject.endUpdate(1, receiveNanos, 2);
                }
                case "G" -> subject.stale(GapReason.SEQUENCE, receiveNanos);
                default -> throw new IllegalArgumentException(parts[0]);
            }
        }
    }

    private long now = TimeUnit.HOURS.toNanos(1);
    private final List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final NullLogger logger = new NullLogger() {
        @Override
        public void logf(final group.gnometrading.logging.LogMessage msg, final String format, final Object... args) {
            logged.add(String.format(format, args));
        }
    };

    private final ScriptedTransport transport = new ScriptedTransport();
    private final SubjectTable table = new SubjectTable();
    private final TextProtocol protocol = new TextProtocol(table, 2);
    private final List<String> quarantined = new ArrayList<>();
    private final FeedConnection<Sent> connection = connection(RateBudget.UNLIMITED);

    private FeedConnection<Sent> connection(final RateBudget limiter) {
        return connection(limiter, new GatewayConfig.Builder().build());
    }

    private FeedConnection<Sent> connection(final RateBudget limiter, final GatewayConfig config) {
        FeedConnection<Sent> made = new FeedConnection<>(
                "test",
                logger,
                transport,
                protocol,
                table,
                config,
                () -> now / 1_000_000,
                () -> now,
                ConnectLimiter.UNLIMITED,
                limiter,
                new FeedListener() {
                    @Override
                    public void onQuarantined(final Subject subject) {
                        quarantined.add(subject.key());
                    }
                });
        made.assumeConnected();
        return made;
    }

    private final SubjectTest.Captured captured = new SubjectTest.Captured();

    private Subject market(final String key) {
        return new Subject(key, 16, new StreamOutput(key.hashCode(), Projection.IDENTITY, captured.ring));
    }

    private void read(final String... frames) throws Exception {
        connection.pauseControl().release();
        for (String frame : frames) {
            transport.frames.add(frame);
            connection.reader().doWork();
        }
    }

    private void control() throws Exception {
        connection.control(now);
    }

    @Test
    void itIsNeverReconnectedOnATimerAsItsGroupRollsItInstead() throws Exception {
        GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            connection.supervisor().onStart();
            assertEquals(1, transport.opens);

            now += TimeUnit.HOURS.toNanos(13);
            // Traffic just now, so silence doesn't reconnect it either.
            transport.frames.add("S A 40 50");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!transport.frames.isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "the reader never read the frame");
                Thread.sleep(1);
            }
            Thread.sleep(50);
            connection.supervisor().doWork();

            assertEquals(1, transport.opens, "a scheduled reconnect would drop every book before the roll");
        } finally {
            connection.pauseControl().stop();
            reader.close();
        }
    }

    @Test
    void addedMarketsAreSubscribedInBatchesAndGoLiveOnTheirSnapshot() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        Subject c = market("C");
        connection.add(a);
        connection.add(b);
        connection.add(c);
        control();

        assertEquals(List.of("sub A,B", "sub C"), transport.sent.commands);
        read("S A 40 50", "S B 41 51");
        assertEquals(SubjectState.LIVE, a.state());
        assertEquals(SubjectState.LIVE, b.state());
        assertEquals(SubjectState.SUBSCRIBING, c.state());
    }

    @Test
    void aGapResyncsOnlyThatMarket() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        connection.add(a);
        connection.add(b);
        control();
        read("S A 40 50", "S B 41 51", "G A");
        control();

        assertEquals("resync A", transport.sent.commands.get(1));
        assertEquals(SubjectState.STALE, a.state());
        assertEquals(SubjectState.LIVE, b.state());
        read("S A 42 52");
        assertEquals(SubjectState.LIVE, a.state());
    }

    @Test
    void theThirdResyncInTenMinutesQuarantinesTheMarket() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        for (int i = 0; i < 3; i++) {
            read("S A 40 50", "G A");
            now += TimeUnit.MINUTES.toNanos(1);
            control();
        }

        assertEquals(SubjectState.QUARANTINED, a.state());
        assertEquals(List.of("A"), quarantined);
        assertEquals("unsub A", transport.sent.commands.get(transport.sent.commands.size() - 1));
        assertEquals(0, connection.size());
    }

    @Test
    void theProbeFindingTheBookWrongAgainAndAgainNeverQuarantinesIt() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        for (int i = 0; i < 4; i++) {
            read("S A 40 50");
            connection.requestStale(a, GapReason.PROBE_MISMATCH);
            connection.reader().doWork();
            now += TimeUnit.MINUTES.toNanos(1);
            control();
        }

        assertTrue(quarantined.isEmpty(), "the venue skipped updates; a fresh snapshot fixes each");
        assertEquals(
                4,
                transport.sent.commands.stream()
                        .filter(c -> c.startsWith("resync"))
                        .count());
        read("S A 40 50");
        assertEquals(SubjectState.LIVE, a.state());
    }

    @Test
    void resyncsSpreadOverMoreThanTenMinutesDontQuarantine() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        for (int i = 0; i < 4; i++) {
            read("S A 40 50", "G A");
            now += TimeUnit.MINUTES.toNanos(6);
            control();
        }

        assertTrue(quarantined.isEmpty());
        assertEquals(SubjectState.STALE, a.state());
    }

    @Test
    void aMarketWithNoSnapshotIsAskedAgainThenMarkedEmpty() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        now += FeedConnection.SNAPSHOT_TIMEOUT_NANOS + 1;
        control();
        assertEquals("resync A", transport.sent.commands.get(1));

        now += FeedConnection.SNAPSHOT_TIMEOUT_NANOS + 1;
        control();
        assertEquals(SubjectState.EMPTY, a.state());

        now += FeedConnection.EMPTY_RECHECK_NANOS + 1;
        control();
        assertEquals("resync A", transport.sent.commands.get(2));
        read("S A 40 50");
        assertEquals(SubjectState.LIVE, a.state());
    }

    @Test
    void removingUnsubscribesAndTellsTheListener() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        read("S A 40 50");
        connection.remove(a);
        control();

        assertEquals("unsub A", transport.sent.commands.get(1));
        assertEquals(SubjectState.REMOVING, a.state());
        read("U A 41 5");
        assertEquals(0, connection.size());
    }

    @Test
    void aReconnectFlagsTrustedBooksAndResubscribesEveryMarket() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        connection.add(a);
        connection.add(b);
        control();
        read("S A 40 50");
        captured.drain().clear();
        // A real reader thread, as the supervisor's pause waits for the reader to stop.
        GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            connection.connect();
        } finally {
            connection.pauseControl().stop();
            reader.close();
        }

        assertEquals(1, transport.opens);
        assertEquals(1, protocol.connects);
        assertEquals(SubjectState.SUBSCRIBING, a.state());
        List<Mbp10Schema> flagged = captured.drain();
        assertEquals(1, flagged.size(), "only the trusted book goes out again, flagged");
        assertTrue(flagged.get(0).decoder.flags().badData());
        assertEquals("sub A,B", transport.sent.commands.get(transport.sent.commands.size() - 1));
    }

    @Test
    void commandsWaitForTheSourcesCommandBudget() throws Exception {
        RateBudget twoAMinute = new RateBudget(2);
        FeedConnection<Sent> paced = connection(twoAMinute);
        for (String key : List.of("A", "B", "C", "D", "E", "F")) {
            paced.add(market(key));
        }
        paced.control(now);
        assertEquals(List.of("sub A,B", "sub C,D"), transport.sent.commands);

        now += TimeUnit.SECONDS.toNanos(29);
        paced.control(now);
        assertEquals(2, transport.sent.commands.size());
        now += TimeUnit.SECONDS.toNanos(2);
        paced.control(now);
        assertEquals("sub E,F", transport.sent.commands.get(2));
    }

    @Test
    void anUnknownMarketsFrameIsIgnored() throws Exception {
        read("U Z 40 1");
        assertFalse(transport.sent.commands.contains("sub Z"));
    }

    @Test
    void aResyncWaitingOnTheBudgetDoesntTimeOutBeforeItIsSent() throws Exception {
        RateBudget oneAMinute = new RateBudget(1);
        FeedConnection<Sent> paced = connection(oneAMinute);
        Subject a = market("A");
        paced.add(a);
        paced.control(now);
        assertEquals(List.of("sub A"), transport.sent.commands);
        paced.pauseControl().release();
        transport.frames.add("S A 40 50");
        paced.reader().doWork();
        transport.frames.add("G A");
        paced.reader().doWork();

        // Its resync waits for room; the snapshot timeout mustn't count that wait.
        now += FeedConnection.SNAPSHOT_TIMEOUT_NANOS * 3;
        paced.control(now);
        assertEquals(1, transport.sent.commands.size(), "no room yet");
        now += TimeUnit.MINUTES.toNanos(1);
        paced.control(now);
        assertEquals("resync A", transport.sent.commands.get(1));
        now += FeedConnection.SNAPSHOT_TIMEOUT_NANOS - 1;
        paced.control(now);
        assertEquals(SubjectState.STALE, a.state(), "still within its timeout, not marked empty");
        assertEquals(2, transport.sent.commands.size(), "and not asked again");
    }

    @Test
    void aConnectionWideGapCountsOnceAndNeverQuarantinesItsMarkets() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        connection.add(a);
        connection.add(b);
        control();
        for (int gap = 0; gap < 3; gap++) {
            read("S A 40 50", "S B 41 51");
            a.stale(GapReason.CONNECTION_SEQUENCE, now + gap);
            b.stale(GapReason.CONNECTION_SEQUENCE, now + gap);
            now += TimeUnit.MINUTES.toNanos(1);
            control();
        }

        assertTrue(quarantined.isEmpty(), "three gaps reconnect the connection instead");
        assertEquals(2, connection.size());
        assertEquals(
                1,
                logged.stream()
                        .filter(line -> line.contains("sequence gaps in 10 minutes"))
                        .count(),
                "two markets times three gaps are three gaps, not six");
    }

    @Test
    void aStaleRequestForAMarketThatMovedGoesToItsNewConnection() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        read("S A 40 50");
        List<String> forwarded = new ArrayList<>();
        a.attach(new RecordingHost(forwarded));
        connection.table().remove(a);

        connection.requestStale(a, GapReason.PROBE_MISMATCH);
        connection.pauseControl().release();
        connection.reader().doWork();

        assertEquals(List.of("stale A:PROBE_MISMATCH"), forwarded);
        assertEquals(SubjectState.LIVE, a.state(), "this connection no longer publishes it");
    }

    @Test
    void aReleaseTheRollGaveUpOnNeverRuns() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        FeedConnection.Handover release = connection.release(List.of(a));

        assertFalse(release.await(1, TimeUnit.MILLISECONDS), "nothing drained it in time: cancelled");
        control();
        assertEquals(1, connection.size(), "still carried");
        assertSame(a, connection.table().get("A"));
    }

    @Test
    void aReleaseHandsOverOnlyTheMarketsStillCarried() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        connection.add(a);
        connection.add(b);
        control();
        connection.remove(b);
        FeedConnection.Handover release = connection.release(List.of(a, b));
        connection.pauseControl().release();
        // The reader has to stop for the handover; one runs, as the supervisor waits for it.
        GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            control();
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } finally {
            connection.pauseControl().stop();
            reader.close();
        }

        assertEquals(List.of(a), release.handed());
        assertEquals(0, connection.size());
    }

    @Test
    void aStandInMadeStaleAsItIsAdoptedStillGetsItsMarketABook() throws Exception {
        Subject shadow = new Subject("X", 16);
        connection.add(shadow);
        control();
        Subject real = market("X");
        real.beginSnapshot();
        real.level(Side.Bid, 40, 1, 1);
        real.endSnapshot(1, now, 1);
        connection.pauseControl().release();
        GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            transport.frames.add("S X 40 50");
            awaitState(shadow, SubjectState.LIVE);
            // Its resync is queued behind the adopt, for a stand-in that is gone by the time it is read.
            transport.frames.add("G X");
            awaitState(shadow, SubjectState.STALE);
            FeedConnection.Handover adopt = connection.adopt(List.of(real), List.of(shadow));
            control();
            assertTrue(adopt.await(5, TimeUnit.SECONDS));
        } finally {
            connection.pauseControl().stop();
            reader.close();
        }

        assertEquals(SubjectState.STALE, real.state());
        assertSame(real, connection.table().get("X"));
        assertTrue(transport.sent.commands.contains("resync X"), "the market asks for its book itself");
    }

    private static void awaitState(final Subject subject, final SubjectState state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (subject.state() != state) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + state);
            Thread.sleep(5);
        }
    }

    @Test
    void aMarketRemovedAndAddedAgainIsUnsubscribedBeforeItIsSubscribed() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        read("S A 40 50");
        connection.remove(a);
        connection.add(a);
        control();

        assertEquals(List.of("sub A", "unsub A", "sub A"), transport.sent.commands);
    }

    @Test
    void aConnectThatFailsFlagsTheBooksAtOnce() throws Exception {
        Subject a = market("A");
        connection.add(a);
        control();
        read("S A 40 50");
        captured.drain().clear();
        transport.refuse = true;
        GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            assertThrows(IOException.class, connection::connect);
        } finally {
            connection.pauseControl().stop();
            reader.close();
        }

        assertEquals(SubjectState.SUBSCRIBING, a.state());
        List<Mbp10Schema> flagged = captured.drain();
        assertEquals(1, flagged.size());
        assertTrue(flagged.get(0).decoder.flags().badData());
        now += FeedConnection.SNAPSHOT_TIMEOUT_NANOS * 10;
        control();
        assertEquals(SubjectState.SUBSCRIBING, a.state(), "no socket, so nothing times out");
    }

    @Test
    void aSilentOutageReconnectsOnlyOnceTheVenueIsBack() throws Exception {
        // One attempt a cycle, so each supervisor pass is one cycle while the venue accepts no connections.
        FeedConnection<Sent> outage = connection(
                RateBudget.UNLIMITED,
                new GatewayConfig.Builder()
                        .withMaxReconnectAttempts(0)
                        .withInitialBackoff(Duration.ofMillis(1))
                        .build());
        // The reader publishes the snapshots and the supervisor the bad marks, as at runtime.
        VenueTestKit.Stream stream = new VenueTestKit.Stream();
        Subject a = new Subject("A", 16, new StreamOutput(1, Projection.IDENTITY, stream.ring));
        GnomeAgentRunner reader = new GnomeAgentRunner(outage.reader(), error -> {});
        GnomeAgentRunner.startOnThread(reader);
        try {
            outage.supervisor().onStart();
            outage.add(a);
            outage.supervisor().doWork();
            transport.frames.add("S A 40 50");
            awaitState(a, SubjectState.LIVE);
            stream.drain().clear();

            transport.refuse = true;
            now += TimeUnit.SECONDS.toNanos(31);
            outage.supervisor().doWork();
            outage.supervisor().doWork();
            transport.refuse = false;
            outage.supervisor().doWork();
            transport.frames.add("S A 40 50");
            awaitState(a, SubjectState.LIVE);
            final int opens = transport.opens;
            final long subscribes = subscribes("A");
            final int published = stream.drain().size();

            for (int i = 0; i < 5; i++) {
                outage.supervisor().doWork();
            }

            final List<Mbp10Schema> after = stream.drain();
            final long badMarks = after.subList(published, after.size()).stream()
                    .filter(record -> record.decoder.flags().badData())
                    .count();
            assertAll(
                    () -> assertEquals(opens, transport.opens, "the retry's socket already answered the silence"),
                    () -> assertEquals(subscribes, subscribes("A"), "no second subscribe for the same market"),
                    () -> assertEquals(SubjectState.LIVE, a.state(), "the book just snapshotted stays trusted"),
                    () -> assertEquals(0, badMarks, "a second bad mark for one outage"));
        } finally {
            outage.pauseControl().stop();
            reader.close();
        }
    }

    private long subscribes(final String key) {
        return transport.sent.commands.stream().filter(("sub " + key)::equals).count();
    }
}
