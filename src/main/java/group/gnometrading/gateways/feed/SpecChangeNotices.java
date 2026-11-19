package group.gnometrading.gateways.feed;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Listings a venue's feed said changed their trading terms (a new tick), from feed readers to a side thread that
 * asks the registry to read the venue again. A reader never blocks or allocates here: the queue is fixed, and a
 * notice that doesn't fit is dropped and counted, since the registry's regular pass reads every listing anyway, just
 * later and dated then.
 *
 * <p>Any number of readers offer, since a source's connections each have their own and a roll runs two at once; one
 * side thread drains.
 */
public final class SpecChangeNotices {

    /** The longest exchange security id a notice carries. */
    public static final int MAX_ID_BYTES = 192;

    /** Takes a drained notice. */
    @FunctionalInterface
    public interface Sink {
        void onNotice(String exchangeSecurityId, long newTick, long venueNanos);
    }

    private final int capacity;
    private final int mask;
    private final byte[][] ids;
    private final int[] idLengths;
    private final long[] newTicks;
    private final long[] venueNanos;
    // A slot is free to the offer at position p when its sequence is p, and written for the drain when it is p + 1.
    private final AtomicLongArray sequences;
    private final AtomicLong tail = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private long head;

    /** @param capacity a power of two */
    public SpecChangeNotices(final int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.ids = new byte[capacity][MAX_ID_BYTES];
        this.idLengths = new int[capacity];
        this.newTicks = new long[capacity];
        this.venueNanos = new long[capacity];
        this.sequences = new AtomicLongArray(capacity);
        for (int i = 0; i < capacity; i++) {
            this.sequences.set(i, i);
        }
    }

    /**
     * Queues a notice whose exchange security id is the first {@code idLength} bytes of {@code id}, ASCII. False when
     * it was dropped: the queue full, or the id too long.
     */
    public boolean offer(final byte[] id, final int idLength, final long newTick, final long venueNanos) {
        if (idLength <= 0 || idLength > MAX_ID_BYTES) {
            this.dropped.incrementAndGet();
            return false;
        }
        long at = this.tail.get();
        while (true) {
            final int slot = (int) (at & this.mask);
            final long sequence = this.sequences.getAcquire(slot);
            if (sequence == at) {
                if (this.tail.compareAndSet(at, at + 1)) {
                    System.arraycopy(id, 0, this.ids[slot], 0, idLength);
                    this.idLengths[slot] = idLength;
                    this.newTicks[slot] = newTick;
                    this.venueNanos[slot] = venueNanos;
                    this.sequences.setRelease(slot, at + 1);
                    return true;
                }
                at = this.tail.get();
            } else if (sequence < at) {
                this.dropped.incrementAndGet();
                return false;
            } else {
                at = this.tail.get();
            }
        }
    }

    /**
     * Hands every written notice to {@code sink}, oldest first, and returns how many; one still being written ends the
     * drain, and the next takes it. Allocates.
     */
    public int drain(final Sink sink) {
        int drained = 0;
        while (true) {
            final long at = this.head;
            final int slot = (int) (at & this.mask);
            if (this.sequences.getAcquire(slot) != at + 1) {
                return drained;
            }
            final String id = new String(this.ids[slot], 0, this.idLengths[slot], StandardCharsets.US_ASCII);
            final long newTick = this.newTicks[slot];
            final long nanos = this.venueNanos[slot];
            this.head = at + 1;
            this.sequences.setRelease(slot, at + this.capacity);
            drained++;
            sink.onNotice(id, newTick, nanos);
        }
    }

    /** Notices dropped since the start. */
    public long dropped() {
        return this.dropped.get();
    }
}
