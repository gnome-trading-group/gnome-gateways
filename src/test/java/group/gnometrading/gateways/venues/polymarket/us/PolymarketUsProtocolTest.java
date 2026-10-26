package group.gnometrading.gateways.venues.polymarket.us;

import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.VenueTestKit.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import group.gnometrading.gateways.feed.VenueTestKit;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
        return "{\"requestId\":\"m." + slug + "\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_MARKET_DATA\","
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
    void marketsAreToldApartByTheirRequestId() throws Exception {
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
        frame("{\"requestId\":\"t." + SLUG + "\",\"trade\":{\"marketSlug\":\"" + SLUG + "\",\"price\":"
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

    @Test
    void eachMarketHasItsOwnPairOfSubscriptions() {
        protocol.subscribe(kit.channel, List.of(kit.table.get(SLUG)));
        protocol.unsubscribe(kit.channel, List.of(kit.table.get(SLUG)));
        protocol.keepAlive(kit.channel);

        assertEquals(
                List.of(
                        "{\"subscribe\":{\"requestId\":\"m." + SLUG + "\",\"subscriptionType\":"
                                + "\"SUBSCRIPTION_TYPE_MARKET_DATA\",\"marketSlugs\":[\"" + SLUG + "\"]}}",
                        "{\"subscribe\":{\"requestId\":\"t." + SLUG + "\",\"subscriptionType\":"
                                + "\"SUBSCRIPTION_TYPE_TRADE\",\"marketSlugs\":[\"" + SLUG + "\"]}}",
                        "{\"unsubscribe\":{\"requestId\":\"m." + SLUG + "\"}}",
                        "{\"unsubscribe\":{\"requestId\":\"t." + SLUG + "\"}}"),
                kit.sent);
        assertEquals(List.of("ping"), kit.pings);
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
