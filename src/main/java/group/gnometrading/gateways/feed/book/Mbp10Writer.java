package group.gnometrading.gateways.feed.book;

import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;

/**
 * Writes a book's top ten levels per side, as a projection sees them, into an MBP-10 record. One instance per thread
 * that publishes: it fills its own arrays, so it allocates nothing.
 */
public final class Mbp10Writer {

    public static final int LEVELS = 10;
    private static final int BID = 0;
    private static final int ASK = LEVELS;

    private final long[] px = new long[LEVELS * 2];
    private final long[] sz = new long[LEVELS * 2];
    private final long[] ct = new long[LEVELS * 2];

    public void write(final Book book, final Projection projection, final Mbp10Encoder encoder) {
        fill(projection.source(book, Side.Bid), projection, BID);
        fill(projection.source(book, Side.Ask), projection, ASK);
        put(encoder);
    }

    private void fill(final PriceLevels levels, final Projection projection, final int offset) {
        final long nullPrice = Mbp10Encoder.bidPrice0NullValue();
        for (int i = 0; i < LEVELS; i++) {
            if (i < levels.depth()) {
                px[offset + i] = projection.price(levels.price(i), nullPrice);
                sz[offset + i] = levels.size(i);
                ct[offset + i] = levels.count(i);
            } else {
                px[offset + i] = nullPrice;
                sz[offset + i] = Mbp10Encoder.bidSize0NullValue();
                ct[offset + i] = Mbp10Encoder.bidCount0NullValue();
            }
        }
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private void put(final Mbp10Encoder encoder) {
        encoder.bidPrice0(px[BID + 0]);
        encoder.bidSize0(sz[BID + 0]);
        encoder.bidCount0(ct[BID + 0]);
        encoder.bidPrice1(px[BID + 1]);
        encoder.bidSize1(sz[BID + 1]);
        encoder.bidCount1(ct[BID + 1]);
        encoder.bidPrice2(px[BID + 2]);
        encoder.bidSize2(sz[BID + 2]);
        encoder.bidCount2(ct[BID + 2]);
        encoder.bidPrice3(px[BID + 3]);
        encoder.bidSize3(sz[BID + 3]);
        encoder.bidCount3(ct[BID + 3]);
        encoder.bidPrice4(px[BID + 4]);
        encoder.bidSize4(sz[BID + 4]);
        encoder.bidCount4(ct[BID + 4]);
        encoder.bidPrice5(px[BID + 5]);
        encoder.bidSize5(sz[BID + 5]);
        encoder.bidCount5(ct[BID + 5]);
        encoder.bidPrice6(px[BID + 6]);
        encoder.bidSize6(sz[BID + 6]);
        encoder.bidCount6(ct[BID + 6]);
        encoder.bidPrice7(px[BID + 7]);
        encoder.bidSize7(sz[BID + 7]);
        encoder.bidCount7(ct[BID + 7]);
        encoder.bidPrice8(px[BID + 8]);
        encoder.bidSize8(sz[BID + 8]);
        encoder.bidCount8(ct[BID + 8]);
        encoder.bidPrice9(px[BID + 9]);
        encoder.bidSize9(sz[BID + 9]);
        encoder.bidCount9(ct[BID + 9]);
        encoder.askPrice0(px[ASK + 0]);
        encoder.askSize0(sz[ASK + 0]);
        encoder.askCount0(ct[ASK + 0]);
        encoder.askPrice1(px[ASK + 1]);
        encoder.askSize1(sz[ASK + 1]);
        encoder.askCount1(ct[ASK + 1]);
        encoder.askPrice2(px[ASK + 2]);
        encoder.askSize2(sz[ASK + 2]);
        encoder.askCount2(ct[ASK + 2]);
        encoder.askPrice3(px[ASK + 3]);
        encoder.askSize3(sz[ASK + 3]);
        encoder.askCount3(ct[ASK + 3]);
        encoder.askPrice4(px[ASK + 4]);
        encoder.askSize4(sz[ASK + 4]);
        encoder.askCount4(ct[ASK + 4]);
        encoder.askPrice5(px[ASK + 5]);
        encoder.askSize5(sz[ASK + 5]);
        encoder.askCount5(ct[ASK + 5]);
        encoder.askPrice6(px[ASK + 6]);
        encoder.askSize6(sz[ASK + 6]);
        encoder.askCount6(ct[ASK + 6]);
        encoder.askPrice7(px[ASK + 7]);
        encoder.askSize7(sz[ASK + 7]);
        encoder.askCount7(ct[ASK + 7]);
        encoder.askPrice8(px[ASK + 8]);
        encoder.askSize8(sz[ASK + 8]);
        encoder.askCount8(ct[ASK + 8]);
        encoder.askPrice9(px[ASK + 9]);
        encoder.askSize9(sz[ASK + 9]);
        encoder.askCount9(ct[ASK + 9]);
    }
}
