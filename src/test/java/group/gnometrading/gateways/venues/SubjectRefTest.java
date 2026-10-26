package group.gnometrading.gateways.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.venues.hyperliquid.HyperliquidVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.lighter.LighterVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import org.junit.jupiter.api.Test;

class SubjectRefTest {

    @Test
    void binaryListingsShareTheirMarketAndTheOtherSideSeesTheComplement() {
        assertEquals(new SubjectRef("KXBTC-26", Projection.IDENTITY), KalshiVenue.subjectOf("KXBTC-26:yes"));
        assertEquals(new SubjectRef("KXBTC-26", Projection.COMPLEMENT), KalshiVenue.subjectOf("KXBTC-26:no"));
        assertEquals(new SubjectRef("KXBTC-26", Projection.IDENTITY), KalshiVenue.subjectOf("KXBTC-26"));
        assertEquals(
                new SubjectRef("nba-lal-bos", Projection.IDENTITY), PolymarketUsVenue.subjectOf("nba-lal-bos:long"));
        assertEquals(
                new SubjectRef("nba-lal-bos", Projection.COMPLEMENT), PolymarketUsVenue.subjectOf("nba-lal-bos:short"));
    }

    @Test
    void otherVenuesReadTheirOwnMarket() {
        assertEquals(new SubjectRef("token-1", Projection.IDENTITY), PolymarketIntlVenue.subjectOf("cond:token-1"));
        assertEquals(new SubjectRef("BTC", Projection.IDENTITY), HyperliquidVenue.subjectOf("BTC"));
        assertEquals(new SubjectRef("1", Projection.IDENTITY), LighterVenue.subjectOf("1"));
    }
}
