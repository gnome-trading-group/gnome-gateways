package group.gnometrading.gateways.feed.book;

import group.gnometrading.schemas.book.Side;

/**
 * A venue market's full book: every price level on both sides, kept from the venue's snapshots and updates. One
 * implementation serves every venue; what differs between venues is only how their messages map onto {@link #set} and
 * {@link #add}.
 */
public final class Book {

    private final PriceLevels bids;
    private final PriceLevels asks;

    public Book(final int levelsPerSide) {
        this.bids = new PriceLevels(levelsPerSide, true);
        this.asks = new PriceLevels(levelsPerSide, false);
    }

    public PriceLevels bids() {
        return bids;
    }

    public PriceLevels asks() {
        return asks;
    }

    public PriceLevels side(final Side side) {
        return side == Side.Bid ? bids : asks;
    }

    public void clear() {
        bids.clear();
        asks.clear();
    }

    /** The level becomes {@code size}; zero removes it. */
    public void set(final Side side, final long price, final long size, final long count) {
        side(side).set(price, size, count);
    }

    /** The level changes by {@code delta}; a level that reaches zero is removed. */
    public void add(final Side side, final long price, final long delta, final long count) {
        side(side).add(price, delta, count);
    }

    /** Whether a side ran out of room, so its deeper levels are missing. */
    public boolean overflowed() {
        return bids.overflowed() || asks.overflowed();
    }

    /** Whether the best bid is at or above the best ask, which a correct book never shows. */
    public boolean crossed() {
        return bids.depth() > 0 && asks.depth() > 0 && bids.price(0) >= asks.price(0);
    }

    public void copyFrom(final Book other) {
        bids.copyFrom(other.bids);
        asks.copyFrom(other.asks);
    }
}
