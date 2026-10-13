package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strings.GnomeString;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PolymarketUsVenueOrderQueryTest {

    private static final String SLUG = "tec-mlb-champ-2026-09-27-lad";
    private static final String STAMP = PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(17, 3));
    private static final long OPENED_MS = 1_791_469_000_000L;

    private HTTPClient httpClient;
    private PolymarketUsVenueOrderQuery query;
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(HTTPClient.class);
        when(httpClient.get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(call -> {
                    final String path = call.getArgument(2);
                    paths.add(path);
                    String best = null;
                    for (final String prefix : answers.keySet()) {
                        if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                            best = prefix;
                        }
                    }
                    return response(best == null ? 404 : 200, best == null ? "{}" : answers.get(best));
                });
        query = new PolymarketUsVenueOrderQuery(
                httpClient,
                "api.test",
                new PolymarketUsAuthSigner(
                        "key",
                        KeyPairGenerator.getInstance("Ed25519")
                                .generateKeyPair()
                                .getPrivate()),
                () -> OPENED_MS);
    }

    @Test
    void openOrders_AreKnownByTheirStamp_OrTheVenueIdWithoutOne() throws Exception {
        answers.put(
                "/v1/orders/open",
                "{\"orders\":[" + order("V1", "ORDER_STATE_NEW", "BUY_LONG", 2, 0, "0", STAMP) + ","
                        + order("MANUAL", "ORDER_STATE_NEW", "BUY_LONG", 1, 0, "0", null) + "]}");

        final List<VenueOrder> open = query.listOpenOrders(listing(":long"));

        assertEquals("/v1/orders/open?slugs=" + SLUG, paths.get(0));
        assertEquals(new VenueOrder(STAMP, "V1", 0, 0, 0, false), open.get(0));
        assertEquals(new VenueOrder("MANUAL", "MANUAL", 0, 0, 0, false), open.get(1));
    }

    @Test
    void noOrdersFills_AreValuedInNoTerms() throws Exception {
        answers.put(
                "/v1/orders/open",
                "{\"orders\":[" + order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_SHORT", 4, 2, "0.4000", STAMP)
                        + "]}");

        final VenueOrder order = query.listOpenOrders(listing(":short")).get(0);

        assertEquals(qty("2"), order.filledQty());
        assertEquals(price("1.2"), order.filledNotional());
    }

    @Test
    void getOrder_AfterListing_GoesStraightToTheOrder() throws Exception {
        answers.put(
                "/v1/orders/open",
                "{\"orders\":[" + order("V1", "ORDER_STATE_NEW", "BUY_LONG", 2, 0, "0", STAMP) + "]}");
        answers.put(
                "/v1/order/V1",
                "{\"order\":" + order("V1", "ORDER_STATE_CANCELED", "BUY_LONG", 2, 1, "0.4400", STAMP, "0.0200") + "}");
        query.listOpenOrders(listing(":long"));

        final Optional<VenueOrder> order = query.getOrder(listing(":long"), STAMP, 0);

        assertEquals(new VenueOrder(STAMP, "V1", qty("1"), price("0.44"), price("0.02"), true), order.orElseThrow());
        assertEquals(List.of("/v1/orders/open?slugs=" + SLUG, "/v1/order/V1"), paths);
    }

    @Test
    void finishedOrder_IsFoundAmongTheMarketsTrades_OnOurSideOfThem() throws Exception {
        answers.put("/v1/orders/open", "{\"orders\":[]}");
        answers.put(
                "/v1/portfolio/activities",
                "{\"activities\":["
                        + trade("2026-10-08T14:40:00Z", true, "OURS-AGGR", null, "THEIRS", STAMP)
                        + "," + trade("2026-10-08T14:38:00Z", false, "THEIRS2", STAMP, "OURS", STAMP)
                        + "],\"nextCursor\":\"\",\"eof\":true}");
        answers.put(
                "/v1/order/OURS",
                "{\"order\":" + order("OURS", "ORDER_STATE_FILLED", "BUY_LONG", 1, 1, "0.4400", STAMP, "0.0200") + "}");

        final Optional<VenueOrder> order = query.getOrder(listing(":long"), STAMP, OPENED_MS);

        assertEquals("OURS", order.orElseThrow().venueId());
        assertTrue(
                paths.get(1).startsWith("/v1/portfolio/activities?marketSlug=" + SLUG + "&types=ACTIVITY_TYPE_TRADE"),
                paths.toString());
    }

    @Test
    void finishedOrderThatNeverTraded_IsNotFound() throws Exception {
        answers.put("/v1/orders/open", "{\"orders\":[]}");
        answers.put("/v1/portfolio/activities", "{\"activities\":[],\"nextCursor\":\"\",\"eof\":true}");

        assertTrue(query.getOrder(listing(":long"), STAMP, OPENED_MS).isEmpty());
    }

    @Test
    void tradesOlderThanTheOrder_EndTheSearch() throws Exception {
        answers.put("/v1/orders/open", "{\"orders\":[]}");
        answers.put(
                "/v1/portfolio/activities",
                "{\"activities\":["
                        + trade("2026-10-01T00:00:00Z", true, "OLD", STAMP, "X", null)
                        + "],\"nextCursor\":\"more\",\"eof\":false}");

        assertTrue(query.getOrder(listing(":long"), STAMP, OPENED_MS).isEmpty());
        assertEquals(2, paths.size());
    }

    @Test
    void anIocCantBeLookedUp() throws Exception {
        assertTrue(query.getOrder(listing(":long"), "ioc-17-4", OPENED_MS).isEmpty());
        assertTrue(paths.isEmpty());
    }

    @Test
    void cancel_PostsToTheOrdersCancelPath() throws Exception {
        final HTTPResponse ok = response(200, "{}");
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(ok);

        assertTrue(query.cancel(listing(":long"), new VenueOrder(STAMP, "V1", 0, 0, 0, false)));

        final ArgumentCaptor<GnomeString> path = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient)
                .post(
                        any(),
                        anyString(),
                        path.capture(),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertEquals("/v1/order/V1/cancel", path.getValue().toString());
        assertFalse(paths.contains("/v1/orders/open"));
    }

    private static Listing listing(final String suffix) {
        return new Listing(
                7,
                new Exchange(2, "POLYMARKET_US", "Polymarket US", "us", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                SLUG + suffix,
                "TEST");
    }

    private static HTTPResponse response(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static String trade(
            final String createTime,
            final boolean isAggressor,
            final String aggressorId,
            final String aggressorStamp,
            final String passiveId,
            final String passiveStamp) {
        return "{\"type\":\"ACTIVITY_TYPE_TRADE\",\"trade\":{\"id\":\"T\",\"aggressorExecution\":{\"id\":\"E1\","
                + "\"order\":" + order(aggressorId, "ORDER_STATE_FILLED", "BUY_LONG", 1, 1, "0.4400", aggressorStamp)
                + ",\"type\":\"EXECUTION_TYPE_FILL\"},\"passiveExecution\":{\"id\":\"E2\",\"order\":"
                + order(passiveId, "ORDER_STATE_FILLED", "SELL_LONG", 1, 1, "0.4400", passiveStamp)
                + ",\"type\":\"EXECUTION_TYPE_FILL\"},\"marketSlug\":\"" + SLUG + "\",\"createTime\":\"" + createTime
                + "\",\"qty\":1,\"isAggressor\":" + isAggressor + "}}";
    }

    private static String order(
            final String id,
            final String state,
            final String intent,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime) {
        return order(id, state, intent, quantity, cumQuantity, avgPx, goodTillTime, "0.0000");
    }

    private static String order(
            final String id,
            final String state,
            final String intent,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime,
            final String commission) {
        return "{\"id\":\"" + id + "\",\"marketSlug\":\"" + SLUG + "\",\"price\":{\"value\":\"0.44\",\"currency\":"
                + "\"USD\"},\"quantity\":" + quantity + ",\"cumQuantity\":" + cumQuantity + ",\"goodTillTime\":"
                + (goodTillTime == null ? "null" : "\"" + goodTillTime + "\"") + ",\"intent\":\"ORDER_INTENT_"
                + intent + "\",\"state\":\"" + state + "\",\"commissionNotionalTotalCollected\":{\"value\":\""
                + commission + "\",\"currency\":\"USD\"},\"avgPx\":{\"value\":\"" + avgPx + "\",\"currency\":\"USD\"}}";
    }

    private static long price(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long qty(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
