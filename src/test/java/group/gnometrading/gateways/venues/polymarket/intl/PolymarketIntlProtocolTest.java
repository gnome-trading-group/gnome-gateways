package group.gnometrading.gateways.venues.polymarket.intl;

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

class PolymarketIntlProtocolTest {

    private final VenueTestKit kit = new VenueTestKit();
    private final VenueTestKit.Stream yes = kit.market("token-yes");
    private final VenueTestKit.Stream no = kit.market("token-no");
    private final PolymarketIntlProtocol protocol = new PolymarketIntlProtocol(kit.table);
    private long receive = 1_000;

    @BeforeEach
    void subscribe() {
        protocol.subscribe(kit.channel, List.of(kit.table.get("token-yes"), kit.table.get("token-no")));
        kit.sent.clear();
    }

    private void frame(final String json) {
        protocol.onFrame(VenueTestKit.frame(json), receive++);
    }

    private static String book(final String token, final String timestamp, final String bids, final String asks) {
        return "{\"market\":\"condition-1\",\"asset_id\":\"" + token + "\",\"timestamp\":\"" + timestamp
                + "\",\"bids\":[" + bids + "],\"asks\":[" + asks + "],\"event_type\":\"book\","
                + "\"last_trade_price\":\"0.09\"}";
    }

    private static String level(final String price, final String size) {
        return "{\"price\":\"" + price + "\",\"size\":\"" + size + "\"}";
    }

    private static String change(
            final String token,
            final String side,
            final String price,
            final String size,
            final String bestBid,
            final String bestAsk) {
        return "{\"asset_id\":\"" + token + "\",\"price\":\"" + price + "\",\"size\":\"" + size + "\",\"side\":\""
                + side + "\",\"hash\":\"h\",\"best_bid\":\"" + bestBid + "\",\"best_ask\":\"" + bestAsk + "\"}";
    }

    private static String changes(final String timestamp, final String... items) {
        return "{\"market\":\"condition-1\",\"price_changes\":[" + String.join(",", items) + "],\"timestamp\":\""
                + timestamp + "\",\"event_type\":\"price_change\"}";
    }

    private void bookYes() {
        frame("["
                + book(
                        "token-yes",
                        "1782753357257",
                        level("0.07", "4") + "," + level("0.08", "3"),
                        level("0.11", "7") + "," + level("0.10", "6"))
                + "]");
    }

    @Test
    void aBookIsTheTokensWholeBook() throws Exception {
        bookYes();

        Mbp10Schema record = yes.last();
        assertTrue(record.decoder.flags().snapshot());
        assertEquals(price("0.08"), record.decoder.bidPrice0());
        assertEquals(size("3"), record.decoder.bidSize0());
        assertEquals(price("0.10"), record.decoder.askPrice0());
        assertEquals(1_782_753_357_257_000_000L, record.decoder.timestampEvent());
        assertTrue(no.drain().isEmpty());
    }

    @Test
    void anOpeningArrayOfBooksReachesEachToken() throws Exception {
        frame("[" + book("token-yes", "1782753357257", level("0.08", "3"), level("0.10", "6")) + ","
                + book("token-no", "1782753357257", level("0.90", "6"), level("0.92", "3")) + "]");

        assertEquals(price("0.08"), yes.last().decoder.bidPrice0());
        assertEquals(price("0.90"), no.last().decoder.bidPrice0());
    }

    @Test
    void priceChangesReachEveryTokenTheyNameAndSkipOthers() throws Exception {
        bookYes();
        frame("[" + book("token-no", "1782753357257", level("0.89", "6"), level("0.92", "3")) + "]");
        frame(changes(
                "1782753358257",
                change("token-yes", "BUY", "0.08", "0", "0.09", "0.10"),
                change("token-yes", "BUY", "0.09", "5", "0.09", "0.10"),
                change("token-no", "SELL", "0.91", "2", "0.89", "0.91"),
                change("someone-else", "SELL", "0.12", "999", "0.1", "0.12")));

        Mbp10Schema yesBook = yes.last();
        assertEquals(price("0.09"), yesBook.decoder.bidPrice0());
        assertEquals(size("5"), yesBook.decoder.bidSize0());
        assertEquals(price("0.07"), yesBook.decoder.bidPrice1());
        assertEquals(1_782_753_358_257_000_000L, yesBook.decoder.timestampEvent());
        assertEquals(price("0.91"), no.last().decoder.askPrice0());
        assertEquals(2, yes.drain().size(), "one book, one update for the whole event");
    }

    @Test
    void aTradeGoesOutWithTheBookUntouched() throws Exception {
        bookYes();
        frame("{\"market\":\"condition-1\",\"asset_id\":\"token-yes\",\"price\":\"0.10\",\"size\":\"12\","
                + "\"side\":\"BUY\",\"timestamp\":\"1782753359000\",\"event_type\":\"last_trade_price\"}");

        Mbp10Schema print = yes.last();
        assertEquals(Action.Trade, print.decoder.action());
        assertEquals(Side.Bid, print.decoder.side());
        assertEquals(price("0.10"), print.decoder.price());
        assertEquals(size("12"), print.decoder.size());
        assertEquals(price("0.08"), print.decoder.bidPrice0(), "the trade doesn't clear the book");
    }

    @Test
    void levelsInExponentFormAreReadAndUnreadableOnesLeftOut() throws Exception {
        frame("["
                + book(
                        "token-yes",
                        "1782753357257",
                        level("8e-02", "3E+1") + "," + level("n/a", "1"),
                        level("0.10", "6"))
                + "]");

        assertEquals(price("0.08"), yes.last().decoder.bidPrice0());
        assertEquals(size("30"), yes.last().decoder.bidSize0());
        assertEquals(1, kit.table.get("token-yes").book().bids().depth());
    }

