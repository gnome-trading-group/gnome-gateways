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
    private static final long NO_POSITION = Long.MIN_VALUE;
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

    // Where its latest book and trade fell in the venue's sequence on the connection carrying it: the venue's time and
    // how many it sent at that time, counting each once whether or not it was published. Every connection sees the
    // same sequence (measured on each venue), so a roll's replacement can tell from these what the old one published.
    private long bookTime = NO_POSITION;
    private int booksAtTime;
    private long tradeTime = NO_POSITION;
    private int tradesAtTime;
    // After a roll whose replacement was behind the old connection: how far the old one had published. The
    // replacement's records up to there are ones already out; they still change the book but aren't published again.
    private long repeatBookTime = NO_POSITION;
    private int repeatBooksAtTime;
    private long repeatTradeTime = NO_POSITION;
    private int repeatTradesAtTime;
    // A roll's stand-in only: what it would have published, for the market it stands in for.
    private final ReplayLog replay;
    // Whether adopting this stand-in found records it should have published already overwritten.
    private boolean replayFellShort;
    // The book a replayed record carried; made the first time one is.
    private Book replayed;

    public Subject(final String key, final int levelsPerSide, final StreamOutput... outputs) {
        this(key, levelsPerSide, null, outputs);
    }

    private Subject(final String key, final int levelsPerSide, final ReplayLog replay, final StreamOutput... outputs) {
        this.key = key;
        this.book = new Book(levelsPerSide);
        this.outputs = outputs.clone();
        this.replay = replay;
    }

    /**
     * A market's stand-in on a roll's replacement connection: it publishes nowhere, but keeps its latest {@code
     * records} so the market can publish what its old connection didn't.
     */
    public static Subject standIn(final String key, final int levelsPerSide, final int records) {
        return new Subject(key, levelsPerSide, new ReplayLog(records));
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
            publishBook(book, lastEventTimestamp, receiveNanos, NO_SEQUENCE, true, false);
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
        final int index = nextBook(eventTimestamp);
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
        if (repeatsBook(eventTimestamp, index)) {
            lastEventTimestamp = eventTimestamp;
            top.update(book);
            return;
        }
        publishBook(book, eventTimestamp, receiveNanos, sequence, true, false);
        if (replay != null) {
            replay.book(eventTimestamp, index, book, receiveNanos, sequence, true);
        }
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
        final int index = nextBook(eventTimestamp);
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
        if (repeatsBook(eventTimestamp, index)) {
            top.update(book);
            return;
        }
        publishBook(book, eventTimestamp, receiveNanos, sequence, false, false);
        if (replay != null) {
            replay.book(eventTimestamp, index, book, receiveNanos, sequence, false);
        }
    }

    /**
     * More levels than a side can hold even at its largest: what was dropped could surface as the top changes, so the
     * book goes out flagged and is fetched again. A market that keeps overflowing is quarantined.
     */
    private void overflowed(final long receiveNanos) {
        resyncs++;
        publishBad(receiveNanos);
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
        final int index = nextTrade(eventTimestamp);
        final SubjectState current = state();
        if (current == SubjectState.REMOVING || current == SubjectState.QUARANTINED) {
            return;
        }
        if (repeatsTrade(eventTimestamp, index)) {
            return;
        }
        publishTrade(price, size, aggressor, eventTimestamp, receiveNanos, current.trusted(), book);
        if (replay != null) {
            replay.trade(eventTimestamp, index, price, size, aggressor, receiveNanos);
        }
    }

    private void publishTrade(
            final long price,
            final long size,
            final Side aggressor,
            final long eventTimestamp,
            final long receiveNanos,
            final boolean trusted,
            final Book with) {
        for (StreamOutput output : outputs) {
            final Mbp10Schema schema = output.ring().claim();
            final Mbp10Encoder encoder = schema.encoder;
            header(encoder, output, eventTimestamp, receiveNanos, NO_SEQUENCE, false, !trusted);
            encoder.price(output.projection().price(price, Mbp10Encoder.priceNullValue()));
            encoder.size(size);
            encoder.action(Action.Trade);
            encoder.side(output.projection().side(aggressor));
            writer.write(with, output.projection(), encoder);
            output.ring().publish();
        }
        records++;
    }

    // ----- where records fall in the venue's sequence -----

    private int nextBook(final long time) {
        if (time == bookTime) {
            return booksAtTime++;
        }
        bookTime = time;
        booksAtTime = 1;
        return 0;
    }

    private int nextTrade(final long time) {
        if (time == tradeTime) {
            return tradesAtTime++;
        }
        tradeTime = time;
        tradesAtTime = 1;
        return 0;
    }

    /** Whether the record at {@code time}, {@code index} comes after the {@code count} records sent up to {@code at}. */
    private static boolean after(final long time, final int index, final long at, final int count) {
        return at == NO_POSITION || time > at || (time == at && index >= count);
    }

    private boolean repeatsBook(final long time, final int index) {
        if (repeatBookTime == NO_POSITION) {
            return false;
        }
        if (after(time, index, repeatBookTime, repeatBooksAtTime)) {
            repeatBookTime = NO_POSITION;
            return false;
        }
        return true;
    }

    private boolean repeatsTrade(final long time, final int index) {
        if (repeatTradeTime == NO_POSITION) {
            return false;
        }
        if (after(time, index, repeatTradeTime, repeatTradesAtTime)) {
            repeatTradeTime = NO_POSITION;
            return false;
        }
        return true;
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
        publishBad(receiveNanos);
        if (host != null) {
            host.requestResync(this, reason, receiveNanos);
        }
    }

    /**
     * A replacement connection built this market's book on {@code shadow}; it takes that book and goes on from it.
     * Called on the replacement's supervisor with its reader paused, after the old connection let the market go, so it
     * is the only producer.
     *
     * <p>A trusted market carries on as if one connection had carried it throughout: whatever the stand-in took after
     * the last record the old connection published goes out now, in order; if the stand-in was behind instead, its
     * next records, already published by the old connection, aren't published again. A market that wasn't trusted
     * publishes the stand-in's book once as a snapshot. A stand-in with no trusted book (the venue had none) leaves the
     * market flagged: its last good book goes out once more as badData, so readers don't take it as current.
     *
     * @return how many records went out that the old connection never published
     */
    public int adoptFrom(final Subject shadow, final long receiveNanos) {
        final SubjectState shadowState = shadow.state();
        if (!shadowState.trusted()) {
            if (state().trusted()) {
                publishBad(receiveNanos);
            }
            moveTo(shadowState);
            return 0;
        }
        int published = 0;
        if (state().trusted() && shadow.replay != null) {
            published = replayFrom(shadow);
            repeatUpTo(shadow);
        } else {
            publishBook(shadow.book, shadow.lastEventTimestamp, receiveNanos, NO_SEQUENCE, true, false);
        }
        book.copyFrom(shadow.book);
        top.update(book);
        lastEventTimestamp = Math.max(lastEventTimestamp, shadow.lastEventTimestamp);
        bookTime = shadow.bookTime;
        booksAtTime = shadow.booksAtTime;
        tradeTime = shadow.tradeTime;
        tradesAtTime = shadow.tradesAtTime;
        moveTo(SubjectState.LIVE);
        return published;
    }

    /** Whether adopting this stand-in found some of what it should have published already overwritten. */
    public boolean replayFellShort() {
        return replayFellShort;
    }

    /** Publishes the stand-in's records that come after this market's last book and trade, in the order it took them. */
    private int replayFrom(final Subject shadow) {
        final ReplayLog log = shadow.replay;
        boolean bookCovered = !log.wrapped();
        boolean tradeCovered = !log.wrapped();
        Book with = book;
        int published = 0;
        for (int nth = 0; nth < log.held(); nth++) {
            final int slot = log.slot(nth);
            final boolean isBook = log.isBook(slot);
            if (isBook
                    ? after(log.time(slot), log.index(slot), bookTime, booksAtTime)
                    : after(log.time(slot), log.index(slot), tradeTime, tradesAtTime)) {
                with = replay(log, slot, with);
                published++;
            } else if (isBook) {
                bookCovered = true;
            } else {
                tradeCovered = true;
            }
        }
        // Records past this market's last ones exist but the oldest of them kept is already past it: some were lost.
        shadow.replayFellShort = (!bookCovered && shadow.tookBooksAfter(bookTime, booksAtTime))
                || (!tradeCovered && shadow.tookTradesAfter(tradeTime, tradesAtTime));
        return published;
    }

    /** Publishes one of a stand-in's records; returns the book a trade after it goes out with. */
    private Book replay(final ReplayLog log, final int slot, final Book with) {
        if (!log.isBook(slot)) {
            publishTrade(
                    log.tradePrice(slot),
                    log.tradeSize(slot),
                    log.tradeSide(slot),
                    log.time(slot),
                    log.receive(slot),
                    true,
                    with);
            return with;
        }
        if (replayed == null) {
            replayed = new Book(Mbp10Writer.LEVELS);
        }
        log.bookInto(slot, replayed);
        publishBook(replayed, log.time(slot), log.receive(slot), log.sequence(slot), log.snapshot(slot), false);
        return replayed;
    }

    private boolean tookBooksAfter(final long at, final int count) {
        return bookTime != NO_POSITION && after(bookTime, booksAtTime - 1, at, count);
    }

    private boolean tookTradesAfter(final long at, final int count) {
        return tradeTime != NO_POSITION && after(tradeTime, tradesAtTime - 1, at, count);
    }

    /** A stand-in still short of what the old connection published: its records up to there are repeats. */
    private void repeatUpTo(final Subject shadow) {
        if (bookTime != NO_POSITION && !shadow.tookBooksAfter(bookTime, booksAtTime)) {
            repeatBookTime = bookTime;
            repeatBooksAtTime = booksAtTime;
        }
        if (tradeTime != NO_POSITION && !shadow.tookTradesAfter(tradeTime, tradesAtTime)) {
            repeatTradeTime = tradeTime;
            repeatTradesAtTime = tradesAtTime;
        }
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
            publishBad(receiveNanos);
        }
        moveTo(SubjectState.SUBSCRIBING);
    }

    /**
     * The book once more, flagged badData, stamped when it stopped being trusted: its last update can be minutes old on
     * a quiet market, and a replay would show the book bad from then.
     */
    private void publishBad(final long receiveNanos) {
        publishBook(book, Math.max(lastEventTimestamp, receiveNanos), receiveNanos, NO_SEQUENCE, false, true);
    }

    private void publishBook(
            final Book from,
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
            writer.write(from, output.projection(), encoder);
            output.ring().publish();
        }
        top.update(from);
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
