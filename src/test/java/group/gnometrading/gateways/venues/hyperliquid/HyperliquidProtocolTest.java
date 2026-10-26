package group.gnometrading.gateways.venues.hyperliquid;

import static group.gnometrading.gateways.feed.VenueTestKit.frame;
import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.VenueTestKit.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.VenueTestKit;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Side;
import java.util.List;
import org.junit.jupiter.api.Test;

class HyperliquidProtocolTest {

    private final VenueTestKit kit = new VenueTestKit();
    private final VenueTestKit.Stream eth = kit.market("ETH");
    private final VenueTestKit.Stream btc = kit.market("BTC");
    // Subscriptions happen 0.4 s after the books' time, between the test trades' times.
    private final HyperliquidProtocol protocol = new HyperliquidProtocol(kit.table, () -> 1_700_000_000_400L);
    private long receive = 1_000;

    private void frame(final String json) {
        protocol.onFrame(VenueTestKit.frame(json), receive++);
    }

    private static String book(final String coin, final String bid, final String ask) {
        return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"" + coin
                + "\",\"time\":1700000000000,\"levels\":[[{\"px\":\""
                + bid + "\",\"sz\":\"1.5\",\"n\":3},{\"px\":\"1.0\",\"sz\":\"2\",\"n\":1}],[{\"px\":\"" + ask
                + "\",\"sz\":\"0.25\",\"n\":2}]]}}";
    }

    private static String trades(final String coin, final String side, final String px) {
        return trades(coin, side, px, 1_700_000_000_500L);
    }

    private static String trades(final String coin, final String side, final String px, final long time) {
        return "{\"channel\":\"trades\",\"data\":[{\"coin\":\"" + coin + "\",\"side\":\"" + side + "\",\"px\":\"" + px
                + "\",\"sz\":\"0.5\",\"time\":" + time + ",\"tid\":7}]}";
    }

    @Test
    void eachBookMessageIsTheCoinsWholeBook() throws Exception {
        frame(book("ETH", "2300.5", "2301"));

        Mbp10Schema record = eth.last();
        assertTrue(record.decoder.flags().snapshot());
        assertEquals(price("2300.5"), record.decoder.bidPrice0());
        assertEquals(size("1.5"), record.decoder.bidSize0());
        assertEquals(3, record.decoder.bidCount0());
        assertEquals(price("1.0"), record.decoder.bidPrice1());
        assertEquals(price("2301"), record.decoder.askPrice0());
        assertEquals(1700000000000L * 1_000_000L, record.decoder.timestampEvent());

        frame("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":1,\"levels\":[[],[]]}}");
        assertEquals(0, kit.table.get("ETH").book().bids().depth(), "the next message replaces the book whole");
    }

    @Test
    void manyCoinsShareAConnection() throws Exception {
        frame(book("ETH", "2300", "2301"));
        frame(book("BTC", "80000", "80001"));

        assertEquals(price("2300"), eth.last().decoder.bidPrice0());
        assertEquals(price("80000"), btc.last().decoder.bidPrice0());
        assertEquals(1, eth.drain().size());
    }

    @Test
    void aBookWithAnUnreadableLevelIsNotPublishedAndTheNextOneRepairsIt() throws Exception {
        frame(book("ETH", "2300", "2301"));
        frame(book("ETH", "n/a", "2301"));

        Subject subject = kit.table.get("ETH");
        assertEquals(SubjectState.STALE, subject.state());
        assertEquals(List.of("ETH:UNREADABLE"), kit.resyncs);
        assertTrue(eth.last().decoder.flags().badData());

        frame(book("ETH", "2302", "2303"));
        assertEquals(SubjectState.LIVE, subject.state());
        assertEquals(price("2302"), eth.last().decoder.bidPrice0());
    }

    @Test
    void theReplayANewTradesSubscriptionOpensWithIsSkipped() throws Exception {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        frame(book("ETH", "2300", "2301"));
        frame(trades("ETH", "B", "2300.5", 1_700_000_000_300L));
        int afterReplay = eth.drain().size();
        frame(trades("ETH", "A", "2300.25"));

        assertEquals(1, afterReplay, "only the book");
        Mbp10Schema print = eth.last();
        assertEquals(Action.Trade, print.decoder.action());
        assertEquals(Side.Ask, print.decoder.side());
        assertEquals(price("2300.25"), print.decoder.price());
        assertEquals(size("0.5"), print.decoder.size());
    }

    @Test
    void eachCoinIsSubscribedAndUnsubscribedOnItsOwn() {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        protocol.unsubscribe(kit.channel, List.of(kit.table.get("ETH")));
        protocol.keepAlive(kit.channel);

        assertEquals(
                List.of(
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"ETH\"}}",
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"ETH\"}}",
                        "{\"method\":\"unsubscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"ETH\"}}",
                        "{\"method\":\"unsubscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"ETH\"}}",
                        "{\"method\":\"ping\"}"),
                kit.sent);
    }

    @Test
    void otherChannelsAreIgnored() throws Exception {
        frame("{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\"}}");
        frame("{\"channel\":\"pong\"}");
        assertTrue(eth.drain().isEmpty());
    }

    @Test
    void anEmptyReplayDoesntSwallowTheFirstRealTrades() throws Exception {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        frame(book("ETH", "2300", "2301"));
        frame("{\"channel\":\"trades\",\"data\":[]}");
        frame(trades("ETH", "A", "2300.25"));

        assertEquals(Action.Trade, eth.last().decoder.action());
    }
}
