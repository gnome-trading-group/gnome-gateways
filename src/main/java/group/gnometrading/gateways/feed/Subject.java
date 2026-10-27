package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.Mbp10Writer;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * One venue market: its book, where it stands, how lively it is, and the streams it feeds. The venue's decoder drives it
 * through {@link #beginSnapshot} … {@link #endSnapshot}, {@link #set}/{@link #add} … {@link #endUpdate}, {@link #trade}
 * and {@link #stale}; it publishes every change to each stream, as that stream's projection sees it.
 *
 * <p>Threads: the reader of the connection carrying it does everything but move its state, which the connection's
 * supervisor and the silence check may also do, always by compare-and-set from the state they expect. Other threads
 * read {@link #state}, the liveness fields and {@link #top}.
 */
public final class Subject {

    private static final long NO_SEQUENCE = Mbp10Encoder.sequenceNullValue();
    private static final long NO_TIME = Mbp10Encoder.timestampEventNullValue();
    // How much of each new gap the mean takes in: about the last 16 messages' worth.
    private static final int GAP_SMOOTHING_SHIFT = 4;
    private static final VarHandle STATE;

    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(Subject.class, "state", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String key;
    private final Book book;
    // Replaced whole when a stream joins or leaves; the reader reads it once per publish.
    private volatile StreamOutput[] outputs;
    private final Mbp10Writer writer = new Mbp10Writer();
    private final TopLevels top = new TopLevels();
    private volatile SubjectHost host;

    @SuppressWarnings("unused")
    private volatile int state = SubjectState.SUBSCRIBING.ordinal();

    private long lastEventTimestamp = NO_TIME;
    private volatile long lastReceiveNanos;
    private volatile long meanGapNanos;
    private volatile long lastVerifiedNanos;
    private volatile long records;
    private volatile long resyncs;

    public Subject(final String key, final int levelsPerSide, final StreamOutput... outputs) {
        this.key = key;
        this.book = new Book(levelsPerSide);
        this.outputs = outputs.clone();
    }

    public String key() {
        return key;
    }

    public Book book() {
        return book;
    }

    public TopLevels top() {
        return top;
    }

    /**
     * Another stream of this market (its other outcome's listing, say) starts reading it. It needs the book before
     * any update, so the connection is asked to publish it again. Any thread.
     */
    public synchronized void addOutput(final StreamOutput output) {
        final StreamOutput[] current = outputs;
        final StreamOutput[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = output;
        outputs = next;
        final SubjectHost attached = host;
        if (attached != null) {
            attached.requestRepublish(this);
        }
    }

    /** A stream stops reading this market; returns how many still do. Any thread. */
    public synchronized int removeOutput(final int streamId) {
        outputs = Arrays.stream(outputs).filter(o -> o.streamId() != streamId).toArray(StreamOutput[]::new);
        return outputs.length;
    }

    /** Reader thread: publishes the book once more, as a snapshot, if it is trusted; for a stream that just joined. */
    public void republish(final long receiveNanos) {
        if (state().trusted()) {
            publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, true, false);
        }
    }

    /** The connection carrying it; null before it is placed on one. */
    public SubjectHost host() {
        return host;
    }

    /** The connection now carrying it. */
    public void attach(final SubjectHost newHost) {
        this.host = newHost;
    }

    public SubjectState state() {
        return SubjectState.of(state);
    }

    /** Moves to {@code next} if it is in {@code expected}; whichever thread wins a race decides. */
    public boolean transition(final SubjectState expected, final SubjectState next) {
        return STATE.compareAndSet(this, expected.ordinal(), next.ordinal());
    }

    private void moveTo(final SubjectState next) {
        int current;
        do {
            current = state;
            if (current == SubjectState.REMOVING.ordinal() || current == SubjectState.QUARANTINED.ordinal()) {
                return;
            }
        } while (!STATE.compareAndSet(this, current, next.ordinal()));
    }

    // ----- driven by the venue's decoder, on the reader thread -----

    public void beginSnapshot() {
        book.clear();
    }

    public void level(final Side side, final long price, final long size, final long count) {
        book.set(side, price, size, count);
    }

    /** The snapshot is complete: the market is live from here, and its book goes out flagged as a snapshot. */
    public void endSnapshot(final long eventTimestamp, final long receiveNanos, final long sequence) {
        received(receiveNanos);
        final SubjectState current = state();
        if (current == SubjectState.REMOVING || current == SubjectState.QUARANTINED) {
            return;
        }
        if (book.overflowed()) {
            lastEventTimestamp = eventTimestamp;
            moveTo(SubjectState.STALE);
            overflowed(receiveNanos);
            return;
        }
        moveTo(SubjectState.LIVE);
        publishBook(eventTimestamp, receiveNanos, sequence, true, false);
    }

    /** Whether updates apply now: not while a snapshot is awaited, so a stale book isn't patched. */
    public boolean accepting() {
        return state().trusted();
    }

    public void set(final Side side, final long price, final long size, final long count) {
        if (accepting()) {
            book.set(side, price, size, count);
        }
    }

    public void add(final Side side, final long price, final long delta, final long count) {
        if (accepting()) {
            book.add(side, price, delta, count);
        }
    }

    /** One message's updates are applied: publish the book. */
    public void endUpdate(final long eventTimestamp, final long receiveNanos, final long sequence) {
        received(receiveNanos);
        if (!accepting()) {
            return;
        }
        lastEventTimestamp = eventTimestamp;
        if (book.overflowed()) {
            moveTo(SubjectState.STALE);
            overflowed(receiveNanos);
            return;
        }
        if (state == SubjectState.SUSPECT.ordinal()) {
            transition(SubjectState.SUSPECT, SubjectState.LIVE);
        }
        publishBook(eventTimestamp, receiveNanos, sequence, false, false);
    }

    /**
     * More levels than a side can hold even at its largest: what was dropped could surface as the top changes, so the
     * book goes out flagged and is fetched again. A market that keeps overflowing is quarantined.
     */
    private void overflowed(final long receiveNanos) {
        resyncs++;
        publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, false, true);
        if (host != null) {
            host.requestResync(this, GapReason.BOOK_OVERFLOW, receiveNanos);
        }
    }

    /** A trade, published with the book as it stands; flagged badData when the book isn't trusted. */
    public void trade(
            final long price,
            final long size,
            final Side aggressor,
            final long eventTimestamp,
            final long receiveNanos) {
        received(receiveNanos);
        final SubjectState current = state();
        if (current == SubjectState.REMOVING || current == SubjectState.QUARANTINED) {
            return;
        }
        final boolean trusted = current.trusted();
        for (StreamOutput output : outputs) {
            final Mbp10Schema schema = output.ring().claim();
            final Mbp10Encoder encoder = schema.encoder;
            header(encoder, output, eventTimestamp, receiveNanos, NO_SEQUENCE, false, !trusted);
            encoder.price(output.projection().price(price, Mbp10Encoder.priceNullValue()));
            encoder.size(size);
            encoder.action(Action.Trade);
            encoder.side(output.projection().side(aggressor));
            writer.write(book, output.projection(), encoder);
            output.ring().publish();
        }
        records++;
    }

    /**
     * The book is wrong: it goes out once more flagged badData, so readers know from this moment, and the connection
     * is asked for a fresh snapshot. Updates are ignored until it arrives.
     */
    public void stale(final GapReason reason, final long receiveNanos) {
        final SubjectState current = state();
        if (!current.trusted() && current != SubjectState.SUBSCRIBING) {
            return;
        }
        moveTo(SubjectState.STALE);
        resyncs++;
        publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, false, true);
        if (host != null) {
            host.requestResync(this, reason, receiveNanos);
        }
    }

    /**
     * A replacement connection built this market's book on {@code shadow}; it takes that book and goes on from it,
     * publishing it once as a snapshot. A stand-in with no trusted book (the venue had none) leaves the market flagged:
     * its last good book goes out once more as badData, so readers don't take it as current. Called on the
     * replacement's supervisor with its reader paused, after the old connection let the market go, so it is the only
     * producer.
     */
    public void adoptFrom(final Subject shadow, final long receiveNanos) {
        final SubjectState shadowState = shadow.state();
        if (!shadowState.trusted()) {
            if (state().trusted()) {
                publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, false, true);
            }
            moveTo(shadowState);
            return;
        }
        book.copyFrom(shadow.book);
        lastEventTimestamp = shadow.lastEventTimestamp;
        moveTo(SubjectState.LIVE);
        publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, true, false);
    }

    /**
     * Its connection dropped or is being replaced: a trusted book goes out once flagged badData, and it waits for the
     * next snapshot. Called with the connection's reader paused, so it is still the only producer.
     */
    public void disconnected(final long receiveNanos) {
        final SubjectState current = state();
        if (current == SubjectState.REMOVING || current == SubjectState.QUARANTINED) {
            return;
        }
        if (current.trusted()) {
            publishBook(lastEventTimestamp, receiveNanos, NO_SEQUENCE, false, true);
        }
        moveTo(SubjectState.SUBSCRIBING);
    }

    private void publishBook(
            final long eventTimestamp,
            final long receiveNanos,
            final long sequence,
            final boolean snapshot,
            final boolean badData) {
        lastEventTimestamp = eventTimestamp;
        for (StreamOutput output : outputs) {
            final Mbp10Schema schema = output.ring().claim();
            final Mbp10Encoder encoder = schema.encoder;
            header(encoder, output, eventTimestamp, receiveNanos, sequence, snapshot, badData);
            encoder.price(Mbp10Encoder.priceNullValue());
            encoder.size(Mbp10Encoder.sizeNullValue());
            encoder.action(Action.Modify);
            encoder.side(Side.None);
            writer.write(book, output.projection(), encoder);
            output.ring().publish();
        }
        top.update(book);
        records++;
    }

    private static void header(
            final Mbp10Encoder encoder,
            final StreamOutput output,
            final long eventTimestamp,
            final long receiveNanos,
            final long sequence,
            final boolean snapshot,
            final boolean badData) {
        encoder.streamId(output.streamId());
        encoder.timestampEvent(eventTimestamp);
        encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        encoder.timestampRecv(receiveNanos);
        encoder.sequence(sequence);
        encoder.depth(Mbp10Encoder.depthNullValue());
        encoder.flags().clear().marketByPrice(true).snapshot(snapshot).badData(badData);
    }

    // ----- liveness -----

    private void received(final long receiveNanos) {
        final long previous = lastReceiveNanos;
        if (previous != 0) {
            final long gap = receiveNanos - previous;
            final long mean = meanGapNanos;
            meanGapNanos = mean == 0 ? gap : mean + ((gap - mean) >> GAP_SMOOTHING_SHIFT);
        }
        lastReceiveNanos = receiveNanos;
    }

    /** Receive time of its latest message, on the reader's nanosecond clock; 0 before any. */
    public long lastReceiveNanos() {
        return lastReceiveNanos;
    }

    /** Its usual gap between messages; 0 until it has had two. */
    public long meanGapNanos() {
        return meanGapNanos;
    }

    /** When the probe last found its book right, on the probe's clock; 0 if never. */
    public long lastVerifiedNanos() {
        return lastVerifiedNanos;
    }

    public void verified(final long nanos) {
        lastVerifiedNanos = nanos;
    }

    public long records() {
        return records;
    }

    public long resyncs() {
        return resyncs;
    }
}
