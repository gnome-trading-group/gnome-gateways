package group.gnometrading.gateways.account.admission;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * How an account's lane shares what its budget leaves after cancels and lookups between sessions: start-time fair
 * queuing over each session's queued amends and new orders. Each request sent charges its session what it cost divided
 * by the session's weight, and the session with something queued that has been charged least goes next, its amends
 * before its new orders.
 *
 * <p>A session that had nothing queued for over a second comes back level with the sessions still sending, never
 * ahead, so what it didn't use went to the others for good. One back sooner, as when its queued orders all waited too
 * long between two of the venue's slots, keeps its place: levelling it then would throw away the turns its weight
 * earned before it could spend them. Its place is kept at most a second of the budget behind the level, however often
 * it comes and goes, or a session that keeps coming back could save up enough to shut the others out for as long as
 * their orders may wait. No session has to hold a place in a rotation, which matters because a queued request waits at
 * most its session's limit for a connection or the budget, then is refused.
 *
 * <p>A session whose orders are all being cancelled is fenced: its new orders are refused until the gateway introduces
 * it again, so none that was on its way when the cancel was asked for reaches the venue after it.
 *
 * <p>The lists link through the orders themselves, so nothing here allocates once built. On the lane's thread only.
 */
public final class FairQueue<E extends AdmissionEntry> {

    /** The most sessions an account serves: a bound against a leak, far above what its budget can serve. */
    public static final int MAX_SESSIONS = 1024;

    // Away longer than this, a session comes back level with the rest; sooner, it keeps its place. Long enough to span
    // the gaps between a slow venue's slots.
    static final long IDLE_LEVEL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final E[] orders;
    private final long defaultMaxQueueNanos;
    private int defaultMaxOpenOrders;
    private long defaultNewPerSecond;
    private final LongSupplier nanoClock;
    // Refuses a queued request that waited too long, or drops one that no longer stands; it must unqueue the order.
    private final Consumer<? super E> expire;
    // The most a session back within IDLE_LEVEL_NANOS may stay behind the level, in thousandths of a token at weight 1.
    private final long maxCreditMilliTokens;

    private final AdmissionSession[] sessions;
    private final int[] freeSessions;
    private int freeSessionCount;
    private final Long2ObjectHashMap<AdmissionSession> sessionsBySlot;
    // A session on the ring of sessions with something queued; null if none has.
    private AdmissionSession ring;
    // What the session served last had been charged before it: where a session that comes back starts.
    private long servedFrom;
    // Submits queued in sessions' lists.
    private int queuedSubmits;
    private FairShareTrace trace;

    /**
     * @param maxCreditMilliTokens a second of the budget, in thousandths of a token: the most a session that keeps its
     *     place may be owed
     */
    public FairQueue(
            final E[] orders,
            final long defaultMaxQueueNanos,
            final LongSupplier nanoClock,
            final Consumer<? super E> expire,
            final long maxCreditMilliTokens) {
        this.orders = orders;
        this.defaultMaxQueueNanos = defaultMaxQueueNanos;
        this.nanoClock = nanoClock;
        this.expire = expire;
        this.maxCreditMilliTokens = maxCreditMilliTokens;
        this.sessions = new AdmissionSession[MAX_SESSIONS];
        this.freeSessions = new int[MAX_SESSIONS];
        for (int i = 0; i < MAX_SESSIONS; i++) {
            this.sessions[i] = new AdmissionSession(i);
            this.freeSessions[i] = MAX_SESSIONS - 1 - i;
        }
        this.freeSessionCount = MAX_SESSIONS;
        this.sessionsBySlot = new Long2ObjectHashMap<>(MAX_SESSIONS * 2, 0.5f);
    }

    // ----- sessions -----

