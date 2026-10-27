package group.gnometrading.gateways.feed.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import org.junit.jupiter.api.Test;

class BookTest {

    private static long px(final double dollars) {
        return Math.round(dollars * Statics.PRICE_SCALING_FACTOR);
    }

    @Test
    void bidsAreBestFirstHighestPriceFirst() {
        Book book = new Book(8);
        book.set(Side.Bid, px(0.40), 1, 1);
        book.set(Side.Bid, px(0.60), 2, 1);
        book.set(Side.Bid, px(0.50), 3, 1);

        assertEquals(3, book.bids().depth());
        assertEquals(px(0.60), book.bids().price(0));
        assertEquals(px(0.50), book.bids().price(1));
        assertEquals(px(0.40), book.bids().price(2));
        assertEquals(3, book.bids().size(1));
    }

    @Test
    void asksAreBestFirstLowestPriceFirst() {
        Book book = new Book(8);
        book.set(Side.Ask, px(0.70), 1, 1);
        book.set(Side.Ask, px(0.55), 2, 1);
        book.set(Side.Ask, px(0.62), 3, 1);

        assertEquals(px(0.55), book.asks().price(0));
        assertEquals(px(0.62), book.asks().price(1));
        assertEquals(px(0.70), book.asks().price(2));
    }

    @Test
    void settingZeroRemovesALevelAndSettingAgainReplacesIt() {
        Book book = new Book(8);
        book.set(Side.Bid, px(0.40), 1, 1);
        book.set(Side.Bid, px(0.50), 2, 1);
        book.set(Side.Bid, px(0.45), 3, 1);

        book.set(Side.Bid, px(0.45), 0, 0);
        book.set(Side.Bid, px(0.50), 9, 2);

        assertEquals(2, book.bids().depth());
        assertEquals(px(0.50), book.bids().price(0));
        assertEquals(9, book.bids().size(0));
        assertEquals(2, book.bids().count(0));
        assertEquals(px(0.40), book.bids().price(1));
        book.set(Side.Bid, px(0.99), 0, 0);
        assertEquals(2, book.bids().depth(), "removing a level that isn't there changes nothing");
    }

    @Test
    void deltasAccumulateAndALevelThatReachesZeroGoes() {
        Book book = new Book(8);
        book.add(Side.Ask, px(0.30), 10, 1);
        book.add(Side.Ask, px(0.30), 5, 1);
        assertEquals(15, book.asks().size(0));

        book.add(Side.Ask, px(0.30), -20, 1);
        assertEquals(0, book.asks().depth());

        book.add(Side.Ask, px(0.30), -5, 1);
        assertEquals(0, book.asks().depth(), "a negative delta on no level adds nothing");
    }

    @Test
    void aSideGrowsPastTheDepthItStartedWith() {
        Book book = new Book(3);
        for (int cents = 10; cents <= 50; cents += 10) {
            book.set(Side.Bid, px(cents / 100.0), cents, 1);
        }

        assertEquals(5, book.bids().depth());
        assertEquals(px(0.50), book.bids().price(0));
        assertEquals(px(0.10), book.bids().price(4));
        assertFalse(book.overflowed());
    }

    @Test
    void aSideAtTheMostItHoldsKeepsTheBestLevelsAndSaysItOverflowed() {
        Book book = new Book(4);
        int levels = PriceLevels.MAX_LEVELS + 2;
        for (int i = 1; i <= levels; i++) {
            book.set(Side.Ask, i * 1_000L, 1, 1);
        }

        assertEquals(PriceLevels.MAX_LEVELS, book.asks().depth());
        assertEquals(1_000L, book.asks().price(0), "the best levels are the ones kept");
        assertTrue(book.overflowed());
        book.clear();
        assertFalse(book.overflowed());
    }

    @Test
    void aShallowBookCopiesADeepOneWhole() {
        Book deep = new Book(4);
        for (int i = 1; i <= 1_000; i++) {
            deep.set(Side.Bid, i * 1_000L, 1, 1);
        }
        Book shallow = new Book(4);
        shallow.copyFrom(deep);

        assertEquals(1_000, shallow.bids().depth());
        assertEquals(1_000_000L, shallow.bids().price(0));
        assertFalse(shallow.overflowed());
    }

    @Test
    void manyInsertsInTheMiddleKeepTheOrder() {
        Book book = new Book(1024);
        for (int i = 0; i < 500; i++) {
            book.set(Side.Ask, ((i * 7919L) % 1000 + 1) * 1_000_000L, i + 1, 1);
        }
        for (int i = 1; i < book.asks().depth(); i++) {
            assertTrue(book.asks().price(i - 1) < book.asks().price(i));
        }
    }

    @Test
    void crossedIsBestBidAtOrAboveBestAsk() {
        Book book = new Book(4);
        book.set(Side.Bid, px(0.50), 1, 1);
        book.set(Side.Ask, px(0.51), 1, 1);
        assertFalse(book.crossed());
        book.set(Side.Ask, px(0.50), 1, 1);
        assertTrue(book.crossed());
    }

    @Test
    void theComplementSeesBidsAsAsksAtOneMinusThePrice() {
        Book book = new Book(16);
        book.set(Side.Bid, px(0.40), 4, 1);
        book.set(Side.Bid, px(0.38), 8, 2);
        book.set(Side.Ask, px(0.45), 5, 1);
        Mbp10Schema schema = new Mbp10Schema();

        new Mbp10Writer().write(book, Projection.COMPLEMENT, schema.encoder);

        assertEquals(px(0.55), schema.decoder.bidPrice0());
        assertEquals(5, schema.decoder.bidSize0());
        assertEquals(Mbp10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice1());
        assertEquals(px(0.60), schema.decoder.askPrice0());
        assertEquals(px(0.62), schema.decoder.askPrice1());
        assertEquals(8, schema.decoder.askSize1());
        assertEquals(2, schema.decoder.askCount1());
    }

    @Test
    void theIdentityWritesTheTopTenAndNullsTheRest() {
        Book book = new Book(32);
        for (int i = 1; i <= 12; i++) {
            book.set(Side.Bid, px(i / 100.0), i, 1);
        }
        Mbp10Schema schema = new Mbp10Schema();

        new Mbp10Writer().write(book, Projection.IDENTITY, schema.encoder);

        assertEquals(px(0.12), schema.decoder.bidPrice0());
        assertEquals(px(0.03), schema.decoder.bidPrice9());
        assertEquals(Mbp10Encoder.askPrice0NullValue(), schema.decoder.askPrice0());
    }

    @Test
    void theComplementFlipsSides() {
        assertEquals(Side.Ask, Projection.COMPLEMENT.side(Side.Bid));
        assertEquals(Side.None, Projection.COMPLEMENT.side(Side.None));
        assertEquals(Side.Bid, Projection.IDENTITY.side(Side.Bid));
    }
}
