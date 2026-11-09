package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.orders.OrderContext;
import java.nio.charset.StandardCharsets;

/** One order an {@link AccountLane} holds: its context, its prepared submit, and where it stands. Pooled. */
final class LaneOrder {

    // The generation's bits an entry keeps: with the slot index's 16 and a request kind's 3, a tag fits a long.
    static final int GENERATION_MASK = (1 << 29) - 1;
    static final int INDEX_BITS = 16;

    /** Requests an order can have on the wire, at most one at a time. */
    static final int NONE = 0;

    static final int SUBMIT = 1;
    static final int LOOKUP = 2;
    static final int CANCEL = 3;
    static final int AMEND = 4;

    /** Where its submit stands. */
    static final int QUEUED = 0;

    static final int RESOLVING = 1;
    static final int SETTLED = 2;

    final int index;
    // Advanced each time the slot is reused, so an answer or queue entry for the order before is recognised as stale.
    int generation;
    final OrderContext ctx = new OrderContext();
    final byte[] body;
    int bodyLength;
    Object market;
    boolean held;

    int submitState;
    int inFlight;
    int inFlightRule;
    boolean accepted;
    // Asked for while another request was on the wire or the submit was unsettled; served once it can be.
    boolean cancelWanted;
    boolean amendWanted;
    long amendPrice;
    long amendSize;
    boolean amendPostOnly;
    // The reader ended the order while a request was still on the wire: freed when that answer comes.
    boolean releaseWanted;
    // When it was queued in its session's list (its submit, or an amend); one that can't go out soon is refused rather
    // than sent late. Unlinked while in neither list.
    long queuedAtNanos;
    LaneSession queuedIn;
    int queuedKind;
    int prevQueued = LaneSession.NO_ORDER;
    int nextQueued = LaneSession.NO_ORDER;
    // Whether the budget has held its queued request yet, which is told once.
    boolean heldTold;
    // When its cancel was queued, for how long cancels wait.
    long cancelQueuedAtNanos;
    // A cancel the venue failed to answer, sent again: how many times so far, since when, and when next.
    int cancelRetries;
    long cancelFailedSinceNanos;
    long cancelRetryAtNanos;
    // When its submit's outcome was left unknown (0 while known), and when it is next looked for.
    long unknownSinceNanos;
    long nextSweepNanos;

    LaneOrder(final int index, final int maxBodyLength) {
        this.index = index;
        this.body = new byte[maxBodyLength];
    }

    /** A queue entry naming this order as it is now. */
    long entry() {
        return ((long) (this.generation & GENERATION_MASK) << INDEX_BITS) | this.index;
    }

    /** Whether a queued request still stands: a submit not yet sent, or an amend not overtaken by a cancel. */
    boolean stillQueued(final int kind) {
        if (this.inFlight != NONE) {
            return false;
        }
        return kind == SUBMIT ? this.submitState == QUEUED : this.amendWanted && !this.cancelWanted;
    }

    /** Where the order stands, for a post-mortem; allocates. */
    void describe(final StringBuilder text, final long nowNanos) {
        text.append(this.ctx.sessionSlot)
                .append('/')
                .append(this.ctx.clientOidCounter)
                .append(": venue id ")
                .append(new String(
                        this.ctx.exchangeOrderIdBytes, 0, this.ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII))
                .append(", submit ")
                .append(this.submitState)
                .append(this.accepted ? " accepted" : " not accepted")
                .append(", in flight ")
                .append(this.inFlight)
                .append(this.cancelWanted ? ", cancel wanted" : "")
                .append(this.releaseWanted ? ", release wanted" : "")
                .append(this.held ? ", held" : "")
                .append(", cancel retries ")
                .append(this.cancelRetries);
        if (this.cancelQueuedAtNanos != 0) {
            text.append(", cancel queued ")
                    .append((nowNanos - this.cancelQueuedAtNanos) / 1_000_000)
                    .append(" ms ago");
        }
        if (this.unknownSinceNanos != 0) {
            text.append(", unknown for ")
                    .append((nowNanos - this.unknownSinceNanos) / 1_000_000)
                    .append(" ms");
        }
    }

    void reset() {
        this.ctx.reset();
        this.bodyLength = 0;
        this.market = null;
        this.held = false;
        this.submitState = QUEUED;
        this.inFlight = NONE;
        this.inFlightRule = 0;
        this.accepted = false;
        this.cancelWanted = false;
        this.amendWanted = false;
        this.amendPrice = 0;
        this.amendSize = 0;
        this.amendPostOnly = false;
        this.releaseWanted = false;
        this.queuedAtNanos = 0;
        this.queuedIn = null;
        this.queuedKind = NONE;
        this.prevQueued = LaneSession.NO_ORDER;
        this.nextQueued = LaneSession.NO_ORDER;
        this.heldTold = false;
        this.cancelQueuedAtNanos = 0;
        this.cancelRetries = 0;
        this.cancelFailedSinceNanos = 0;
        this.cancelRetryAtNanos = 0;
        this.unknownSinceNanos = 0;
        this.nextSweepNanos = 0;
    }
}
