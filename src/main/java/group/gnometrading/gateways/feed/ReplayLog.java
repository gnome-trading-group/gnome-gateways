package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.Mbp10Writer;
import group.gnometrading.gateways.feed.book.PriceLevels;
import group.gnometrading.schemas.book.Side;

/**
 * A roll's stand-in's latest records, books and trades in the order it took them, each with its place in the venue's
 * sequence: what the market it stands in for publishes once it moves over, if its old connection never did. Books are
 * kept as the records carry them, ten levels a side. Fixed in size, the oldest overwritten first; written by the
 * replacement's reader, read by its supervisor with the reader paused.
 */
final class ReplayLog {

    private static final int LEVELS = Mbp10Writer.LEVELS;
    private static final byte BOOK = 1;
    private static final byte TRADE = 2;

    private final int capacity;
    private final byte[] kinds;
    private final long[] times;
    private final int[] indexes;
    private final long[] receives;
    private final long[] sequences;
    private final boolean[] snapshots;
    private final long[] prices;
    private final long[] sizes;
    private final Side[] sides;
    private final int[] bidDepths;
    private final int[] askDepths;
    // LEVELS bids then LEVELS asks per record.
    private final long[] levelPrices;
    private final long[] levelSizes;
    private final long[] levelCounts;
    private long written;

    ReplayLog(final int capacity) {
        this.capacity = capacity;
        kinds = new byte[capacity];
        times = new long[capacity];
        indexes = new int[capacity];
        receives = new long[capacity];
        sequences = new long[capacity];
        snapshots = new boolean[capacity];
        prices = new long[capacity];
        sizes = new long[capacity];
        sides = new Side[capacity];
        bidDepths = new int[capacity];
        askDepths = new int[capacity];
        levelPrices = new long[capacity * 2 * LEVELS];
        levelSizes = new long[capacity * 2 * LEVELS];
        levelCounts = new long[capacity * 2 * LEVELS];
    }

    void book(
            final long time,
            final int index,
            final Book book,
            final long receiveNanos,
            final long sequence,
            final boolean snapshot) {
        final int slot = next(BOOK, time, index, receiveNanos);
        sequences[slot] = sequence;
        snapshots[slot] = snapshot;
        bidDepths[slot] = keep(book.bids(), slot * 2 * LEVELS);
        askDepths[slot] = keep(book.asks(), slot * 2 * LEVELS + LEVELS);
    }

    void trade(
            final long time,
            final int index,
            final long price,
            final long size,
            final Side aggressor,
            final long receiveNanos) {
        final int slot = next(TRADE, time, index, receiveNanos);
        prices[slot] = price;
        sizes[slot] = size;
        sides[slot] = aggressor;
    }

    private int next(final byte kind, final long time, final int index, final long receiveNanos) {
        final int slot = (int) (written++ % capacity);
        kinds[slot] = kind;
        times[slot] = time;
        indexes[slot] = index;
        receives[slot] = receiveNanos;
        return slot;
    }

    private int keep(final PriceLevels side, final int offset) {
        final int depth = Math.min(side.depth(), LEVELS);
        for (int i = 0; i < depth; i++) {
            levelPrices[offset + i] = side.price(i);
            levelSizes[offset + i] = side.size(i);
            levelCounts[offset + i] = side.count(i);
        }
        return depth;
    }

    /** How many it holds. */
    int held() {
        return (int) Math.min(written, capacity);
    }

    /** Whether records were overwritten, so its oldest isn't the first it took. */
    boolean wrapped() {
        return written > capacity;
    }

    /** The slot of the {@code nth} record it holds, oldest first. */
    int slot(final int nth) {
        return (int) ((written - held() + nth) % capacity);
    }

    boolean isBook(final int slot) {
        return kinds[slot] == BOOK;
    }

    long time(final int slot) {
        return times[slot];
    }

    int index(final int slot) {
        return indexes[slot];
    }

    long receive(final int slot) {
        return receives[slot];
    }

    long sequence(final int slot) {
        return sequences[slot];
    }

    boolean snapshot(final int slot) {
        return snapshots[slot];
    }

    long tradePrice(final int slot) {
        return prices[slot];
    }

    long tradeSize(final int slot) {
        return sizes[slot];
    }

    Side tradeSide(final int slot) {
        return sides[slot];
    }

    /** Puts the slot's book, as its record carried it, into {@code into}. */
    void bookInto(final int slot, final Book into) {
        into.clear();
        final int bids = slot * 2 * LEVELS;
        for (int i = 0; i < bidDepths[slot]; i++) {
            into.set(Side.Bid, levelPrices[bids + i], levelSizes[bids + i], levelCounts[bids + i]);
        }
        final int asks = bids + LEVELS;
        for (int i = 0; i < askDepths[slot]; i++) {
            into.set(Side.Ask, levelPrices[asks + i], levelSizes[asks + i], levelCounts[asks + i]);
        }
    }
}
