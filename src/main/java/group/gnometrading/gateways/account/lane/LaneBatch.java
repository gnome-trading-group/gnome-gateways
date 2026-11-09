package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitResult;

/** Several orders' submits, or cancels, sent as one request by an {@link AccountLane}. Pooled. */
final class LaneBatch {

    final int index;
    int kind;
    int count;
    int rule;
    // Each entry as the order was when it went in, so an answer for an order since ended is recognised.
    final long[] entries;
    final OrderContext[] ctxs;
    final byte[][] bodies;
    final int[] bodyLengths;
    final SubmitResult[] submitResults;
    final CancelResult[] cancelResults;
    // The session each entry was charged to, and what it was charged, in thousandths of a token: a share of a full
    // batch while it filled, made up to its share of the batch as sent.
    final LaneSession[] chargedTo;
    final long[] charged;

    LaneBatch(final int index, final int maxBatch) {
        this.index = index;
        this.entries = new long[maxBatch];
        this.ctxs = new OrderContext[maxBatch];
        this.bodies = new byte[maxBatch][];
        this.bodyLengths = new int[maxBatch];
        this.submitResults = new SubmitResult[maxBatch];
        this.cancelResults = new CancelResult[maxBatch];
        this.chargedTo = new LaneSession[maxBatch];
        this.charged = new long[maxBatch];
    }

    void reset() {
        for (int i = 0; i < this.count; i++) {
            this.ctxs[i] = null;
            this.bodies[i] = null;
            this.submitResults[i] = null;
            this.cancelResults[i] = null;
            this.chargedTo[i] = null;
            this.charged[i] = 0;
        }
        this.kind = LaneOrder.NONE;
        this.count = 0;
        this.rule = 0;
    }
}
