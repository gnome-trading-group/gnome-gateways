package group.gnometrading.gateways.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.orders.CrossMarket;
import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import org.junit.jupiter.api.Test;

/** Each venue's answer to where a listing's orders can trade against others. */
class CrossMarketTest {

    @Test
    void aBinaryMarketsTwoListingsShareTheirMarketAndTheOtherSideIsTheComplement() {
        assertEquals(new CrossMarket("KXBTC-26", false), KalshiVenue.crossMarketOf("KXBTC-26:yes"));
        assertEquals(new CrossMarket("KXBTC-26", true), KalshiVenue.crossMarketOf("KXBTC-26:no"));
        assertEquals(new CrossMarket("KXBTC-26", false), KalshiVenue.crossMarketOf("KXBTC-26"));
        assertEquals(new CrossMarket("nba-lal-bos", false), PolymarketUsVenue.crossMarketOf("nba-lal-bos:long"));
        assertEquals(new CrossMarket("nba-lal-bos", true), PolymarketUsVenue.crossMarketOf("nba-lal-bos:short"));
    }

    @Test
    void anOutcomeTokensMarketIsItsConditionAndTheSecondOutcomeIsTheComplement() {
        final PolymarketIntlMarketInfo info = new PolymarketIntlMarketInfo(1, 1, false, false, 0, "token-yes");

        assertEquals(new CrossMarket("cond", false), PolymarketIntlVenue.crossMarketOf("cond:token-yes", info));
        assertEquals(new CrossMarket("cond", true), PolymarketIntlVenue.crossMarketOf("cond:token-no", info));
    }

    @Test
    void anOutcomeTokenWhoseMarketDidntListItsTokensIsItsOwnMarket() {
        final PolymarketIntlMarketInfo info = new PolymarketIntlMarketInfo(1, 1, false, false, 0);

        assertEquals(new CrossMarket("token-no", false), PolymarketIntlVenue.crossMarketOf("cond:token-no", info));
    }
}
