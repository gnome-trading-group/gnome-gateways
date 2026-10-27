package group.gnometrading.gateways.venues.polymarket.us;

import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.VenueTestKit.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.VenueTestKit;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolymarketUsProtocolTest {

    private static final String SLUG = "nfl-buf-kc-2026-11-02";

    private final VenueTestKit kit = new VenueTestKit();
    private final VenueTestKit.Stream[] legs = new VenueTestKit.Stream[2];
    private final VenueTestKit.Stream other;
    private final PolymarketUsProtocol protocol;
    private long receive = 1_000;

    PolymarketUsProtocolTest() throws Exception {
        kit.market(SLUG, legs, Projection.IDENTITY, Projection.COMPLEMENT);
        other = kit.market("other-market");
        byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate())
                .getBytes()
                .orElseThrow();
        protocol = new PolymarketUsProtocol(
                new NullLogger(),
                kit.table,
                new PolymarketUsAuthSigner(
                        "test-key",
                        PolymarketUsAuthSigner.parseSecretKey(
                                Base64.getEncoder().encodeToString(seed))),
                () -> 0);
    }

    private void frame(final String json) {
        protocol.onFrame(VenueTestKit.frame(json), receive++);
    }

    private static String amount(final String value) {
        return "{\"value\":\"" + value + "\",\"currency\":\"USD\"}";
    }

    private static String level(final String px, final String qty) {
        return "{\"px\":" + amount(px) + ",\"qty\":\"" + qty + "\"}";
    }

    private static String book(final String slug, final String bids, final String offers) {
        return "{\"requestId\":\"m0.1\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_MARKET_DATA\","
                + "\"marketData\":{\"marketSlug\":\"" + slug + "\"" + bids + offers
                + ",\"state\":\"MARKET_STATE_OPEN\",\"transactTime\":\"2026-10-26T15:00:00.123Z\"}}";
    }

    @Test
    void eachMessageIsTheWholeBookAndAnEmptySideMayBeLeftOut() throws Exception {
        frame(book(
                SLUG,
                ",\"bids\":[" + level("0.55", "100") + "," + level("0.54", "20") + "]",
                ",\"offers\":[" + level("0.56", "30") + "]"));
        Mbp10Schema record = legs[0].last();
        assertTrue(record.decoder.flags().snapshot());
        assertEquals(price("0.55"), record.decoder.bidPrice0());
        assertEquals(size("20"), record.decoder.bidSize1());
        assertEquals(price("0.56"), record.decoder.askPrice0());
        assertEquals(1793026800123000000L, record.decoder.timestampEvent());

        frame(book(SLUG, "", ",\"offers\":[" + level("0.57", "5") + "]"));
        record = legs[0].last();
        assertEquals(Mbp10Encoder.bidPrice0NullValue(), record.decoder.bidPrice0());
        assertEquals(price("0.57"), record.decoder.askPrice0());
    }

    @Test
    void anUnreadableLevelIsLeftOutOfThatMessageOnly() throws Exception {
        frame(book(SLUG, ",\"bids\":[" + level("n/a", "100") + "," + level("0.54", "20") + "]", ""));
        assertEquals(price("0.54"), legs[0].last().decoder.bidPrice0());
    }

    @Test
    void marketsAreToldApartByTheMarketEachMessageNames() throws Exception {
        frame(book("other-market", ",\"bids\":[" + level("0.10", "1") + "]", ""));
        frame(book(SLUG, ",\"bids\":[" + level("0.55", "1") + "]", ""));

        assertEquals(price("0.10"), other.last().decoder.bidPrice0());
        assertEquals(price("0.55"), legs[0].last().decoder.bidPrice0());
        frame(book("not-ours", ",\"bids\":[" + level("0.99", "1") + "]", ""));
        assertEquals(1, other.drain().size());
    }

    @Test
    void theShortListingSeesTheComplement() throws Exception {
        frame(book(SLUG, ",\"bids\":[" + level("0.55", "100") + "]", ",\"offers\":[" + level("0.56", "30") + "]"));

        Mbp10Schema shortBook = legs[1].last();
        assertEquals(price("0.44"), shortBook.decoder.bidPrice0());
        assertEquals(price("0.45"), shortBook.decoder.askPrice0());
    }

    @Test
    void theTakersIntentDecidesTheAggressor() throws Exception {
        frame(book(SLUG, ",\"bids\":[" + level("0.55", "100") + "]", ""));
        frame("{\"requestId\":\"t0.1\",\"trade\":{\"marketSlug\":\"" + SLUG + "\",\"price\":"
                + amount("0.555") + ",\"quantity\":" + amount("3") + ",\"tradeTime\":\"2026-10-26T15:00:01Z\","
                + "\"taker\":{\"side\":\"ORDER_SIDE_BUY\",\"intent\":\"ORDER_INTENT_BUY_SHORT\"}}}");

        Mbp10Schema print = legs[0].last();
        assertEquals(Action.Trade, print.decoder.action());
        assertEquals(Side.Ask, print.decoder.side(), "buying short sells long");
        assertEquals(price("0.555"), print.decoder.price());
        assertEquals(size("3"), print.decoder.size());
        assertEquals(Side.Bid, legs[1].last().decoder.side());
        assertEquals(price("0.445"), legs[1].last().decoder.price());
    }

    private static String subscribe(final String id, final String type, final String... slugs) {
        return "{\"subscribe\":{\"requestId\":\"" + id + "\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_" + type
                + "\",\"marketSlugs\":[\"" + String.join("\",\"", slugs) + "\"]}}";
    }

    private static String unsubscribe(final String id) {
        return "{\"unsubscribe\":{\"requestId\":\"" + id + "\"}}";
    }

    @Test
    void marketsShareTheirGroupsBookAndTradeSubscriptions() {
        protocol.subscribe(kit.channel, List.of(kit.table.get(SLUG), kit.table.get("other-market")));
        protocol.keepAlive(kit.channel);

        assertEquals(
                List.of(
                        subscribe("m0.1", "MARKET_DATA", SLUG, "other-market"),
                        subscribe("t0.1", "TRADE", SLUG, "other-market")),
                kit.sent);
        assertEquals(List.of("ping"), kit.pings);
    }

    @Test
    void aGroupWhoseMarketsChangeIsReplacedUnderNewIds() {
        protocol.subscribe(kit.channel, List.of(kit.table.get(SLUG)));
        protocol.subscribe(kit.channel, List.of(kit.table.get("other-market")));
        protocol.unsubscribe(kit.channel, List.of(kit.table.get(SLUG)));
        protocol.unsubscribe(kit.channel, List.of(kit.table.get("other-market")));

        assertEquals(
                List.of(
                        subscribe("m0.1", "MARKET_DATA", SLUG),
                        subscribe("t0.1", "TRADE", SLUG),
                        // Dropped before the new ones: a market may be in only one subscription at a time.
                        unsubscribe("m0.1"),
                        unsubscribe("t0.1"),
                        subscribe("m0.2", "MARKET_DATA", SLUG, "other-market"),
                        subscribe("t0.2", "TRADE", SLUG, "other-market"),
                        unsubscribe("m0.2"),
                        unsubscribe("t0.2"),
                        subscribe("m0.3", "MARKET_DATA", "other-market"),
                        subscribe("t0.3", "TRADE", "other-market"),
                        unsubscribe("m0.3"),
                        unsubscribe("t0.3")),
                kit.sent);
    }

    @Test
    void aFullConnectionNeverHoldsMoreThanTenSubscriptions() {
        List<Subject> markets = new ArrayList<>();
        int total = PolymarketUsProtocol.GROUPS * PolymarketUsProtocol.MARKETS_PER_GROUP;
        for (int i = 0; i < total + 1; i++) {
            kit.market("m-" + i);
            markets.add(kit.table.get("m-" + i));
        }
        for (int from = 0; from < total; from += PolymarketUsProtocol.MARKETS_PER_GROUP) {
            protocol.subscribe(kit.channel, markets.subList(from, from + PolymarketUsProtocol.MARKETS_PER_GROUP));
        }

        Set<String> open = new HashSet<>();
        Set<String> subscribedMarkets = new HashSet<>();
        int peak = 0;
        for (String sent : kit.sent) {
            String id = sent.replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1");
            if (sent.startsWith("{\"subscribe\"")) {
                open.add(id);
            } else {
                open.remove(id);
            }
            peak = Math.max(peak, open.size());
        }
        for (String sent : kit.sent) {
            if (sent.contains("MARKET_DATA")
                    && open.contains(sent.replaceAll(".*\"requestId\":\"([^\"]+)\".*", "$1"))) {
                for (String slug : sent.replaceAll(".*\\[\"(.*)\"\\].*", "$1").split("\",\"")) {
                    assertTrue(subscribedMarkets.add(slug), slug + " is in one subscription");
                }
            }
        }
        assertEquals(10, peak);
        assertEquals(total, subscribedMarkets.size());
        assertEquals(PolymarketUsProtocol.GROUPS * 2, kit.sent.size(), "each group subscribed once, full");
        assertThrows(
                IllegalStateException.class, () -> protocol.subscribe(kit.channel, markets.subList(total, total + 1)));
    }

    @Test
    void aNewConnectionHasNoSubscriptionsToDrop() {
        protocol.subscribe(kit.channel, List.of(kit.table.get(SLUG)));
        protocol.onConnected();
        kit.sent.clear();
        protocol.subscribe(kit.channel, List.of(kit.table.get(SLUG)));

        assertEquals(List.of(subscribe("m0.2", "MARKET_DATA", SLUG), subscribe("t0.2", "TRADE", SLUG)), kit.sent);
    }

    @Test
    void aVenueErrorIsLoggedNotMistakenForABook() throws Exception {
        frame("{\"requestId\":\"m0.1\",\"error\":\"max subscriptions per connection reached\","
                + "\"subscriptionType\":\"SUBSCRIPTION_TYPE_MARKET_DATA\"}");
        frame("{\"requestId\":\"t0.1\",\"trade\":{\"marketSlug\":\"not-ours\",\"price\":" + amount("0.5")
                + ",\"quantity\":" + amount("1") + "}}");
        assertTrue(legs[0].drain().isEmpty());
        assertTrue(other.drain().isEmpty());
    }

    @Test
    void theUpgradeIsSigned() throws Exception {
        List<String> headers = new ArrayList<>();
        doAnswer(inv -> {
                    headers.add(inv.getArgument(0));
                    return null;
                })
                .when(kit.client)
                .setHeader(any(), any());

        protocol.authenticate(kit.channel);

        assertEquals(List.of("X-PM-Access-Key", "X-PM-Timestamp", "X-PM-Signature"), headers);
    }
}
