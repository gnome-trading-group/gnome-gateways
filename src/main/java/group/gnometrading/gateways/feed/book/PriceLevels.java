package group.gnometrading.gateways.feed.book;

import org.agrona.concurrent.UnsafeBuffer;

/**
 * One side of a book: every price level, best first, in a buffer reused so updating it allocates nothing. A venue that
 * sends its whole book can go far deeper than usual (Lighter: 3,000+ levels), so the buffer doubles when a level
 * doesn't fit, allocating only when a book is deeper than it has ever been. Past {@link #MAX_LEVELS} a level is not
 * kept; {@link #overflowed} says whether that happened since the last clear, since a book that dropped levels can't be
 * trusted once the levels above them go.
 */
public final class PriceLevels {

    public static final int MAX_LEVELS = 1 << 16;
    private static final int ENTRY = 24; // price, size, count

    private UnsafeBuffer levels;
    private int capacity;
    private final boolean descending;
    private int depth;
    private boolean overflowed;

    PriceLevels(final int capacity, final boolean descending) {
        if (capacity < 1 || capacity > MAX_LEVELS) {
            throw new IllegalArgumentException("A side starts with room for 1 to " + MAX_LEVELS + " levels");
        }
        this.capacity = capacity;
        this.descending = descending;
        this.levels = new UnsafeBuffer(new byte[capacity * ENTRY]);
    }

    public int capacity() {
        return capacity;
    }

    public int depth() {
        return depth;
    }

    public boolean overflowed() {
        return overflowed;
    }

    public long price(final int index) {
        return levels.getLong(index * ENTRY);
    }

    public long size(final int index) {
        return levels.getLong(index * ENTRY + 8);
    }

    public long count(final int index) {
        return levels.getLong(index * ENTRY + 16);
    }

    /** The best price, or {@code empty} when the side has no levels. */
    public long best(final long empty) {
        return depth == 0 ? empty : price(0);
    }

    void clear() {
        depth = 0;
        overflowed = false;
    }

    /** The level at {@code price} becomes {@code size}; a size of zero removes it. */
    void set(final long price, final long size, final long count) {
        final int found = search(price);
        if (found >= 0) {
            if (size <= 0) {
                removeAt(found);
            } else {
                putAt(found, price, size, count);
            }
        } else if (size > 0) {
            insertAt(~found, price, size, count);
        }
    }

    /** The level at {@code price} changes by {@code delta}; one that reaches zero or below is removed. */
    void add(final long price, final long delta, final long count) {
        final int found = search(price);
        final long size = (found >= 0 ? size(found) : 0) + delta;
        if (found >= 0) {
            if (size <= 0) {
                removeAt(found);
            } else {
                putAt(found, price, size, count);
            }
        } else if (size > 0) {
            insertAt(~found, price, size, count);
        }
    }

    /** The index of {@code price}, or {@code ~insertionPoint} if it has no level, as {@code Arrays.binarySearch}. */
    private int search(final long price) {
        int low = 0;
        int high = depth - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final long midPrice = price(mid);
            if (midPrice == price) {
                return mid;
            }
            if (descending ? price > midPrice : price < midPrice) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return ~low;
    }

    private void insertAt(final int index, final long price, final long size, final long count) {
        if (depth == capacity && !grow(capacity * 2)) {
            // Full at the largest size kept: the worst level falls off, or this one if it would be the worst.
            overflowed = true;
            if (index >= capacity) {
                return;
            }
        } else {
            depth++;
        }
        levels.putBytes((index + 1) * ENTRY, levels, index * ENTRY, (depth - 1 - index) * ENTRY);
        putAt(index, price, size, count);
    }

    private void removeAt(final int index) {
        levels.putBytes(index * ENTRY, levels, (index + 1) * ENTRY, (depth - 1 - index) * ENTRY);
        depth--;
    }

    private void putAt(final int index, final long price, final long size, final long count) {
        final int offset = index * ENTRY;
        levels.putLong(offset, price);
        levels.putLong(offset + 8, size);
        levels.putLong(offset + 16, count);
    }

    /** Room for {@code wanted} levels, or as many as allowed; false if it was already at the most. */
    private boolean grow(final int wanted) {
        if (capacity >= MAX_LEVELS) {
            return false;
        }
        final int grown = Math.min(Math.max(wanted, capacity), MAX_LEVELS);
        final UnsafeBuffer larger = new UnsafeBuffer(new byte[grown * ENTRY]);
        larger.putBytes(0, levels, 0, depth * ENTRY);
        levels = larger;
        capacity = grown;
        return true;
    }

    void copyFrom(final PriceLevels other) {
        if (other.depth > capacity) {
            grow(other.depth);
        }
        depth = Math.min(other.depth, capacity);
        overflowed = other.overflowed || other.depth > capacity;
        levels.putBytes(0, other.levels, 0, depth * ENTRY);
    }
}
