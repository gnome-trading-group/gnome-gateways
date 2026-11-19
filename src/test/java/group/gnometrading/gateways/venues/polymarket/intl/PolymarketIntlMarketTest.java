package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.PriceGrid;
import group.gnometrading.sm.Security;
import org.junit.jupiter.api.Test;

class PolymarketIntlMarketTest {

    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long CENT = DOLLAR / 100;
    private static final long SHARE = Statics.SIZE_SCALING_FACTOR;

    private static PolymarketIntlMarket market(final long tick) {
        final Listing listing = new Listing(
                1,
                new Exchange(1, "POLYMARKET", "Polymarket", "global"),
                new Security(1, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "0xcondition:123",
                "TEST-YES");
        return PolymarketIntlMarket.of(listing, new PolymarketIntlMarketInfo(tick, SHARE, false, false, 0));
    }

    @Test
    void amountsAreTwoDecimalsFinerThanTheTick() {
        assertEquals(SHARE / 10_000, market(CENT).amountIncrement());
        assertEquals(SHARE / 100_000, market(CENT / 10).amountIncrement());
    }

    @Test
    void aMarketMovedToAFinerTickTakesItsFinerAmountIncrement() {
        final PolymarketIntlMarket market = market(CENT);

        market.priceGrid(PriceGrid.single(CENT / 10, DOLLAR));

        assertEquals(SHARE / 100_000, market.amountIncrement());
    }
}
