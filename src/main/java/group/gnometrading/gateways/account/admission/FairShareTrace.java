package group.gnometrading.gateways.account.admission;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every step of an account's fair share ({@link FairQueue}), for a post-mortem of how it divided a saturated budget: sessions
 * starting and stopping to have something queued, each order picked and what it was charged, a batch's charges made
 * up when it is sent, and orders refused for waiting. Recorded on the lane's thread into arrays sized up front, so it
 * allocates nothing; once full it records no more. Written out once the lane is quiet.
 */
public final class FairShareTrace {

    static final int ACTIVATED = 0;
    static final int EMPTIED = 1;
    static final int PICKED = 2;
    static final int TOPPED_UP = 3;
    static final int EXPIRED = 4;
    static final int BATCH_OPENED = 5;

    private static final String[] NAMES = {"activated", "emptied", "picked", "topped_up", "expired", "batch_opened"};

    private final long[] atNanos;
    private final int[] kinds;
    private final long[] slots;
    private final long[] weights;
    private final long[] served;
    private final long[] values;
    private int count;

    FairShareTrace(final int capacity) {
        this.atNanos = new long[capacity];
        this.kinds = new int[capacity];
        this.slots = new long[capacity];
        this.weights = new long[capacity];
        this.served = new long[capacity];
        this.values = new long[capacity];
    }

    /**
     * @param served the session's charge after the step
     * @param value what the step adds: the level it was raised to on activation, the charge of a pick or top-up, how
     *     long an expired order waited, how many submits were queued when a batch opened
     */
    void record(
            final int kind,
            final long nowNanos,
            final long slot,
            final long weight,
            final long served,
            final long value) {
        final int i = this.count;
        if (i == this.atNanos.length) {
            return;
        }
        this.atNanos[i] = nowNanos;
        this.kinds[i] = kind;
        this.slots[i] = slot;
        this.weights[i] = weight;
        this.served[i] = served;
        this.values[i] = value;
        this.count = i + 1;
    }

    public int size() {
        return this.count;
    }

    /** Tab-separated, one step a line, times from the first step: {@code micros kind slot weight served value}. */
    public void writeTo(final Path file) throws IOException {
        final long start = this.count == 0 ? 0 : this.atNanos[0];
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("micros\tkind\tslot\tweight\tserved\tvalue");
            for (int i = 0; i < this.count; i++) {
                out.print((this.atNanos[i] - start) / 1_000);
                out.print('\t');
                out.print(NAMES[this.kinds[i]]);
                out.print('\t');
                out.print(this.slots[i]);
                out.print('\t');
                out.print(this.weights[i]);
                out.print('\t');
                out.print(this.served[i]);
                out.print('\t');
                out.println(this.values[i]);
            }
        }
    }
}
