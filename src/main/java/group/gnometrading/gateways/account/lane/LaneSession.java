package group.gnometrading.gateways.account.lane;

/**
 * One session's place in an {@link AccountLane}'s fair share: its weight, how long its orders may wait, what it has
 * been charged for what it sent, and its queued amends and new orders, each a list linked through the orders
 * themselves. Pooled; a session with something queued is on the lane's ring of sessions to serve.
 */
final class LaneSession {

    static final int NO_ORDER = -1;
    static final long NEVER = Long.MIN_VALUE;
    private static final long MILLI = 1_000;
    // How long a thousandth of an order takes to refill at one order a second.
    private static final long NANOS_PER_MILLI_TOKEN = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

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

    // Its caps, 0 for none: orders it may hold at once, and new orders a second (a bucket a second deep, in
    // thousandths of an order).
    int maxOpenOrders;
    long newPerSecond;
    int openOrders;
    long newTokensMilli;
    long newRefilledAtNanos = NEVER;

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
        this.maxOpenOrders = 0;
        this.newPerSecond = 0;
        this.openOrders = 0;
        this.newTokensMilli = 0;
        this.newRefilledAtNanos = NEVER;
    }

    /**
     * Whether a new order is within the session's caps, taking it from the rate bucket if so. The bucket starts full
     * and refills continuously, so a session may send a second's worth at once and then its rate.
     */
    boolean admitNew(final long nowNanos) {
        if (this.maxOpenOrders > 0 && this.openOrders >= this.maxOpenOrders) {
            return false;
        }
        if (this.newPerSecond <= 0) {
            return true;
        }
        final long full = this.newPerSecond * MILLI;
        if (this.newRefilledAtNanos == NEVER) {
            this.newTokensMilli = full;
            this.newRefilledAtNanos = nowNanos;
        } else {
            // The bucket is a second deep, so more than a second away refills it whatever the rate; capping the
            // elapsed time there also keeps elapsed * rate from overflowing.
            final long elapsed = Math.min(nowNanos - this.newRefilledAtNanos, NANOS_PER_SECOND);
            final long refill = elapsed * this.newPerSecond / NANOS_PER_MILLI_TOKEN;
            if (this.newTokensMilli + refill >= full) {
                this.newTokensMilli = full;
                this.newRefilledAtNanos = nowNanos;
            } else if (refill > 0) {
                // Only the time those thousandths took is used up: rounding the rest away on every call would starve
                // a session that asks more often than a thousandth refills.
                this.newTokensMilli += refill;
                this.newRefilledAtNanos += refill * NANOS_PER_MILLI_TOKEN / this.newPerSecond;
            }
        }
        if (this.newTokensMilli < MILLI) {
            return false;
        }
        this.newTokensMilli -= MILLI;
        return true;
    }
}
