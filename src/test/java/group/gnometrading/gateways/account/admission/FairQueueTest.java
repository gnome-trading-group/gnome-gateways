package group.gnometrading.gateways.account.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The fair queue on its own, on a clock the test moves: what a session that comes and goes may be owed. */
class FairQueueTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long TOKEN = 1_000;
    // A second of a budget of 10 tokens a second.
    private static final long MAX_CREDIT = 10 * TOKEN;

    /** An entry that is only its session, and whose queued request always still stands. */
    private static final class Entry extends AdmissionEntry {
        long slot;

        Entry(final int index) {
            super(index);
        }

        @Override
        public long sessionSlot() {
            return this.slot;
        }

        @Override
        public boolean stillQueued(final int kind) {
            return true;
        }

        void reuse(final long slot) {
            resetQueueing();
            this.slot = slot;
        }
    }

    private final long[] nowNanos = {TimeUnit.SECONDS.toNanos(100)};
    private final Entry[] orders = new Entry[8];
    private final FairQueue<Entry> queue;

    FairQueueTest() {
        for (int i = 0; i < this.orders.length; i++) {
            this.orders[i] = new Entry(i);
        }
        this.queue = new FairQueue<>(
                this.orders, TimeUnit.SECONDS.toNanos(10), () -> this.nowNanos[0], this::drop, MAX_CREDIT);
    }

    private void drop(final Entry order) {
        this.queue.unqueue(order);
    }

    @Test
    void aSessionThatKeepsComingBackSoonIsOwedAtMostASecondOfTheBudget() {
        this.queue.configure(A, 1, 0);
        this.queue.configure(B, 1, 0);
        final Entry fromB = submit(1, B);
        // B sends seven tokens' worth for every one of A's, and A is never away long enough to come back level.
        for (int cycle = 0; cycle < 50; cycle++) {
            final Entry fromA = submit(0, A);
            serve(fromA);
            for (int i = 0; i < 7; i++) {
                this.nowNanos[0] += TimeUnit.MILLISECONDS.toNanos(70);
                serve(fromB);
            }
        }

        submit(0, A);

        final long owed = this.queue.session(B).served - this.queue.session(A).served;
        // The level is what B had been charged before its last token.
        assertTrue(
                owed <= MAX_CREDIT + TOKEN, "owed " + owed + " milli-tokens, a second of the budget behind the level");
        assertTrue(owed >= MAX_CREDIT, "and no less: " + owed);
    }

    @Test
    void aSessionBackSoonKeepsWhatItIsOwedWithinTheBound() {
        this.queue.configure(A, 1, 0);
        this.queue.configure(B, 1, 0);
        final Entry fromB = submit(1, B);
        final Entry fromA = submit(0, A);
        serve(fromA);
        for (int i = 0; i < 3; i++) {
            serve(fromB);
        }
        this.nowNanos[0] += TimeUnit.MILLISECONDS.toNanos(500);

        submit(0, A);

        assertSame(this.queue.session(A), this.queue.leastServed(), "its three tokens behind B are kept");
    }

    @Test
    void aWalkVisitsEverySessionWithSomethingQueuedOnce() {
        this.queue.configure(A, 1, 0);
        this.queue.configure(B, 1, 0);
        this.queue.configure(3, 1, 0);
        submit(0, A);
        submit(1, B);

        final java.util.List<Long> walked = new java.util.ArrayList<>();
        for (AdmissionSession s = this.queue.firstQueued(); s != null; s = this.queue.nextQueued(s)) {
            walked.add(s.slot());
        }

        assertEquals(java.util.List.of(A, B), walked, "the session with nothing queued is not on the walk");
    }

    @Test
    void aFencedSessionStaysFencedUntilItIsConfiguredAgain() {
        this.queue.configure(A, 1, 0);

        this.queue.fence(A);

        assertTrue(this.queue.fenced(A));
        assertFalse(this.queue.fenced(B), "another session isn't");
        this.queue.configure(A, 0, 0);
        assertFalse(this.queue.fenced(A));
    }

    @Test
    void aBatchsTopUpForASessionForgottenSinceChargesNobody() {
        this.queue.configure(A, 1, 0);
        final AdmissionSession place = this.queue.session(A);
        this.queue.ended(A);
        // Its place is reused by another session before the batch it was charged in goes.
        this.queue.configure(B, 1, 0);
        assertSame(place, this.queue.session(B));

        this.queue.topUp(place, A, 5 * TOKEN);

        assertTrue(this.queue.session(B).served == 0, "B wasn't charged for A's order");
    }

    @Test
    void aSubmitPutBackWhenTheAccountHasNoRoomForItsSessionIsNotQueued() {
        for (long slot = 100; slot < 100 + FairQueue.MAX_SESSIONS; slot++) {
            this.queue.configure(slot, 1, 0);
        }
        final Entry order = this.orders[0];
        order.reuse(A);

        assertFalse(this.queue.requeueSubmit(order));
        assertFalse(this.queue.anyQueued());
    }

    private Entry submit(final int index, final long slot) {
        final Entry order = this.orders[index];
        order.reuse(slot);
        this.queue.enqueue(order, AdmissionEntry.SUBMIT);
        return order;
    }

    /** Charges the order's session a token; the order stays queued unless it is the session's only one to go. */
    private void serve(final Entry order) {
        final AdmissionSession session = order.queuedIn;
        this.queue.charge(session, TOKEN);
        if (order.slot == A) {
            this.queue.unqueue(order);
        }
    }
}
