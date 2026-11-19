package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.PriceGrid;
import group.gnometrading.sm.Security;
import org.junit.jupiter.api.Test;

class PolymarketUsMarketTest {

    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;
    private static final long CENT = DOLLAR / 100;
    private static final long LOT = Statics.SIZE_SCALING_FACTOR;

    private static PolymarketUsMarket market() {
        final Listing listing = new Listing(
                1,
                new Exchange(1, "POLYMARKET_US", "Polymarket US", "us"),
                new Security(1, "slug", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "slug:long",
                "slug");
        return new PolymarketUsMarket(listing, new PolymarketUsMarketInfo(CENT, LOT));
    }

    @Test
    void termsAreCheckedOnTheTickTheMarketLoadedWith() {
        final PolymarketUsMarket market = market();

        assertTrue(market.validTerms(55 * CENT, LOT));
        assertFalse(market.validTerms(555 * CENT / 10, LOT), "half a cent is off a cent tick");
        assertFalse(market.validTerms(DOLLAR, LOT));
        assertFalse(market.validTerms(55 * CENT, LOT / 2));
    }

    @Test
    void aFinerGridFromTheListingsSpecReplacesTheLoadedTick() {
        final PolymarketUsMarket market = market();

        market.priceGrid(PriceGrid.single(CENT / 10, DOLLAR));

        assertTrue(market.validTerms(555 * CENT / 10, LOT));
        assertFalse(market.validTerms(5555 * CENT / 100, LOT));
    }

    @Test
    void aGridThatDoesNotEndAtADollarIsRefusedAndTheLastOneKept() {
        final PolymarketUsMarket market = market();

        assertThrows(IllegalArgumentException.class, () -> market.priceGrid(PriceGrid.single(CENT, PriceGrid.OPEN)));

        assertFalse(market.validTerms(DOLLAR, LOT));
        assertTrue(market.validTerms(55 * CENT, LOT));
    }
}
