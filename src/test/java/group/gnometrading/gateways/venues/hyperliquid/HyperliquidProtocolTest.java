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
    private final HyperliquidProtocol protocol = new HyperliquidProtocol(kit.table);
    private long receive = 1_000;

    private void frame(final String json) {
        protocol.onFrame(VenueTestKit.frame(json), receive++);
    }

    // Each book a block later than the last, as Hyperliquid sends them.
    private long bookTime = 1_700_000_000_000L;

    private String book(final String coin, final String bid, final String ask) {
        final long time = bookTime;
        bookTime += 500;
        return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"" + coin
                + "\",\"time\":" + time + ",\"levels\":[[{\"px\":\""
                + bid + "\",\"sz\":\"1.5\",\"n\":3},{\"px\":\"1.0\",\"sz\":\"2\",\"n\":1}],[{\"px\":\"" + ask
                + "\",\"sz\":\"0.25\",\"n\":2}]]}}";
    }

    private static String trades(final String coin, final String side, final String px) {
        return trades(coin, side, px, 1_700_000_000_500L);
    }

    private static String trades(final String coin, final String side, final String px, final long time) {
        return "{\"channel\":\"trades\",\"data\":[" + trade(coin, side, px, time, 7) + "]}";
    }

    private static String trade(
            final String coin, final String side, final String px, final long time, final long tid) {
        return "{\"coin\":\"" + coin + "\",\"side\":\"" + side + "\",\"px\":\"" + px + "\",\"sz\":\"0.5\",\"time\":"
                + time + ",\"tid\":" + tid + "}";
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

        frame("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":1700000001000,\"levels\":[[],[]]}}");
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
    void eachCoinIsSubscribedAndUnsubscribedOnItsOwn() {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        protocol.unsubscribe(kit.channel, List.of(kit.table.get("ETH")));
        protocol.keepAlive(kit.channel);

        assertEquals(
                List.of(
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"ETH\",\"fast\":true}}",
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"ETH\"}}",
                        "{\"method\":\"unsubscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"ETH\",\"fast\":true}}",
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
    void distinctTradesInOneMillisecondAreAllPublished() throws Exception {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        frame(book("ETH", "2300", "2301"));
        eth.drain().clear();
        frame("{\"channel\":\"trades\",\"data\":[" + trade("ETH", "B", "2300.5", 1_700_000_000_500L, 11) + ","
                + trade("ETH", "B", "2300.5", 1_700_000_000_500L, 12) + "]}");
        frame("{\"channel\":\"trades\",\"data\":[" + trade("ETH", "B", "2300.5", 1_700_000_000_500L, 13) + "]}");

        assertEquals(3, eth.drain().size());
    }

    @Test
    void anIdIsForgottenOnlyOnceManyNewerOnesFollow() throws Exception {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        frame(book("ETH", "2300", "2301"));
        eth.drain().clear();
        for (int tid = 1; tid <= HyperliquidProtocol.RECENT_TRADE_IDS; tid++) {
            frame("{\"channel\":\"trades\",\"data\":[" + trade("ETH", "B", "2300.5", 1_700_000_000_500L, tid) + "]}");
        }
        frame("{\"channel\":\"trades\",\"data\":[" + trade("ETH", "B", "2300.5", 1_700_000_000_500L, 1) + "]}");
        frame("{\"channel\":\"trades\",\"data\":[" + trade("ETH", "B", "2300.5", 1_700_000_000_500L, 2) + "]}");

        assertEquals(HyperliquidProtocol.RECENT_TRADE_IDS, eth.drain().size(), "64 ids held: 1 and 2 are still known");
    }

    /** How Hyperliquid answers a subscription, as captured. */
    private static String answer(final String type, final String coin) {
        return "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\",\"subscription\":{\"type\":\""
                + type + "\",\"coin\":\"" + coin + "\",\"nSigFigs\":null,\"mantissa\":null,\"fast\":true}}}";
    }

    private static String tradesOf(final String... trades) {
        return "{\"channel\":\"trades\",\"data\":[" + String.join(",", trades) + "]}";
    }

    private void live() throws Exception {
        protocol.subscribe(kit.channel, List.of(kit.table.get("ETH")));
        frame(book("ETH", "2300", "2301"));
        eth.drain().clear();
    }

    @Test
    void theReplayATradesSubscriptionIsAnsweredWithIsNotPublished() throws Exception {
        live();
        frame(answer("trades", "ETH"));
        frame(tradesOf(
                trade("ETH", "B", "2300.5", 1_700_000_000_300L, 1),
                trade("ETH", "B", "2300.6", 1_800_000_000_000L, 2)));
        frame(tradesOf(trade("ETH", "A", "2300.25", 1_700_000_000_500L, 3)));

        List<Mbp10Schema> prints = eth.drain();
        assertEquals(1, prints.size(), "however new its times, the replay isn't published");
        assertEquals(Action.Trade, prints.get(0).decoder.action());
        assertEquals(Side.Ask, prints.get(0).decoder.side());
        assertEquals(price("2300.25"), prints.get(0).decoder.price());
        assertEquals(size("0.5"), prints.get(0).decoder.size());
    }

    @Test
    void aLiveTradeOlderThanTheSubscribeIsStillPublished() throws Exception {
        live();
        frame(answer("trades", "ETH"));
        frame(tradesOf(trade("ETH", "B", "2300.5", 1_700_000_000_300L, 1)));
        // Hyperliquid can deliver a trade a second after its time, after a subscribe sent in between.
        frame(tradesOf(trade("ETH", "B", "2300.75", 1_000L, 2)));

        assertEquals(1, eth.drain().size());
    }

    @Test
    void theReplaysLastBlockSentAgainLiveIsPublishedOnce() throws Exception {
        live();
        frame(answer("trades", "ETH"));
        frame(tradesOf(
                trade("ETH", "B", "2300.5", 1_700_000_000_400L, 1),
                trade("ETH", "B", "2300.5", 1_700_000_000_400L, 2),
                trade("ETH", "B", "2300.75", 1_700_000_000_400L, 3)));
        frame(tradesOf(
                trade("ETH", "B", "2300.5", 1_700_000_000_400L, 2),
                trade("ETH", "B", "2300.75", 1_700_000_000_400L, 3)));
        frame(tradesOf(trade("ETH", "A", "2300.25", 1_700_000_000_500L, 4)));

        List<Mbp10Schema> prints = eth.drain();
        assertEquals(1, prints.size(), "2 and 3 came in the replay; only 4 is new");
        assertEquals(price("2300.25"), prints.get(0).decoder.price());
    }

    @Test
    void anEmptyReplayDoesntSwallowTheFirstRealTrades() throws Exception {
        live();
        frame(answer("trades", "ETH"));
        frame("{\"channel\":\"trades\",\"data\":[]}");
        frame(trades("ETH", "A", "2300.25"));

        assertEquals(Action.Trade, eth.last().decoder.action());
    }

    @Test
    void anotherCoinsTradesBetweenAnAnswerAndItsReplayArePublished() throws Exception {
        live();
        protocol.subscribe(kit.channel, List.of(kit.table.get("BTC")));
        frame(book("BTC", "80000", "80001"));
        btc.drain().clear();
        frame(answer("trades", "ETH"));
        frame(tradesOf(trade("BTC", "B", "80000.5", 1_700_000_000_500L, 9)));
        frame(tradesOf(trade("ETH", "B", "2300.5", 1_700_000_000_300L, 1)));
        frame(tradesOf(trade("ETH", "B", "2300.75", 1_700_000_000_600L, 2)));

        assertEquals(1, btc.drain().size());
        assertEquals(1, eth.drain().size(), "the ETH replay was the frame after the BTC trades");
    }

    @Test
    void aBookSubscriptionsAnswerIsFollowedByNoReplay() throws Exception {
        live();
        frame(answer("l2Book", "ETH"));
        frame(tradesOf(trade("ETH", "B", "2300.5", 1_700_000_000_500L, 1)));

        assertEquals(1, eth.drain().size());
    }

    private static String book(final String coin, final String bid, final long time) {
        return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"" + coin + "\",\"time\":" + time
                + ",\"levels\":[[{\"px\":\"" + bid
                + "\",\"sz\":\"1.5\",\"n\":3}],[{\"px\":\"2400\",\"sz\":\"1\",\"n\":1}]]}}";
    }

    @Test
    void aLiveCoinsBookNoNewerThanTheLastIsSkipped() throws Exception {
        frame(book("ETH", "2300", 1_700_000_000_000L));
        frame(book("ETH", "2300", 1_700_000_000_000L));
        frame(book("ETH", "2299", 1_699_999_999_000L));
        frame(book("ETH", "2301", 1_700_000_000_500L));

        List<Mbp10Schema> books = eth.drain();
        assertEquals(2, books.size(), "the repeat and the older one skipped");
        assertEquals(price("2301"), books.get(1).decoder.bidPrice0());
    }

    @Test
    void aSnapshotAfterAReconnectIsTakenThoughNoNewer() throws Exception {
        frame(book("ETH", "2300", 1_700_000_000_000L));
        kit.table.get("ETH").disconnected(5);
        frame(book("ETH", "2300", 1_700_000_000_000L));

        assertEquals(SubjectState.LIVE, kit.table.get("ETH").state());
    }
}
