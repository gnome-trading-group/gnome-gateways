package group.gnometrading.gateways.account.lane;

/**
 * One session's place in an {@link AccountLane}'s fair share: its weight, how long its orders may wait, what it has
 * been charged for what it sent, and its queued amends and new orders, each a list linked through the orders
 * themselves. Pooled; a session with something queued is on the lane's ring of sessions to serve.
 */
final class LaneSession {

    static final int NO_ORDER = -1;
    static final long NEVER = Long.MIN_VALUE;

    final int index;
    long slot;
    long weight;
    long maxQueueNanos;
    // What it has been charged, in thousandths of a token over its weight: the session with something queued that has
    // been charged least sends next. Compared by difference, as it only ever grows.
    long served;

    int amendHead = NO_ORDER;
    int amendTail = NO_ORDER;
    int newHead = NO_ORDER;
    int newTail = NO_ORDER;

    // The ring of sessions with something queued.
    boolean active;
    LaneSession prev;
    LaneSession next;
    // The gateway is done with the session: forgotten once nothing of it is queued.
    boolean endWanted;
    // When it last had nothing queued; NEVER until it first has. How long it was away decides whether it comes back
    // level with the rest or keeps its place.
    long emptiedAtNanos = NEVER;
    // Its orders are all being cancelled: its new orders are refused until the gateway introduces it again.
    boolean fenced;

    LaneSession(final int index) {
        this.index = index;
    }

    boolean empty() {
        return this.amendHead == NO_ORDER && this.newHead == NO_ORDER;
    }

    /** Amends first: they change quotes already resting, which a new order of the same session can wait behind. */
    int head() {
        return this.amendHead != NO_ORDER ? this.amendHead : this.newHead;
    }

    void reset() {
        this.slot = 0;
        this.weight = 0;
        this.maxQueueNanos = 0;
        this.served = 0;
        this.amendHead = NO_ORDER;
        this.amendTail = NO_ORDER;
        this.newHead = NO_ORDER;
        this.newTail = NO_ORDER;
        this.active = false;
        this.prev = null;
        this.next = null;
        this.endWanted = false;
        this.emptiedAtNanos = NEVER;
        this.fenced = false;
    }
}
