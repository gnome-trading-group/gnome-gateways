package group.gnometrading.gateways.feed;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.transport.FrameWriter;
import group.gnometrading.gateways.feed.transport.Transport;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.book.Side;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A venue that answers every subscribe with the market's book at {@link #bid}, and takes pushed updates. */
public final class FakeVenue implements Transport<FakeVenue>, VenueProtocol<FakeVenue> {
    final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
    // Every market subscribed or resynced, in order.
    final List<String> subscribed = new CopyOnWriteArrayList<>();
    final SubjectTable table;
    volatile long bid = 40;
    // Off: subscribes go unanswered, as for a market the venue has no book for.
    volatile boolean answers = true;
    volatile boolean closed;
    private boolean traffic;
    private final FrameWriter writer = new FrameWriter(64) {
        @Override
        protected void send(final ByteBuffer frame) {}

        @Override
        protected boolean isOpen() {
            return true;
        }
    };

    FakeVenue(final SubjectTable table) {
        this.table = table;
    }

    @Override
    public FakeVenue channel() {
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
    public void close() {
        closed = true;
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
    public void subscribe(final FakeVenue channel, final List<Subject> subjects) {
        for (Subject subject : subjects) {
            subscribed.add(subject.key());
            if (!answers) {
                continue;
            }
            frames.add("S " + subject.key() + " " + bid);
        }
    }

    @Override
    public void unsubscribe(final FakeVenue channel, final List<Subject> subjects) {}

    @Override
    public void resync(final FakeVenue channel, final List<Subject> subjects) {
        subscribe(channel, subjects);
    }

    @Override
    public void keepAlive(final FakeVenue channel) {}

    @Override
    public void onFrame(final ByteBuffer frame, final long receiveNanos) {
        final String[] parts =
                StandardCharsets.US_ASCII.decode(frame).toString().split(" ");
        final Subject subject = table.get(parts[1]);
        if (subject == null) {
            return;
        }
        if (parts[0].equals("S")) {
            subject.beginSnapshot();
            subject.level(Side.Bid, Long.parseLong(parts[2]), 1, 1);
            subject.endSnapshot(1, receiveNanos, 1);
        } else {
            subject.set(Side.Bid, Long.parseLong(parts[2]), 1, 1);
            subject.endUpdate(1, receiveNanos, 2);
        }
    }

    /** Opens connections to fake venues, each with running reader and supervisor threads. */
    public static final class Opener implements ConnectionGroup.Opener<FakeVenue> {
        public final List<FakeVenue> venues = new CopyOnWriteArrayList<>();
        public final List<FeedConnection<FakeVenue>> opened = new CopyOnWriteArrayList<>();
        // Venues opened from now on leave subscribes unanswered.
        public volatile boolean silent;
        // The next open throws, as when the venue refuses the connection.
        public volatile boolean failNext;
        public int attempts;

        @Override
        public ConnectionGroup.Open<FakeVenue> open(
                final String name, final SubjectTable table, final FeedListener listener) {
            attempts++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("refused");
            }
            FakeVenue venue = new FakeVenue(table);
            venue.answers = !silent;
            FeedConnection<FakeVenue> connection = new FeedConnection<>(
                    name,
                    new NullLogger(),
                    venue,
                    venue,
                    table,
                    new GatewayConfig.Builder().build(),
                    System::currentTimeMillis,
                    System::nanoTime,
                    ConnectLimiter.UNLIMITED,
                    RateBudget.UNLIMITED,
                    listener);
            GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), e -> {});
            GnomeAgentRunner supervisor = new GnomeAgentRunner(connection.supervisor(), e -> {});
            GnomeAgentRunner.startOnThread(reader);
            GnomeAgentRunner.startOnThread(supervisor);
            venues.add(venue);
            opened.add(connection);
            return new ConnectionGroup.Open<>(connection, () -> {
                connection.pauseControl().stop();
                supervisor.close();
                reader.close();
            });
        }
    }
}
