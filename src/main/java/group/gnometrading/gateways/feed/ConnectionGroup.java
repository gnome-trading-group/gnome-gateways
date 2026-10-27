package group.gnometrading.gateways.feed;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
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
 * markets and waits until each has its book, then, with both readers paused, each market moves to the new connection
 * taking the book built there, and the old connection is closed. Nothing is missed and every stream keeps a single
 * producer. A replacement that doesn't get every book in time is closed and tried again later, the old one untouched.
 *
 * <p>Its methods are called from one thread, the runtime's.
 */
public final class ConnectionGroup<C> {

    static final long EMPTY_CLOSE_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long ROLL_INTERVAL_NANOS = TimeUnit.HOURS.toNanos(12);
    static final long ROLL_JITTER_NANOS = TimeUnit.MINUTES.toNanos(30);
    static final long ROLL_SYNC_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final long HANDOVER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long ROLL_RETRY_NANOS = TimeUnit.MINUTES.toNanos(10);

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
    private final long syncTimeoutNanos;
    private final long handoverTimeoutNanos;
    private int opened;

    public ConnectionGroup(
            final String source,
            final int maxPerConnection,
            final Opener<C> opener,
            final FeedListener listener,
            final Logger logger) {
        this(source, maxPerConnection, opener, listener, logger, ROLL_SYNC_TIMEOUT_NANOS, HANDOVER_TIMEOUT_NANOS);
    }

    /** With shorter roll timeouts, for tests of a roll that fails. */
    ConnectionGroup(
            final String source,
            final int maxPerConnection,
            final Opener<C> opener,
            final FeedListener listener,
            final Logger logger,
            final long syncTimeoutNanos,
            final long handoverTimeoutNanos) {
        this.source = source;
        this.maxPerConnection = maxPerConnection;
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
        final Slot<C> slot = new Slot<>(open, now + ROLL_INTERVAL_NANOS + jitter());
        slots.add(slot);
        return slot;
    }

    private static long jitter() {
        return ThreadLocalRandom.current().nextLong(-ROLL_JITTER_NANOS, ROLL_JITTER_NANOS + 1);
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
                    new Subject(market.key(), market.book().bids().capacity());
            shadows.put(market, shadow);
            next.add(shadow);
        }
        if (!synced(shadows)) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: replacement for %s didn't sync; keeping it",
                    source,
                    old.connection().name());
            return false;
        }
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
        awaitAdopt(next, next.adopt(handed, standIns));
        final Slot<C> slot = new Slot<>(replacement, now + ROLL_INTERVAL_NANOS + jitter());
        slots.add(slot);
        for (Subject market : handed) {
            placed.put(market, slot);
            slot.markets++;
            old.markets--;
        }
        close(old);
        logger.logf(
                LogMessage.DEBUG,
                "%s: rolled %s to %s with %d markets",
                source,
                old.connection().name(),
                next.name(),
                handed.size());
        return true;
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
            for (Map.Entry<Subject, Subject> entry : shadows.entrySet()) {
                final SubjectState state = entry.getValue().state();
                all &= state.trusted()
                        || (state == SubjectState.EMPTY
                                && !entry.getKey().state().trusted());
            }
            if (all) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private void closeQuietly(final Open<C> open) {
        try {
            open.threads().close();
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to close a replacement: %s", e);
        }
    }
}
