package group.gnometrading.gateways.feed;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewaySupervisor;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.feed.transport.Transport;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;

/**
 * One connection to a source carrying many markets. Generic: the venue is its {@link VenueProtocol}, the wire its
 * {@link Transport}. Three threads run it ({@link #reader}, the transport's writer, {@link #supervisor}); markets are
 * added, removed and resynced from any thread through a queue the supervisor drains.
 *
 * <p>Records are published only by the reader, or by the supervisor while it holds the reader paused, so each stream's
 * ring keeps one producer at a time. A roll's handover runs here too, so it never interleaves with a reconnect.
 *
 * <p>Commands go out no faster than the source's {@link RateBudget} and the writer's room allow; a market leaves the
 * pending set only once its command is queued.
 */
public final class FeedConnection<C> implements SubjectHost {

    static final long SNAPSHOT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long EMPTY_RECHECK_NANOS = TimeUnit.MINUTES.toNanos(15);
    static final int MAX_RESYNCS = 3;
    static final long RESYNC_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10);
    // Writer slots commands leave free, for keep-alives the supervisor may send before the writer drains.
    static final int KEEP_ALIVE_SLOTS = 8;
    // Far enough off never to come: a feed connection is replaced by its group's roll instead.
    static final Duration NO_SCHEDULED_RECONNECT = Duration.ofDays(36_500);

    private sealed interface Command {}

    private record Add(Subject subject) implements Command {}

    private record Remove(Subject subject) implements Command {}

    private record Resync(Subject subject, GapReason reason, long atNanos) implements Command {}

    private record Adopt(List<Subject> real, List<Subject> shadows, Handover handover) implements Command {}

    private record Release(List<Subject> subjects, Handover handover) implements Command {}

    /** What the reader does for another thread: make a market stale, or publish its book once more. */
    private sealed interface ReaderRequest {}

    private record Stale(Subject subject, GapReason reason) implements ReaderRequest {}

    private record Republish(Subject subject) implements ReaderRequest {}

    private enum Kind {
        SUBSCRIBE,
        UNSUBSCRIBE,
        RESYNC
    }

    /**
     * One step of a roll, done by a connection's supervisor. The roll may give up on it while it is still queued, and
     * then it is never done: a release that ran after the roll had moved on would leave its markets on no connection.
     */
    public static final class Handover {
        private static final int PENDING = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;

        private final AtomicInteger state = new AtomicInteger(PENDING);
        private final CountDownLatch finished = new CountDownLatch(1);
        // Filled by a release: the markets it handed over, those still on the connection.
        private final List<Subject> handed = new ArrayList<>();

        /** Waits for it; false if it timed out and was cancelled, so it will never run. */
        public boolean await(final long timeout, final TimeUnit unit) throws InterruptedException {
            if (finished.await(timeout, unit)) {
                return true;
            }
            if (state.compareAndSet(PENDING, CANCELLED)) {
                return false;
            }
            // Already running: it finishes promptly.
            finished.await();
            return true;
        }

        /** Waits up to the timeout without giving up on it; whether it has finished. */
        public boolean finishedWithin(final long timeout, final TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        /** The markets a finished release handed over. */
        public List<Subject> handed() {
            return handed;
        }

        private boolean start() {
            return state.compareAndSet(PENDING, STARTED);
        }

        private void finish() {
            finished.countDown();
        }
    }

    /** The supervisor's notes on a market: when it was asked for, and its recent resyncs. */
    private static final class Notes {
        final Subject subject;
        long awaitingSinceNanos;
        int attempts;
        final long[] resyncs = new long[MAX_RESYNCS];
        int resyncCount;

        Notes(final Subject subject) {
            this.subject = subject;
        }
    }

    // ----- the reader -----

    private final class Reader implements GnomeAgent {
        @Override
        public int doWork() {
            if (!pause.awaitIfPaused()) {
                return 0;
            }
            ReaderRequest request;
            while ((request = readerRequests.poll()) != null) {
                serve(request);
            }
            final ByteBuffer frame;
            try {
                frame = transport.read();
            } catch (Exception e) {
                pause.pauseSelf();
                logger.logf(LogMessage.SOCKET_DISCONNECTED, "%s: %s", name, e);
                throw new SocketClosedException(e);
            }
            if (transport.sawTraffic()) {
                lastTrafficNanos = nanoClock.nanoTime();
            }
            if (frame == null) {
                return 0;
            }
            final long receiveNanos = nanoClock.nanoTime();
            raw.capture(receiveNanos, frame);
            try {
                protocol.onFrame(frame, receiveNanos);
            } catch (RuntimeException e) {
                // A frame that can't be read at all leaves every market on the connection in doubt.
                pause.pauseSelf();
                logger.logf(LogMessage.UNKNOWN_ERROR, "%s: unreadable frame: %s", name, e);
                throw new SocketClosedException(e);
            }
            return 1;
        }

        /** A request for a market a roll has moved on is passed to the connection carrying it now. */
        private void serve(final ReaderRequest request) {
            if (request instanceof Stale stale) {
                final Subject subject = stale.subject();
                if (table.get(subject.key()) == subject) {
                    subject.stale(stale.reason(), nanoClock.nanoTime());
                } else if (subject.host() != FeedConnection.this && subject.host() != null) {
                    subject.host().requestStale(subject, stale.reason());
                }
            } else if (request instanceof Republish republish) {
                final Subject subject = republish.subject();
                if (table.get(subject.key()) == subject) {
                    subject.republish(nanoClock.nanoTime());
                } else if (subject.host() != FeedConnection.this && subject.host() != null) {
                    subject.host().requestRepublish(subject);
                }
            }
        }

        @Override
        public void onClose() {
            pause.readerExited();
        }

        @Override
        public ThreadProfile threadProfile() {
            return ThreadProfile.HOT_PATH;
        }

        @Override
        public String roleName() {
            return "feed-reader";
        }
    }

    // ----- the supervisor -----

    private final class Supervisor extends GatewaySupervisor {

        Supervisor(
                final Logger logger,
                final GatewayConfig config,
                final EpochClock clock,
                final EpochNanoClock nanoClock,
                final ConnectLimiter connects) {
            super(logger, FeedConnection.this::connect, config, clock, nanoClock, connects);
        }

        @Override
        protected void doDisconnect() throws Exception {
            connected = false;
            pause.pause();
            synchronized (transport.writer().socketLock()) {
                transport.close();
            }
        }

        @Override
        protected void doKeepAlive() throws Exception {
            protocol.keepAlive(transport.channel());
        }

        @Override
        protected long recvTimestamp() {
            return lastTrafficNanos;
        }

        @Override
        protected int doControl() throws Exception {
            return control(nanoClock.nanoTime());
        }

        @Override
        public String roleName() {
            return "feed-supervisor";
        }
    }

    private final String name;
    private final Logger logger;
    private final Transport<C> transport;
    private final VenueProtocol<C> protocol;
    private final SubjectTable table;
    private final EpochNanoClock nanoClock;
    private final RateBudget messages;
    private final FeedListener listener;
    private final ReaderPauseControl pause = new ReaderPauseControl();
    private final ConcurrentLinkedQueue<Command> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ReaderRequest> readerRequests = new ConcurrentLinkedQueue<>();
    private final Reader reader = new Reader();
    private final Supervisor supervisor;

    // Supervisor thread only. The notes are kept in a list as well, so the timeout pass walks them without allocating.
    private final Map<Subject, Notes> notes = new IdentityHashMap<>();
    private final List<Notes> noteList = new ArrayList<>();
    private final Set<Subject> toSubscribe = new LinkedHashSet<>();
    private final Set<Subject> toResync = new LinkedHashSet<>();
    private final Set<Subject> toUnsubscribe = new LinkedHashSet<>();
    private final List<Subject> batch = new ArrayList<>();
    private final long[] connectionGaps = new long[MAX_RESYNCS];
    private int connectionGapCount;
    private long lastConnectionGapNanos;

    private volatile RawDataSink raw = RawDataSink.NO_OP;
    private volatile long lastTrafficNanos;
    private volatile boolean carriesMarkets;
    // Supervisor thread: whether the socket is open; timeouts and commands wait for it.
    private boolean connected;

    public FeedConnection(
            final String name,
            final Logger logger,
            final Transport<C> transport,
            final VenueProtocol<C> protocol,
            final SubjectTable table,
            final GatewayConfig config,
            final EpochClock clock,
            final EpochNanoClock nanoClock,
            final ConnectLimiter connects,
            final RateBudget messages,
            final FeedListener listener) {
        this.name = name;
        this.logger = logger;
        this.transport = transport;
        this.protocol = protocol;
        this.table = table;
        this.nanoClock = nanoClock;
        this.messages = messages;
        this.listener = listener;
        // Its group replaces it every 12 h, make before break; the supervisor's own scheduled reconnect would drop
        // every book first. It still reconnects when the socket fails or goes silent.
        this.supervisor = new Supervisor(
                logger, config.withReconnectInterval(NO_SCHEDULED_RECONNECT), clock, nanoClock, connects);
        try {
            transport.configure(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String name() {
        return name;
    }

    /**
     * Whether streams depend on it: from the start for a connection its group opens for markets, but a roll's
     * replacement only once the old connection lets its markets go. Until then, a replacement that fails costs
     * nothing, as the old connection still carries them.
     */
    public boolean carriesMarkets() {
        return carriesMarkets;
    }

    void carryMarkets() {
        carriesMarkets = true;
    }

    public GnomeAgent reader() {
        return reader;
    }

    public GnomeAgent writer() {
        return transport.writer();
    }

    public GatewaySupervisor supervisor() {
        return supervisor;
    }

    public ReaderPauseControl pauseControl() {
        return pause;
    }

    public SubjectTable table() {
        return table;
    }

    /** Every frame as received, for re-parsing later (decision 9). */
    public void setRawSink(final RawDataSink sink) {
        this.raw = sink;
    }

    public int size() {
        return table.size();
    }

    /** Whether its venue sends book changes rather than whole books, so its books can drift and are probed. */
    public boolean sendsChanges() {
        return protocol.books() == VenueProtocol.Books.CHANGES;
    }

    // ----- any thread -----

    public void add(final Subject subject) {
        subject.attach(this);
        queue.add(new Add(subject));
    }

    public void remove(final Subject subject) {
        queue.add(new Remove(subject));
    }

    @Override
    public void requestResync(final Subject subject, final GapReason reason, final long atNanos) {
        queue.add(new Resync(subject, reason, atNanos));
    }

    @Override
    public void requestStale(final Subject subject, final GapReason reason) {
        readerRequests.add(new Stale(subject, reason));
    }

    @Override
    public void requestRepublish(final Subject subject) {
        readerRequests.add(new Republish(subject));
    }

    /**
     * A roll's first step, on the connection being replaced: its markets leave it, the reader held paused meanwhile so
     * it is done publishing them, without being unsubscribed as the connection is closing.
     */
    public Handover release(final List<Subject> subjects) {
        final Handover handover = new Handover();
        queue.add(new Release(List.copyOf(subjects), handover));
        return handover;
    }

    /**
     * A roll's second step, on the replacement: each market takes the place and the book of the stand-in built for it
     * here, the reader held paused meanwhile, and publishes that book as a snapshot.
     */
    public Handover adopt(final List<Subject> real, final List<Subject> shadows) {
        final Handover handover = new Handover();
        queue.add(new Adopt(List.copyOf(real), List.copyOf(shadows), handover));
        return handover;
    }

    // ----- supervisor thread -----

    /**
     * Supervisor thread, through the connect controller: (re)opens the connection and resubscribes every market. Each
     * market's book goes out flagged as the attempt starts, so a connection that keeps failing to connect leaves no
     * market looking current.
     */
    void connect() throws IOException {
        connected = false;
        pause.pause();
        // Paused: the supervisor is the only producer while it publishes their last books.
        drainQueue();
        final long attempt = nanoClock.nanoTime();
        for (int i = 0; i < noteList.size(); i++) {
            noteList.get(i).subject.disconnected(attempt);
        }
        try {
            synchronized (transport.writer().socketLock()) {
                transport.close();
                transport.writer().newSocket();
                protocol.authenticate(transport.channel());
                transport.open();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        protocol.onConnected();
        final long now = nanoClock.nanoTime();
        lastTrafficNanos = now;
        drainQueue();
        toResync.clear();
        // The new socket carries none of the old subscriptions.
        toUnsubscribe.clear();
        for (int i = 0; i < noteList.size(); i++) {
            final Notes note = noteList.get(i);
            note.subject.disconnected(now);
            toSubscribe.add(note.subject);
            note.awaitingSinceNanos = 0;
            note.attempts = 0;
        }
        connected = true;
        pause.resume();
        sendPending(now);
    }

    /** As if the socket had opened, for tests that drive a connection without its supervisor. */
    @VisibleForTesting
    void assumeConnected() {
        connected = true;
    }

    int control(final long now) throws IOException {
        final int drained = drainQueue();
        if (!connected) {
            // No socket: nothing can arrive or be sent, so nothing times out; a connect resubscribes everything.
            return drained;
        }
        checkTimeouts(now);
        return drained + sendPending(now);
    }

    private int drainQueue() {
        int drained = 0;
        Command command;
        while ((command = queue.poll()) != null) {
            drained++;
            if (command instanceof Add add) {
                put(add.subject());
            } else if (command instanceof Remove remove) {
                takeOff(remove.subject(), SubjectState.REMOVING);
            } else if (command instanceof Resync resync) {
                resync(resync.subject(), resync.reason(), resync.atNanos());
            } else if (command instanceof Release release) {
                if (release.handover().start()) {
                    holdingReader(() -> releaseNow(release));
                }
                release.handover().finish();
            } else if (command instanceof Adopt adopt) {
                if (adopt.handover().start()) {
                    holdingReader(() -> adoptNow(adopt));
                }
                adopt.handover().finish();
            }
        }
        return drained;
    }

    /**
     * A market back from quarantine or removal waits for a new book like any other. One still waiting to be
     * unsubscribed is unsubscribed first, as unsubscribes go before subscribes: a venue asked to subscribe a market it
     * still sends may send no snapshot.
     */
    private void put(final Subject subject) {
        forceState(subject, SubjectState.SUBSCRIBING);
        table.put(subject);
        track(subject);
        toSubscribe.add(subject);
    }

    /**
     * Runs {@code action} with the reader stopped. A reader already held, for a reconnect, stays held; so does one
     * that stopped itself on a broken socket meanwhile, until the reconnect its error brings reopens it.
     */
    private void holdingReader(final Runnable action) {
        final boolean running = !pause.isPauseRequested();
        if (running) {
            pause.pause();
        }
        try {
            action.run();
        } finally {
            if (running && !pause.pausedItself()) {
                pause.resume();
            }
        }
    }

    private void releaseNow(final Release release) {
        for (Subject subject : release.subjects()) {
            // One quarantined or removed meanwhile is the feeds' to place again, not the roll's.
            if (table.get(subject.key()) == subject) {
                table.remove(subject);
                untrack(subject);
                release.handover().handed.add(subject);
            }
        }
    }

    private void adoptNow(final Adopt adopt) {
        final long now = nanoClock.nanoTime();
        for (int i = 0; i < adopt.real().size(); i++) {
            final Subject real = adopt.real().get(i);
            final Subject shadow = adopt.shadows().get(i);
            real.attach(this);
            final Notes shadowNote = notes.get(shadow);
            if (shadowNote == null || table.get(shadow.key()) != shadow) {
                // The stand-in was quarantined here meanwhile: the market starts over on this connection.
                real.disconnected(now);
                put(real);
                continue;
            }
            real.adoptFrom(shadow, now);
            table.put(real);
            final boolean subscribePending = toSubscribe.contains(shadow);
            final boolean resyncPending = toResync.contains(shadow);
            untrack(shadow);
            final Notes note = track(real);
            note.awaitingSinceNanos = shadowNote.awaitingSinceNanos;
            note.attempts = shadowNote.attempts;
            if (subscribePending) {
                toSubscribe.add(real);
            }
            if (resyncPending) {
                toResync.add(real);
            }
            // A stand-in made stale as the handover began left its resync queued for itself, now gone: the market
            // asks again, or nothing would ever fetch it a book.
            if (real.state() == SubjectState.STALE && !subscribePending) {
                note.awaitingSinceNanos = 0;
                toResync.add(real);
            }
        }
    }

    private Notes track(final Subject subject) {
        Notes note = notes.get(subject);
        if (note == null) {
            note = new Notes(subject);
            notes.put(subject, note);
            noteList.add(note);
        }
        return note;
    }

    private void untrack(final Subject subject) {
        final Notes note = notes.remove(subject);
        if (note != null) {
            noteList.remove(note);
        }
        toSubscribe.remove(subject);
        toResync.remove(subject);
    }

    private void resync(final Subject subject, final GapReason reason, final long atNanos) {
        final Notes note = notes.get(subject);
        if (note == null) {
            return;
        }
        final long now = nanoClock.nanoTime();
        if (reason == GapReason.CONNECTION_SEQUENCE) {
            countConnectionGap(atNanos, now);
        } else if (countResync(note, now)) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: quarantined %s after %d resyncs (%s)",
                    name,
                    subject.key(),
                    MAX_RESYNCS,
                    reason);
            takeOff(subject, SubjectState.QUARANTINED);
            listener.onQuarantined(subject);
            return;
        }
        logger.logf(LogMessage.DEBUG, "%s: resyncing %s (%s)", name, subject.key(), reason);
        // Its snapshot timeout starts once the resync is sent.
        note.awaitingSinceNanos = 0;
        toResync.add(subject);
    }

    /** Whether this is the market's third resync within the window. */
    private static boolean countResync(final Notes note, final long now) {
        note.resyncs[note.resyncCount++ % MAX_RESYNCS] = now;
        final long oldest = note.resyncs[note.resyncCount % MAX_RESYNCS];
        return note.resyncCount >= MAX_RESYNCS && now - oldest < RESYNC_WINDOW_NANOS;
    }

    /**
     * Every market of one connection-wide gap asks to resync with the same frame time, so the gap counts once. A
     * connection that keeps losing its sequence is reconnected rather than having all its markets quarantined.
     */
    private void countConnectionGap(final long atNanos, final long now) {
        if (atNanos == lastConnectionGapNanos) {
            return;
        }
        lastConnectionGapNanos = atNanos;
        connectionGaps[connectionGapCount++ % MAX_RESYNCS] = now;
        final long oldest = connectionGaps[connectionGapCount % MAX_RESYNCS];
        if (connectionGapCount >= MAX_RESYNCS && now - oldest < RESYNC_WINDOW_NANOS) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR, "%s: %d sequence gaps in 10 minutes, reconnecting", name, MAX_RESYNCS);
            connectionGapCount = 0;
            supervisor.forceReconnect();
        }
    }

    /** Drops a market from the connection, leaving it in {@code state}; it is unsubscribed as the budget allows. */
    private void takeOff(final Subject subject, final SubjectState state) {
        forceState(subject, state);
        final boolean carried = table.get(subject.key()) == subject;
        if (carried) {
            table.remove(subject);
            toUnsubscribe.add(subject);
        }
        untrack(subject);
    }

    private static void forceState(final Subject subject, final SubjectState state) {
        while (true) {
            final SubjectState current = subject.state();
            if (current == state || subject.transition(current, state)) {
                return;
            }
        }
    }

    /** Markets that never got a snapshot are asked again once, then marked empty; empty ones are rechecked. */
    private void checkTimeouts(final long now) {
        for (int i = 0; i < noteList.size(); i++) {
            final Notes note = noteList.get(i);
            final Subject subject = note.subject;
            final SubjectState state = subject.state();
            if (state == SubjectState.SUBSCRIBING || state == SubjectState.STALE) {
                checkAwaiting(subject, note, state, now);
            } else if (state == SubjectState.EMPTY && now - note.awaitingSinceNanos > EMPTY_RECHECK_NANOS) {
                note.awaitingSinceNanos = 0;
                toResync.add(subject);
            } else if (state.trusted()) {
                note.attempts = 0;
                note.awaitingSinceNanos = 0;
            }
        }
    }

    private void checkAwaiting(final Subject subject, final Notes note, final SubjectState state, final long now) {
        if (note.awaitingSinceNanos == 0 || now - note.awaitingSinceNanos <= SNAPSHOT_TIMEOUT_NANOS) {
            return;
        }
        if (note.attempts++ == 0) {
            note.awaitingSinceNanos = 0;
            toResync.add(subject);
        } else if (subject.transition(state, SubjectState.EMPTY)) {
            logger.logf(LogMessage.DEBUG, "%s: %s sent no book", name, subject.key());
            note.awaitingSinceNanos = now;
        }
    }

    private int sendPending(final long now) throws IOException {
        if (toUnsubscribe.isEmpty() && toSubscribe.isEmpty() && toResync.isEmpty()) {
            return 0;
        }
        // Unsubscribes first, so a market dropped and added again ends up subscribed.
        int sent = send(toUnsubscribe, Kind.UNSUBSCRIBE, now);
        sent += send(toSubscribe, Kind.SUBSCRIBE, now);
        sent += send(toResync, Kind.RESYNC, now);
        return sent;
    }

    /**
     * Sends as many of {@code pending}'s markets as the message budget and the writer's room allow, a command at a
     * time; the rest wait for a later pass. A market's snapshot timeout starts as its command is queued.
     */
    private int send(final Set<Subject> pending, final Kind kind, final long now) throws IOException {
        if (pending.isEmpty()) {
            return 0;
        }
        final int perCommand = Math.max(1, protocol.maxSubjectsPerCommand());
        final int frames = Math.max(1, protocol.framesPerCommand());
        final int commandsWanted = (pending.size() + perCommand - 1) / perCommand;
        final int roomFor = Math.max(0, transport.writer().messageRoom() - KEEP_ALIVE_SLOTS) / frames;
        final int allowed = messages.tryAcquire(Math.min(commandsWanted, roomFor), frames, now);
        int sent = 0;
        for (int command = 0; command < allowed && !pending.isEmpty(); command++) {
            batch.clear();
            final Iterator<Subject> iterator = pending.iterator();
            while (iterator.hasNext() && batch.size() < perCommand) {
                batch.add(iterator.next());
            }
            dispatch(kind);
            sent(pending, kind, now);
            sent++;
        }
        return sent;
    }

    private void dispatch(final Kind kind) throws IOException {
        switch (kind) {
            case SUBSCRIBE -> protocol.subscribe(transport.channel(), batch);
            case UNSUBSCRIBE -> protocol.unsubscribe(transport.channel(), batch);
            case RESYNC -> protocol.resync(transport.channel(), batch);
            default -> throw new IllegalStateException(kind.name());
        }
    }

    /** The batch's markets leave {@code pending}; each one asked for a book starts waiting for it now. */
    private void sent(final Set<Subject> pending, final Kind kind, final long now) {
        for (int i = 0; i < batch.size(); i++) {
            final Subject subject = batch.get(i);
            pending.remove(subject);
            final Notes note = notes.get(subject);
            if (note != null && kind != Kind.UNSUBSCRIBE && note.awaitingSinceNanos == 0) {
                note.awaitingSinceNanos = now;
            }
        }
    }
}
