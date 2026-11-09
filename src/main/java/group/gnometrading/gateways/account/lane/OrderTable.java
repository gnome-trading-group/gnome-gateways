package group.gnometrading.gateways.account.lane;

/**
 * An account's working orders by session slot and OMS counter. Every session's OMS counts from 1, so the counter alone
 * would mix sessions up; the pair is unique. Open addressing over parallel arrays with linear probing and
 * backward-shift deletion: no allocation, no tombstones, and a fixed capacity sized for the most orders an account
 * may have working (at most half full, so probes stay short). One owner thread.
 *
 * @param <V> what is held for each order
 */
public final class OrderTable<V> {

    private final long[] slots;
    private final long[] counters;
    private final V[] values;
    private final int mask;
    private final int maxSize;
    private int size;

    /** @param maxSize the most orders held at once */
    public OrderTable(final int maxSize) {
        final int capacity = Integer.highestOneBit(Math.max(2, maxSize) * 2 - 1) << 1;
        this.slots = new long[capacity];
        this.counters = new long[capacity];
        @SuppressWarnings("unchecked")
        final V[] values = (V[]) new Object[capacity];
        this.values = values;
        this.mask = capacity - 1;
        this.maxSize = maxSize;
    }

    public int size() {
        return this.size;
    }

    public boolean isFull() {
        return this.size >= this.maxSize;
    }

    public V get(final long slot, final long counter) {
        int index = index(slot, counter);
        V value;
        while ((value = this.values[index]) != null) {
            if (this.slots[index] == slot && this.counters[index] == counter) {
                return value;
            }
            index = (index + 1) & this.mask;
        }
        return null;
    }

    /**
     * Holds {@code value} for the order {@code counter} of the session in {@code slot}.
     *
     * @return false if the table is full or already holds that order
     */
    public boolean put(final long slot, final long counter, final V value) {
        if (this.size >= this.maxSize) {
            return false;
        }
        int index = index(slot, counter);
        while (this.values[index] != null) {
            if (this.slots[index] == slot && this.counters[index] == counter) {
                return false;
            }
            index = (index + 1) & this.mask;
        }
        this.slots[index] = slot;
        this.counters[index] = counter;
        this.values[index] = value;
        this.size++;
        return true;
    }

    /** Removes and returns the order, or null if it isn't held. */
    public V remove(final long slot, final long counter) {
        int index = index(slot, counter);
        V value;
        while ((value = this.values[index]) != null) {
            if (this.slots[index] == slot && this.counters[index] == counter) {
                this.values[index] = null;
                this.size--;
                shiftBack(index);
                return value;
            }
            index = (index + 1) & this.mask;
        }
        return null;
    }

    /** The order at {@code index} of the backing array, or null; for walking every order on rare paths. */
    public V at(final int index) {
        return this.values[index];
    }

    /** The backing array's length, the bound for {@link #at}. */
    public int capacity() {
        return this.values.length;
    }

    /** Closes the gap left at {@code hole} by moving back entries whose probe sequence passes over it. */
    private void shiftBack(final int hole) {
        int gap = hole;
        int index = (gap + 1) & this.mask;
        V value;
        while ((value = this.values[index]) != null) {
            final int home = index(this.slots[index], this.counters[index]);
            // Move it if its home is not cyclically within (gap, index].
            if (((index - home) & this.mask) >= ((index - gap) & this.mask)) {
                this.slots[gap] = this.slots[index];
                this.counters[gap] = this.counters[index];
                this.values[gap] = value;
                this.values[index] = null;
                gap = index;
            }
            index = (index + 1) & this.mask;
        }
    }

    private int index(final long slot, final long counter) {
        long hash = slot * 0x9E3779B97F4A7C15L + counter;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return (int) hash & this.mask;
    }
}