    /** The session in {@code slot}, introduced with the defaults if it wasn't; null if the account has no room. */
    public AdmissionSession session(final long slot) {
        AdmissionSession session = this.sessionsBySlot.get(slot);
        if (session == null) {
            if (this.freeSessionCount == 0) {
                return null;
            }
            session = this.sessions[this.freeSessions[--this.freeSessionCount]];
            session.reset();
            session.slot = slot;
            session.weight = 1;
            session.maxQueueNanos = this.defaultMaxQueueNanos;
            session.maxOpenOrders = this.defaultMaxOpenOrders;
            session.newPerSecond = this.defaultNewPerSecond;
            this.sessionsBySlot.put(slot, session);
        }
        return session;
    }

    /** The caps a session gets until the gateway says otherwise; 0 for none. */
    public void defaultCaps(final int maxOpenOrders, final long newPerSecond) {
        this.defaultMaxOpenOrders = maxOpenOrders;
        this.defaultNewPerSecond = newPerSecond;
    }

    /**
     * A session's own caps, as it asked for them: it may ask for less than the default, never more. 0 keeps the
     * default.
     */
    public void caps(final long slot, final int maxOpenOrders, final long newPerSecond) {
        final AdmissionSession session = session(slot);
        if (session == null) {
            return;
        }
        session.maxOpenOrders = (int) lower(this.defaultMaxOpenOrders, maxOpenOrders);
        session.newPerSecond = lower(this.defaultNewPerSecond, newPerSecond);
    }

    /** The tighter of two caps, either 0 for none. */
    private static long lower(final long cap, final long asked) {
        if (asked <= 0) {
            return cap;
        }
        return cap <= 0 ? asked : Math.min(cap, asked);
    }

    /** The session in {@code slot} if the queue holds one; never takes a place for it. */
    public AdmissionSession existing(final long slot) {
        return this.sessionsBySlot.get(slot);
    }

    /**
     * Introduces the session, or changes its terms; a term of zero leaves it as it was.
     *
     * @param maxQueueNanos how long its queued requests may wait
     */
    public void configure(final long slot, final long weight, final long maxQueueNanos) {
        final AdmissionSession session = session(slot);
        if (session == null) {
            return;
        }
        session.endWanted = false;
        session.fenced = false;
        if (weight > 0) {
            session.weight = weight;
        }
        if (maxQueueNanos > 0) {
            session.maxQueueNanos = maxQueueNanos;
        }
    }

    /** The session's orders are all being cancelled: its new orders are refused until it is configured again. */
    public void fence(final long slot) {
        final AdmissionSession session = session(slot);
        if (session != null) {
            session.fenced = true;
        }
    }

    public boolean fenced(final long slot) {
        final AdmissionSession session = this.sessionsBySlot.get(slot);
        return session != null && session.fenced;
    }

    /** The gateway is done with the session: forgotten now, or once nothing of it is queued. */
    public void ended(final long slot) {
        final AdmissionSession session = this.sessionsBySlot.get(slot);
        if (session == null) {
            return;
        }
        if (session.active) {
            session.endWanted = true;
        } else {
            forget(session);
        }
    }

    private void forget(final AdmissionSession session) {
        this.sessionsBySlot.remove(session.slot);
        session.reset();
        this.freeSessions[this.freeSessionCount++] = session.index;
    }

    /** The first session with something queued, to walk them all with {@link #nextQueued}; null if none has. */
    public AdmissionSession firstQueued() {
        return this.ring;
    }

    /**
     * The session after {@code session} among those with something queued; null once back at the first. For a caller
     * that budgets each session on its own, so serves whichever can go rather than the one charged least.
     */
    public AdmissionSession nextQueued(final AdmissionSession session) {
        final AdmissionSession next = session.next;
        return next == null || next == this.ring ? null : next;
    }

    // ----- choosing and charging -----

    /** The session with something queued that has been charged least; null if none has anything queued. */
    public AdmissionSession leastServed() {
        AdmissionSession least = this.ring;
        if (least == null) {
            return null;
        }
        for (AdmissionSession session = least.next; session != this.ring; session = session.next) {
            if (session.served - least.served < 0) {
                least = session;
            }
        }
        return least;
    }