    @Test
    void nothingIsPublishedWithoutTheVenuesTime() throws Exception {
        frame("[" + book("token-yes", "soon", level("0.08", "3"), level("0.10", "6")) + "]");
        assertTrue(yes.drain().isEmpty());
    }

    @Test
    void anUnreadableChangeMakesItsTokenStale() throws Exception {
        bookYes();
        frame(changes("1782753358257", change("token-yes", "BUY", "0.09", "lots", "0.09", "0.10")));

        assertEquals(SubjectState.STALE, kit.table.get("token-yes").state());
        assertEquals(List.of("token-yes:UNREADABLE"), kit.resyncs);
    }

    @Test
    void bestPricesAreCheckedOnceTheMatchIsOver() throws Exception {
        bookYes();
        // Mid-match the venue's best bid (0.09) is ahead of the book; at the next timestamp it must agree.
        frame(changes("1782753357300", change("token-yes", "BUY", "0.07", "5", "0.09", "0.10")));
        frame(changes("1782753357300", change("token-yes", "BUY", "0.09", "1", "0.09", "0.10")));
        frame(changes("1782753357301", change("token-yes", "SELL", "0.11", "2", "0.09", "0.10")));
        assertEquals(SubjectState.LIVE, kit.table.get("token-yes").state());
        assertFalse(yes.last().decoder.flags().badData());

        // A bid the book was never told about.
        frame(changes("1782753357400", change("token-yes", "BUY", "0.07", "6", "0.095", "0.10")));
        frame(changes("1782753357401", change("token-yes", "SELL", "0.11", "3", "0.095", "0.10")));
        Subject subject = kit.table.get("token-yes");
        assertEquals(SubjectState.STALE, subject.state());
        assertTrue(yes.last().decoder.flags().badData());
        assertEquals(List.of("token-yes:BEST_PRICE"), kit.resyncs, "the other token is untouched");
    }

    @Test
    void anEmptySideIsTheVenuesZeroOrOne() throws Exception {
        frame("[" + book("token-yes", "1782753357257", level("0.08", "3"), "") + "]");
        frame(changes("1782753357300", change("token-yes", "BUY", "0.08", "0", "0", "1")));
        frame(changes("1782753357301", change("token-yes", "BUY", "0.05", "1", "0.05", "1")));

        assertFalse(yes.last().decoder.flags().badData());
    }

    @Test
    void aWholeBookClearsAPendingCheck() throws Exception {
        bookYes();
        frame(changes("1782753357300", change("token-yes", "BUY", "0.07", "5", "0.09", "0.10")));
        frame("[" + book("token-yes", "1782753357301", level("0.08", "3"), level("0.10", "6")) + "]");
        frame(changes("1782753357302", change("token-yes", "SELL", "0.11", "2", "0.08", "0.10")));

        assertFalse(yes.last().decoder.flags().badData());
    }

    @Test
    void textFramesAreIgnored() throws Exception {
        frame("PONG");
        frame("NO NEW ASSETS");
        frame("INVALID ASSET IDS");
        assertTrue(yes.drain().isEmpty());
    }

    @Test
    void theOpeningSubscribeNamesTheChannelLaterOnesAreOperations() {
        PolymarketIntlProtocol fresh = new PolymarketIntlProtocol(kit.table);
        Subject token = kit.table.get("token-yes");
        fresh.subscribe(kit.channel, List.of(token));
        fresh.subscribe(kit.channel, List.of(kit.table.get("token-no")));
        fresh.resync(kit.channel, List.of(token));
        fresh.unsubscribe(kit.channel, List.of(token));
        fresh.keepAlive(kit.channel);

        assertEquals(
                List.of(
                        "{\"type\":\"market\",\"assets_ids\":[\"token-yes\"],\"custom_feature_enabled\":true}",
                        "{\"operation\":\"subscribe\",\"assets_ids\":[\"token-no\"],\"custom_feature_enabled\":true}",
                        "{\"operation\":\"unsubscribe\",\"assets_ids\":[\"token-yes\"],\"custom_feature_enabled\":true}",
                        "{\"operation\":\"subscribe\",\"assets_ids\":[\"token-yes\"],\"custom_feature_enabled\":true}",
                        "{\"operation\":\"unsubscribe\",\"assets_ids\":[\"token-yes\"],\"custom_feature_enabled\":true}",
                        "PING"),
                kit.sent);
    }

    @Test
    void anEventTouchingManyTokensPublishesEveryOne() throws Exception {
        List<VenueTestKit.Stream> streams = new java.util.ArrayList<>();
        List<Subject> tokens = new java.util.ArrayList<>();
        StringBuilder books = new StringBuilder("[");
        String[] items = new String[20];
        for (int i = 0; i < 20; i++) {
            String token = "t" + i;
            streams.add(kit.market(token));
            tokens.add(kit.table.get(token));
            books.append(i == 0 ? "" : ",")
                    .append(book(token, "1782753357257", level("0.08", "3"), level("0.10", "6")));
            items[i] = change(token, "BUY", "0.09", "5", "0.09", "0.10");
        }
        protocol.subscribe(kit.channel, tokens);
        frame(books.append("]").toString());
        frame(changes("1782753358257", items));

        for (VenueTestKit.Stream stream : streams) {
            assertEquals(price("0.09"), stream.last().decoder.bidPrice0());
            assertFalse(stream.last().decoder.flags().badData());
        }
    }
}
