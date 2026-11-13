package group.gnometrading.gateways.feed;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A source's connections in one process: markets are packed onto the fullest connection with room, up to the source's
 * markets per connection; a connection is opened when none has room and closed once it has been empty for
 * {@link #EMPTY_CLOSE_NANOS}.
 *
 * <p>Each connection is replaced on a schedule, make before break: a new connection subscribes stand-ins for its
 * markets and waits until each has its book, then each market lets go of the old connection and moves to the new one,
 * taking the book built there, and the old connection is closed. Every stream keeps a single producer. Between the old
 * connection letting go and the new one taking over, a stand-in keeps what came in; the market publishes whatever of
 * it the old connection hadn't, so the stream reads as if one connection had carried it (see {@link
 * Subject#adoptFrom}). A replacement that doesn't get every book in time is closed and tried again later, the old one
 * untouched.
 *
 * <p>Its methods are called from one thread, the runtime's.
 */
public final class ConnectionGroup<C> {

    static final long EMPTY_CLOSE_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long ROLL_SYNC_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final long HANDOVER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long ROLL_RETRY_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int UNSYNCED_NAMED = 5;
    // Records a stand-in keeps for its market to publish: the hand-over takes from milliseconds to a second, so this
    // is several times what a busy market sends in that time.
    static final int STAND_IN_RECORDS = 128;

    /** Opens a connection to the source: its transport, protocol and threads. */
    public interface Opener<C> {
        Open<C> open(String name, SubjectTable table, FeedListener listener) throws Exception;
    }

    /** A running connection and what stops it. */
    public record Open<C>(FeedConnection<C> connection, AutoCloseable threads) {}

    private static final class Slot<C> {
        final Open<C> open;
        int markets;
        long emptySinceNanos;
        long nextRollNanos;

        Slot(final Open<C> open, final long nextRollNanos) {
            this.open = open;
            this.nextRollNanos = nextRollNanos;
        }

        FeedConnection<C> connection() {
            return open.connection();
        }
    }

    private final String source;
    private final int maxPerConnection;
    private final Opener<C> opener;
    private final FeedListener listener;
    private final Logger logger;
    private final List<Slot<C>> slots = new ArrayList<>();
    private final Map<Subject, Slot<C>> placed = new IdentityHashMap<>();
    private final long rollEveryNanos;
    // Spread so connections opened together don't all roll together.
    private final long rollJitterNanos;
    private final long syncTimeoutNanos;
    private final long handoverTimeoutNanos;
    private int opened;

    /** @param rollEvery how long a connection lives before it is replaced, under any age limit the venue enforces */
    public ConnectionGroup(
            final String source,
            final int maxPerConnection,
            final Duration rollEvery,
            final Opener<C> opener,
            final FeedListener listener,
            final Logger logger) {
        this(
                source,
                maxPerConnection,
                rollEvery,
                opener,
                listener,
                logger,
                ROLL_SYNC_TIMEOUT_NANOS,
                HANDOVER_TIMEOUT_NANOS);
    }

    /** With shorter roll timeouts, for tests of a roll that fails. */
    ConnectionGroup(
            final String source,
            final int maxPerConnection,
            final Duration rollEvery,
            final Opener<C> opener,
            final FeedListener listener,
            final Logger logger,
            final long syncTimeoutNanos,
            final long handoverTimeoutNanos) {
        if (rollEvery.isZero() || rollEvery.isNegative()) {
            throw new IllegalArgumentException("rollEvery must be positive: " + rollEvery);
        }
        this.source = source;
        this.maxPerConnection = maxPerConnection;
        this.rollEveryNanos = rollEvery.toNanos();
        this.rollJitterNanos = rollEveryNanos / 24;
        this.opener = opener;
        this.listener = listener;
        this.logger = logger;
        this.syncTimeoutNanos = syncTimeoutNanos;
        this.handoverTimeoutNanos = handoverTimeoutNanos;
    }

    public String source() {
        return source;
    }

    public List<FeedConnection<C>> connections() {
        final List<FeedConnection<C>> all = new ArrayList<>(slots.size());
        for (Slot<C> slot : slots) {
            all.add(slot.connection());
        }
        return all;
    }

    /** Whether the source sends book changes rather than whole books; false until a connection is open. */
    public boolean sendsChanges() {
        return !slots.isEmpty() && slots.get(0).connection().sendsChanges();
    }

    public int size() {
        return placed.size();
    }

    /** Places a market on the fullest connection with room, opening one if none has. */
    public void add(final Subject subject, final long now) throws Exception {
        if (placed.containsKey(subject)) {
            return;
        }
        Slot<C> best = null;
        for (Slot<C> slot : slots) {
            final int size = slot.markets;
            if (size < maxPerConnection && (best == null || size > best.markets)) {
                best = slot;
            }
        }
        if (best == null) {
            best = openSlot(now);
        }
        placed.put(subject, best);
        best.markets++;
        best.emptySinceNanos = 0;
        best.connection().add(subject);
    }

    public void remove(final Subject subject, final long now) {
        final Slot<C> slot = placed.remove(subject);
        if (slot == null) {
            return;
        }
        slot.markets--;
        slot.connection().remove(subject);
        if (slot.markets == 0) {
            slot.emptySinceNanos = now;
        }
    }

    /** A market the connection quarantined is no longer placed; the runtime retries it later through {@link #add}. */
    void forget(final Subject subject, final long now) {
        final Slot<C> slot = placed.remove(subject);
        if (slot != null && --slot.markets == 0) {
            slot.emptySinceNanos = now;
        }
    }

    private Slot<C> openSlot(final long now) throws Exception {
        final Open<C> open = opener.open(source + "-" + opened++, new SubjectTable(), listener);
        open.connection().carryMarkets();
        final Slot<C> slot = new Slot<>(open, nextRoll(now));
        slots.add(slot);
        return slot;
    }

    private long nextRoll(final long now) {
        return now + rollEveryNanos + ThreadLocalRandom.current().nextLong(-rollJitterNanos, rollJitterNanos + 1);
    }

    /** Closes connections left empty and rolls any whose time has come; allocates nothing unless it does either. */
    public void tick(final long now) throws Exception {
        // Backwards: a close removes this slot and a roll appends its replacement, neither touching those not yet seen.
        for (int i = slots.size() - 1; i >= 0; i--) {
            final Slot<C> slot = slots.get(i);
            if (slot.markets == 0) {
                if (slot.emptySinceNanos != 0 && now - slot.emptySinceNanos >= EMPTY_CLOSE_NANOS) {
                    close(slot);
                }
            } else if (now >= slot.nextRollNanos) {
                roll(slot, now);
            }
        }
    }

    /** Closes every connection. */
    public void closeAll() {
        for (Slot<C> slot : new ArrayList<>(slots)) {
            close(slot);
        }
    }

    private void close(final Slot<C> slot) {
        slots.remove(slot);
        try {
            slot.open.threads().close();
        } catch (Exception e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Failed to close %s: %s",
                    slot.connection().name(),
                    e);
        }
    }

    /**
     * Replaces {@code old}: stand-ins sync on a new connection, then the old one lets its markets go and the new one
     * takes each over with its stand-in's book. Any failure before the old one lets go leaves it untouched and tries
     * again in {@link #ROLL_RETRY_NANOS}.
     */
    void roll(final Slot<C> old, final long now) throws Exception {
        // Pushed back first, so a failure anywhere below waits rather than retrying on every tick.
        old.nextRollNanos = now + ROLL_RETRY_NANOS;
        final List<Subject> markets = new ArrayList<>();
        for (Map.Entry<Subject, Slot<C>> entry : placed.entrySet()) {
            if (entry.getValue() == old) {
                markets.add(entry.getKey());
            }
        }
        final Open<C> replacement;
        try {
            replacement = opener.open(source + "-" + opened++, new SubjectTable(), listener);
        } catch (Exception e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: couldn't open a replacement for %s: %s",
                    source,
                    old.connection().name(),
                    e);
            return;
        }
        boolean moved = false;
        try {
            moved = handOver(old, replacement, markets, now);
        } finally {
            if (!moved) {
                closeQuietly(replacement);
            }
        }
    }

    /** Whether the markets moved to {@code replacement}; false leaves them all on {@code old}. */
    private boolean handOver(final Slot<C> old, final Open<C> replacement, final List<Subject> markets, final long now)
            throws InterruptedException {
        final FeedConnection<C> next = replacement.connection();
        final Map<Subject, Subject> shadows = new IdentityHashMap<>();
        for (Subject market : markets) {
            final Subject shadow =
                    Subject.standIn(market.key(), market.book().bids().capacity(), STAND_IN_RECORDS);
            shadows.put(market, shadow);
            next.add(shadow);
        }
        if (!synced(shadows)) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: replacement for %s didn't sync; keeping it. Still waiting: %s",
                    source,
                    old.connection().name(),
                    unsynced(shadows));
            return false;
        }
        final long releaseNanos = System.nanoTime();
        final FeedConnection.Handover released = old.connection().release(markets);
        if (!released.await(handoverTimeoutNanos, TimeUnit.NANOSECONDS)) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: %s didn't let its markets go; keeping it",
                    source,
                    old.connection().name());
            return false;
        }
        // From here the markets are off the old connection and must land on the new one, however long it takes.
        next.carryMarkets();
        final List<Subject> handed = released.handed();
        final List<Subject> standIns = new ArrayList<>(handed.size());
        for (Subject market : handed) {
            standIns.add(shadows.remove(market));
        }
        // Markets the old connection no longer carried (quarantined meanwhile) are the feeds' to place again.
        for (Subject leftOver : shadows.values()) {
            next.remove(leftOver);
        }
        final FeedConnection.Handover adopted = next.adopt(handed, standIns);
        awaitAdopt(next, adopted);
        final long gapMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - releaseNanos);
        final Slot<C> slot = new Slot<>(replacement, nextRoll(now));
        slots.add(slot);
        for (Subject market : handed) {
            placed.put(market, slot);
            slot.markets++;
            old.markets--;
        }
        close(old);
        logger.logf(
                rolledLevel(adopted.fellShort()),
                "%s: rolled %s to %s with %d markets in %d us, %d records replayed, %d markets short of some",
                source,
                old.connection().name(),
                next.name(),
                handed.size(),
                gapMicros,
                adopted.replayed(),
                adopted.fellShort());
        return true;
    }

    /** A roll that left some market short of records left a hole in its stream: an error, not routine. */
    static LogMessage rolledLevel(final int fellShort) {
        return fellShort > 0 ? LogMessage.UNKNOWN_ERROR : LogMessage.DEBUG;
    }

    /** Waits through interrupts too: the markets are on no connection until the adopt is done. */
    private void awaitAdopt(final FeedConnection<C> next, final FeedConnection.Handover adopted) {
        boolean interrupted = false;
        while (true) {
            try {
                if (adopted.finishedWithin(handoverTimeoutNanos, TimeUnit.NANOSECONDS)) {
                    break;
                }
                logger.logf(
                        LogMessage.UNKNOWN_ERROR, "%s: %s slow to take its markets over; waiting", source, next.name());
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether every stand-in has its book before the timeout. One the venue has no book for counts only when its market
     * has none either: a trusted market handed an empty stand-in would lose a book it has.
     */
    private boolean synced(final Map<Subject, Subject> shadows) throws InterruptedException {
        final long deadline = System.nanoTime() + syncTimeoutNanos;
        while (System.nanoTime() < deadline) {
            boolean all = true;
            for (Subject shadow : shadows.values()) {
                all &= settled(shadow);
            }
            if (all) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * Has its answer from the venue: a book, or none at all. A market that closed since the old connection last heard
     * from it still looks live there, but the venue now sends no book; waiting for one would keep the connection from
     * ever rolling (2026-10-27). The market goes EMPTY on the new connection, rechecked as any empty market is.
     */
    private static boolean settled(final Subject shadow) {
        final SubjectState state = shadow.state();
        return state.trusted() || state == SubjectState.EMPTY;
    }

    /** The markets a roll is still waiting on, a few by name, for the log. */
    private static String unsynced(final Map<Subject, Subject> shadows) {
        final List<String> waiting = new ArrayList<>();
        int count = 0;
        for (Subject shadow : shadows.values()) {
            if (!settled(shadow)) {
                if (count++ < UNSYNCED_NAMED) {
                    waiting.add(shadow.key() + " (" + shadow.state() + ")");
                }
            }
        }
        return count + " markets" + (waiting.isEmpty() ? "" : ", e.g. " + String.join(", ", waiting));
    }

    private void closeQuietly(final Open<C> open) {
        try {
            open.threads().close();
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to close a replacement: %s", e);
        }
    }
}
