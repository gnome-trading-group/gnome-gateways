package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.PriceLevels;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A copy of a book's best levels for another thread to read: written by the market's reader after every change, read
 * by the probe, kept consistent with a sequence lock (odd while a write is in progress) rather than a lock, so the
 * reader never waits.
 */
public final class TopLevels {

    public static final int LEVELS = 5;

    private static final VarHandle VERSION;

    static {
        try {
            VERSION = MethodHandles.lookup().findVarHandle(TopLevels.class, "version", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Bids then asks: price and size per level.
    private final long[] prices = new long[LEVELS * 2];
    private final long[] sizes = new long[LEVELS * 2];
    private final int[] depths = new int[2];

    @SuppressWarnings("unused")
    private long version;

    /** Reader thread. */
    void update(final Book book) {
        final long start = (long) VERSION.getAcquire(this) + 1;
        VERSION.setRelease(this, start);
        VarHandle.storeStoreFence();
        copy(book.bids(), 0);
        copy(book.asks(), 1);
        VERSION.setRelease(this, start + 1);
    }

    private void copy(final PriceLevels levels, final int side) {
        final int depth = Math.min(LEVELS, levels.depth());
        depths[side] = depth;
        for (int i = 0; i < depth; i++) {
            prices[side * LEVELS + i] = levels.price(i);
            sizes[side * LEVELS + i] = levels.size(i);
        }
    }

    /**
     * Any thread: copies a consistent view into {@code into}, retrying while a write is under way. Prices and sizes are
     * laid out as in this class: bids at 0..4, asks at 5..9; depths bids then asks.
     */
    public void read(final long[] intoPrices, final long[] intoSizes, final int[] intoDepths) {
        while (true) {
            final long before = (long) VERSION.getAcquire(this);
            if ((before & 1) != 0) {
                Thread.onSpinWait();
                continue;
            }
            System.arraycopy(prices, 0, intoPrices, 0, prices.length);
            System.arraycopy(sizes, 0, intoSizes, 0, sizes.length);
            intoDepths[0] = depths[0];
            intoDepths[1] = depths[1];
            VarHandle.loadLoadFence();
            if ((long) VERSION.getAcquire(this) == before) {
                return;
            }
        }
    }
}
