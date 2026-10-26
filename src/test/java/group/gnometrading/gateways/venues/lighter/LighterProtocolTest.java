package group.gnometrading.gateways.venues.lighter;

import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.VenueTestKit.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.VenueTestKit;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Side;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LighterProtocolTest {

    private final VenueTestKit kit = new VenueTestKit();
    private final VenueTestKit.Stream eth = kit.market("0");
    private final VenueTestKit.Stream btc = kit.market("1");
    private final LighterProtocol protocol = new LighterProtocol(kit.table, kit.channel);
    private long receive = 1_000;

    @BeforeEach
    void subscribe() {
        protocol.subscribe(kit.channel, List.of(kit.table.get("0"), kit.table.get("1")));
        kit.sent.clear();
    }

    private void frame(final String json) {
        protocol.onFrame(VenueTestKit.frame(json), receive++);
    }

    private static String book(
            final String market,
            final String type,
            final long begin,
            final long nonce,
            final String asks,
            final String bids) {
        return "{\"channel\":\"order_book:" + market + "\",\"offset\":5,\"order_book\":{\"code\":0,\"asks\":[" + asks
                + "],\"bids\":[" + bids + "],\"offset\":5,\"begin_nonce\":" + begin + ",\"nonce\":" + nonce
                + "},\"timestamp\":1761585003166,\"type\":\"" + type + "/order_book\"}";
    }

    private static String level(final String price, final String size) {
        return "{\"price\":\"" + price + "\",\"size\":\"" + size + "\"}";
    }

    private void snapshotEth() {
        frame(book(
                "0",
                "subscribed",
                0,
                100,
                level("4212.00", "2.0") + "," + level("4213.00", "1.0"),
                level("4210.00", "3.0")));
    }

    @Test
    void theFirstBookAfterSubscribingIsTheFullBook() throws Exception {
        snapshotEth();

        Mbp10Schema record = eth.last();
        assertTrue(record.decoder.flags().snapshot());
        assertEquals(price("4212"), record.decoder.askPrice0());
        assertEquals(price("4213"), record.decoder.askPrice1());
        assertEquals(price("4210"), record.decoder.bidPrice0());
        assertEquals(size("3"), record.decoder.bidSize0());
        assertEquals(1761585003166L * 1_000_000L, record.decoder.timestampEvent());
        assertEquals(SubjectState.LIVE, kit.table.get("0").state());
    }

    @Test
    void updatesSetLevelsAndZeroRemovesOne() throws Exception {
        snapshotEth();
        frame(book("0", "update", 100, 101, level("4212.00", "0") + "," + level("4211.50", "4.0"), ""));

        Mbp10Schema record = eth.last();
        assertFalse(record.decoder.flags().snapshot());
        assertEquals(price("4211.5"), record.decoder.askPrice0());
        assertEquals(size("4"), record.decoder.askSize0());
        assertEquals(price("4213"), record.decoder.askPrice1());
    }

    @Test
    void aBrokenNonceChainMakesOnlyThatMarketStale() throws Exception {
        snapshotEth();
        frame(book("1", "subscribed", 0, 500, level("90000", "1"), level("89999", "1")));
        frame(book("0", "update", 100, 101, level("4212.00", "1.0"), ""));
        frame(book("1", "update", 500, 501, level("90000", "2"), ""));
        frame(book("0", "update", 105, 106, level("4212.00", "5.0"), ""));

        assertEquals(SubjectState.STALE, kit.table.get("0").state());
        assertEquals(SubjectState.LIVE, kit.table.get("1").state());
        assertEquals(List.of("0:NONCE"), kit.resyncs);
        assertTrue(eth.last().decoder.flags().badData());
    }

    @Test
    void aResyncResubscribesTheBookAndItsAnswerIsASnapshot() throws Exception {
        snapshotEth();
        Subject market = kit.table.get("0");
        market.stale(group.gnometrading.gateways.feed.GapReason.NONCE, receive);
        protocol.resync(kit.channel, List.of(market));

        assertEquals(
                List.of(
                        "{\"type\":\"unsubscribe\",\"channel\":\"order_book/0\"}",
                        "{\"type\":\"subscribe\",\"channel\":\"order_book/0\"}"),
                kit.sent);
        frame(book("0", "subscribed", 0, 300, level("4300.00", "1.0"), ""));
        assertEquals(SubjectState.LIVE, market.state());
        assertEquals(price("4300"), eth.last().decoder.askPrice0());
        assertEquals(0, market.book().bids().depth(), "the snapshot replaces the old book");
    }

    @Test
    void anUnreadableUpdateMakesTheMarketStaleAndAnUnreadableSnapshotLevelIsLeftOut() throws Exception {
        frame(book("0", "subscribed", 0, 100, level("n/a", "2.0") + "," + level("4213.00", "1.0"), ""));
        assertEquals(price("4213"), eth.last().decoder.askPrice0());

        frame(book("0", "update", 100, 101, level("4212.00", "lots"), ""));
        assertEquals(SubjectState.STALE, kit.table.get("0").state());
    }

    @Test
    void tradesNameTheAggressor() throws Exception {
        snapshotEth();
        frame(
                "{\"channel\":\"trade:0\",\"trades\":[{\"price\":\"4212.50\",\"size\":\"0.1\",\"timestamp\":1761585003200,"
                        + "\"is_maker_ask\":true}],\"type\":\"update/trade\"}");

        Mbp10Schema print = eth.last();
        assertEquals(Action.Trade, print.decoder.action());
        assertEquals(Side.Bid, print.decoder.side(), "a resting ask was hit");
        assertEquals(price("4212.5"), print.decoder.price());
    }

    @Test
    void itsPingsAreAnsweredAndItsOwnKeepAliveIsAPing() {
        frame("{\"type\":\"ping\"}");
        protocol.keepAlive(kit.channel);

        assertEquals(List.of("{\"type\":\"pong\"}", "{\"type\":\"ping\"}"), kit.sent);
    }

    @Test
    void subscribingAndUnsubscribingNameBothChannels() {
        protocol.unsubscribe(kit.channel, List.of(kit.table.get("1")));
        assertEquals(
                List.of(
                        "{\"type\":\"unsubscribe\",\"channel\":\"order_book/1\"}",
                        "{\"type\":\"unsubscribe\",\"channel\":\"trade/1\"}"),
                kit.sent);
    }

    @Test
    void channelsForOtherMarketsAreIgnored() throws Exception {
        frame(book("7", "subscribed", 0, 1, level("1", "1"), ""));
        frame("{\"type\":\"connected\",\"session_id\":\"x\"}");
        assertTrue(eth.drain().isEmpty());
    }

    @Test
    void anUpdateArrivingBeforeTheResyncsSnapshotIsNotTakenForIt() throws Exception {
        snapshotEth();
        Subject market = kit.table.get("0");
        market.stale(group.gnometrading.gateways.feed.GapReason.NONCE, receive);
        protocol.resync(kit.channel, List.of(market));
        int published = eth.drain().size();

        // Still subscribed until the resubscribe lands: an ordinary update comes first.
        frame(book("0", "update", 100, 101, level("4212.00", "9.0"), ""));
        assertEquals(SubjectState.STALE, market.state());
        assertEquals(published, eth.drain().size(), "nothing goes out from an update to a stale book");

        frame(book("0", "subscribed", 0, 300, level("4300.00", "1.0"), level("4290.00", "2.0")));
        assertEquals(SubjectState.LIVE, market.state());
        assertTrue(eth.last().decoder.flags().snapshot());
        assertEquals(price("4300"), eth.last().decoder.askPrice0());
        assertEquals(1, market.book().asks().depth(), "the whole book, not the update's levels on top");
    }
}