    /** Charges the session for what it sent, {@code milliTokens} at its weight. */
    public void charge(final AdmissionSession session, final long milliTokens) {
        this.servedFrom = session.served;
        session.served += milliTokens / session.weight;
        traced(FairShareTrace.PICKED, session, milliTokens / session.weight);
    }

    /**
     * Makes up what an order in a batch was charged to what it cost once the batch went, unless its session has been
     * forgotten and its place reused since.
     */
    public void topUp(final AdmissionSession session, final long slot, final long milliTokens) {
        if (session.slot == slot) {
            session.served += milliTokens / session.weight;
            traced(FairShareTrace.TOPPED_UP, session, milliTokens / session.weight);
        }
    }

    // ----- sessions' lists -----

    public void enqueue(final E order, final int kind) {
        final AdmissionSession session = this.sessionsBySlot.get(order.sessionSlot());
        order.queuedIn = session;
        order.queuedKind = kind;
        if (kind == AdmissionEntry.SUBMIT) {
            this.queuedSubmits++;
        }
        order.queuedAtNanos = this.nanoClock.getAsLong();
        order.heldTold = false;
        order.nextQueued = AdmissionSession.NO_ORDER;
        if (kind == AdmissionEntry.AMEND) {
            order.prevQueued = session.amendTail;
            if (session.amendTail == AdmissionSession.NO_ORDER) {
                session.amendHead = order.index;
            } else {
                this.orders[session.amendTail].nextQueued = order.index;
            }
            session.amendTail = order.index;
        } else {
            order.prevQueued = session.newTail;
            if (session.newTail == AdmissionSession.NO_ORDER) {
                session.newHead = order.index;
            } else {
                this.orders[session.newTail].nextQueued = order.index;
            }
            session.newTail = order.index;
        }
        activate(session);
    }

    /**
     * Puts back a submit that went out in a batch the venue refused for another entry: first in its session's list,
     * keeping when it was queued, so it waits no longer in all than its session allows.
     *
     * @return false if its session is gone and the account has no room to introduce it again
     */
    public boolean requeueSubmit(final E order) {
        final AdmissionSession session = session(order.sessionSlot());
        if (session == null) {
            return false;
        }
        order.queuedIn = session;
        order.queuedKind = AdmissionEntry.SUBMIT;
        this.queuedSubmits++;
        order.prevQueued = AdmissionSession.NO_ORDER;
        order.nextQueued = session.newHead;
        if (session.newHead == AdmissionSession.NO_ORDER) {
            session.newTail = order.index;
        } else {
            this.orders[session.newHead].prevQueued = order.index;
        }
        session.newHead = order.index;
        activate(session);
        return true;
    }

    /** Takes the order out of its session's list, if it is in one; a session left with nothing queued leaves the ring. */
    public void unqueue(final E order) {
        final AdmissionSession session = order.queuedIn;
        if (session == null) {
            return;
        }
        final boolean amend = order.queuedKind == AdmissionEntry.AMEND;
        if (!amend) {
            this.queuedSubmits--;
        }
        if (order.prevQueued == AdmissionSession.NO_ORDER) {
            if (amend) {
                session.amendHead = order.nextQueued;
            } else {
                session.newHead = order.nextQueued;
            }
        } else {
            this.orders[order.prevQueued].nextQueued = order.nextQueued;
        }
        if (order.nextQueued == AdmissionSession.NO_ORDER) {
            if (amend) {
                session.amendTail = order.prevQueued;
            } else {
                session.newTail = order.prevQueued;
            }
        } else {
            this.orders[order.nextQueued].prevQueued = order.prevQueued;
        }
        order.queuedIn = null;
        order.queuedKind = AdmissionEntry.NONE;
        order.prevQueued = AdmissionSession.NO_ORDER;
        order.nextQueued = AdmissionSession.NO_ORDER;
        if (session.empty()) {
            deactivate(session);
        }
    }

