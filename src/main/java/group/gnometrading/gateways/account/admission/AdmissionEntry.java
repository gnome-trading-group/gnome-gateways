package group.gnometrading.gateways.account.admission;

/**
 * Something a {@link FairQueue} queues for a session: an order's submit or amend, waiting for the account's budget.
 * Its queue links live here, so the queue allocates nothing; the holder adds what its requests need. Pooled.
 */
public abstract class AdmissionEntry {

    /** What is queued for it, at most one at a time: nothing, its submit, or an amend. */
    public static final int NONE = 0;

    public static final int SUBMIT = 1;
    public static final int AMEND = 4;

    static final int NO_ENTRY = -1;

    /** Its place in the array the queue was built over. */
    public final int index;

    // When it was queued in its session's list; one that can't go out soon is refused rather than sent late.
    long queuedAtNanos;
    AdmissionSession queuedIn;
    int queuedKind = NONE;
    int prevQueued = NO_ENTRY;
    int nextQueued = NO_ENTRY;
    // Whether the budget has held its queued request yet, which is told once.
    boolean heldTold;

    protected AdmissionEntry(final int index) {
        this.index = index;
    }

    /** The session it is queued for. */
    public abstract long sessionSlot();

    /** Whether a request of {@code kind} queued for it still stands; one that doesn't is dropped when it comes due. */
    public abstract boolean stillQueued(int kind);

    public final boolean queued() {
        return this.queuedIn != null;
    }

    public final int queuedKind() {
        return this.queuedKind;
    }

    public final long queuedAtNanos() {
        return this.queuedAtNanos;
    }

    public final boolean heldTold() {
        return this.heldTold;
    }

    public final void markHeldTold() {
        this.heldTold = true;
    }

    /** Back to queued for nothing; its holder calls this when the entry is reused. */
    protected final void resetQueueing() {
        this.queuedAtNanos = 0;
        this.queuedIn = null;
        this.queuedKind = NONE;
        this.prevQueued = NO_ENTRY;
        this.nextQueued = NO_ENTRY;
        this.heldTold = false;
    }
}