    private void activate(final AdmissionSession session) {
        if (session.active) {
            return;
        }
        session.active = true;
        if (session.served - this.servedFrom < 0) {
            final boolean wasIdle = session.emptiedAtNanos == AdmissionSession.NEVER
                    || this.nanoClock.getAsLong() - session.emptiedAtNanos > IDLE_LEVEL_NANOS;
            final long floor = wasIdle ? this.servedFrom : this.servedFrom - this.maxCreditMilliTokens / session.weight;
            if (session.served - floor < 0) {
                session.served = floor;
            }
        }
        traced(FairShareTrace.ACTIVATED, session, this.servedFrom);
        if (this.ring == null) {
            session.prev = session;
            session.next = session;
            this.ring = session;
            return;
        }
        session.next = this.ring;
        session.prev = this.ring.prev;
        this.ring.prev.next = session;
        this.ring.prev = session;
    }

    private void deactivate(final AdmissionSession session) {
        if (!session.active) {
            return;
        }
        session.active = false;
        session.emptiedAtNanos = this.nanoClock.getAsLong();
        traced(FairShareTrace.EMPTIED, session, 0);
        if (session.next == session) {
            this.ring = null;
        } else {
            session.prev.next = session.next;
            session.next.prev = session.prev;
            if (this.ring == session) {
                this.ring = session.next;
            }
        }
        session.prev = null;
        session.next = null;
        if (session.endWanted) {
            forget(session);
        }
    }

    // ----- waiting too long -----

    /**
     * Hands each queued amend and new order that has waited longer than its session allows, or no longer stands, to
     * the expiry. Each list is in arrival order, so only its head can be due.
     */
    public void expireDue() {
        final long now = this.nanoClock.getAsLong();
        AdmissionSession session = this.ring;
        for (int visited = 0; visited < MAX_SESSIONS && session != null; visited++) {
            final AdmissionSession next = session.next;
            final boolean last = next == this.ring;
            expireHead(session, session.amendHead, now);
            expireHead(session, session.newHead, now);
            if (last || this.ring == null) {
                return;
            }
            session = next;
        }
    }

    private void expireHead(final AdmissionSession session, final int head, final long now) {
        int index = head;
        while (index != AdmissionSession.NO_ORDER) {
            final E order = this.orders[index];
            final int next = order.nextQueued;
            if (order.stillQueued(order.queuedKind) && now - order.queuedAtNanos <= session.maxQueueNanos) {
                return;
            }
            traced(FairShareTrace.EXPIRED, session, now - order.queuedAtNanos);
            this.expire.accept(order);
            index = next;
        }
    }

    // ----- for status, batching and post-mortems -----

    public boolean anyQueued() {
        return this.ring != null;
    }

    /** Submits queued in sessions' lists. */
    public int queuedSubmits() {
        return this.queuedSubmits;
    }

    /** Amends and new orders waiting for a connection or the budget. */
    public int queuedRequests() {
        int queued = 0;
        AdmissionSession session = this.ring;
        for (int i = 0; session != null && i < MAX_SESSIONS; i++) {
            queued += count(session.amendHead) + count(session.newHead);
            session = session.next;
            if (session == this.ring) {
                break;
            }
        }
        return queued;
    }

    private int count(final int head) {
        int count = 0;
        for (int index = head; index != AdmissionSession.NO_ORDER; index = this.orders[index].nextQueued) {
            count++;
        }
        return count;
    }

    public FairShareTrace trace(final int capacity) {
        this.trace = new FairShareTrace(capacity);
        return this.trace;
    }

    public void batchOpened() {
        if (this.trace != null) {
            this.trace.record(FairShareTrace.BATCH_OPENED, this.nanoClock.getAsLong(), 0, 0, 0, this.queuedSubmits);
        }
    }

    private void traced(final int kind, final AdmissionSession session, final long value) {
        if (this.trace != null) {
            this.trace.record(kind, this.nanoClock.getAsLong(), session.slot, session.weight, session.served, value);
        }
    }
}
