package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketIntlVenueOrderQueryTest {

    private static final String HASH = "0x" + "ab".repeat(32);
    private static final Listing LISTING = new Listing(
            1,
            new Exchange(2, "POLYMARKET", "Polymarket", "global"),
            new Security(3, "T", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
            "0xc:777",
            "T");

    private final HTTPClient httpClient = mock(HTTPClient.class);
    private final PolymarketIntlAuthHeaders auth = mock(PolymarketIntlAuthHeaders.class);
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final Deque<HTTPResponse> responses = new ArrayDeque<>();
    private PolymarketIntlVenueOrderQuery query;

    @BeforeEach
    void setUp() throws IOException {
        when(auth.apiKey()).thenReturn("k");
        when(auth.signature()).thenReturn("s");
        when(auth.timestamp()).thenReturn("1");
        when(auth.passphrase()).thenReturn("p");
        when(auth.address()).thenReturn("0xa");
        when(httpClient.get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(call -> {
                    paths.add(call.getArgument(2));
                    return responses.poll();
                });
        when(httpClient.delete(
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
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(call -> {
                    final byte[] body = call.getArgument(3);
                    final int length = call.getArgument(4);
                    bodies.add(new String(body, 0, length, StandardCharsets.UTF_8));
                    return responses.poll();
                });
        query = new PolymarketIntlVenueOrderQuery(httpClient, "clob.test", auth);
    }

    @Test
    void listsEveryPageOfTheTokensOrdersUntilTheEndCursor() throws IOException {
        respond(200, "{\"data\":[" + order(HASH, "LIVE", "BUY", "0.42", "10", "4") + "],\"next_cursor\":\"MTA=\"}");
        respond(200, "{\"data\":[],\"next_cursor\":\"LTE=\"}");

        final List<VenueOrder> orders = query.listOpenOrders(LISTING);

        assertEquals(1, orders.size());
        assertEquals("/data/orders?asset_id=777", paths.get(0));
        assertEquals("/data/orders?asset_id=777&next_cursor=MTA=", paths.get(1));
        final VenueOrder order = orders.get(0);
        assertEquals(HASH, order.exchangeOrderId());
        assertEquals(4_000_000, order.filledQty());
        assertEquals(1_680_000_000L, order.filledNotional(), "4 matched at the 42c limit");
        assertFalse(order.terminal());
    }

    @Test
    void anOrderThatMatchedOrWasCancelledIsTerminal() throws IOException {
        respond(200, order(HASH, "MATCHED", "SELL", "0.42", "10", "10"));
        final VenueOrder order = query.getOrder(LISTING, HASH, 0).orElseThrow();
        assertTrue(order.terminal());
        assertEquals("/data/order/" + HASH, paths.get(0));
    }

    @Test
    void anOrderTheVenueDoesntKnowAndNoTradeNamesIsAbsent() throws IOException {
        respond(404, "");
        respond(200, "{\"data\":[],\"next_cursor\":\"LTE=\"}");
        assertFalse(query.getOrder(LISTING, HASH, 120_000).isPresent());
        assertEquals("/data/trades?market=0xc&after=60", paths.get(1), "its market's trades, from a minute before it");
    }

    @Test
    void aMatchedOrderTheLookupForgetsIsFoundInTheTradeThatFilledIt() throws IOException {
        // Captured: the CLOB answers a fully matched order's lookup with a bare null. Ours is the 38th of the trade's
        // 40 resting orders.
        final String ours = "0x351699e67620c414e30b67643815b7fc53dba4505e8d6388d4a1419cf690085b";
        respond(200, "null");
        respond(200, resource("polymarket-intl/trade-40-makers.json"));
        final VenueOrder order =
                query.getOrder(LISTING, ours, 1_794_955_249_150L).orElseThrow();
        assertEquals(10_000_000L, order.filledQty());
        assertEquals(6_100_000_000L, order.filledNotional(), "10 at its 61c");
        assertTrue(order.terminal());
    }

    @Test
    void aTakerFilledTheTradesSizeAtItsMakersPricesTheOtherOutcomesAtTheComplement() throws IOException {
        respond(404, "");
        respond(
                200,
                "{\"data\":[{\"status\":\"MATCHED\",\"taker_order_id\":\"" + HASH + "\",\"asset_id\":\"777\","
                        + "\"size\":\"5\",\"maker_orders\":["
                        + "{\"order_id\":\"0x1\",\"asset_id\":\"777\",\"matched_amount\":\"2\",\"price\":\"0.40\"},"
                        + "{\"order_id\":\"0x2\",\"asset_id\":\"888\",\"matched_amount\":\"3\",\"price\":\"0.55\"}]},"
                        + "{\"status\":\"FAILED\",\"taker_order_id\":\"" + HASH + "\",\"asset_id\":\"777\","
                        + "\"size\":\"9\",\"maker_orders\":[]}],\"next_cursor\":\"LTE=\"}");
        final VenueOrder order = query.getOrder(LISTING, HASH, 0).orElseThrow();
        assertEquals(5_000_000L, order.filledQty(), "the failed trade filled nothing");
        assertEquals(2_150_000_000L, order.filledNotional(), "2 at 40c and 3 at 45c, the complement of 55c");
    }

    @Test
    void aCancelCountsOnlyWhenTheVenueListsItAsCancelled() throws IOException {
        final VenueOrder order = new VenueOrder(HASH, HASH, 0, 0, 0, false);
        respond(200, "{\"canceled\":[\"" + HASH + "\"],\"not_canceled\":{}}");
        assertTrue(query.cancel(LISTING, order));
        assertEquals("{\"orderID\":\"" + HASH + "\"}", bodies.get(0));

        respond(200, "{\"canceled\":[],\"not_canceled\":{\"" + HASH + "\":\"order is already matched\"}}");
        assertFalse(query.cancel(LISTING, order));
    }

    @Test
    void listAllOpenOrders_ReadsEveryPageOfTheAccount_WithEachOrdersTokenAndCreationTime() throws IOException {
        final String other = "0x" + "cd".repeat(32);
        respond(
                200,
                "{\"data\":[" + withCreated(order(HASH, "LIVE", "BUY", "0.42", "10", "4"), "1699999000")
                        + "],\"next_cursor\":\"MTA=\"}");
        respond(
                200,
                "{\"data\":["
                        + withCreated(order(other, "LIVE", "SELL", "0.60", "5", "0"), "\"1699999500\"")
                                .replace("\"777\"", "\"888\"")
                        + "],\"next_cursor\":\"LTE=\"}");

        final List<AccountOrder> orders = query.listAllOpenOrders();

        assertEquals(List.of("/data/orders", "/data/orders?next_cursor=MTA="), paths);
        assertEquals(
                new AccountOrder(
                        new VenueOrder(HASH, HASH, 4_000_000, 1_680_000_000L, 0, false), "777", 1_699_999_000_000L),
                orders.get(0));
        assertEquals(
                new AccountOrder(new VenueOrder(other, other, 0, 0, 0, false), "888", 1_699_999_500_000L),
                orders.get(1),
                "a quoted creation time reads the same");
    }

    @Test
    void cancelAccountOrder_CancelsByItsHash() throws IOException {
        final AccountOrder order = new AccountOrder(new VenueOrder(HASH, HASH, 0, 0, 0, false), "777", 0);
        respond(200, "{\"canceled\":[\"" + HASH + "\"],\"not_canceled\":{}}");
        assertTrue(query.cancel(order));
        assertEquals("{\"orderID\":\"" + HASH + "\"}", bodies.get(0));

        respond(200, "{\"canceled\":[],\"not_canceled\":{\"" + HASH + "\":\"order is already matched\"}}");
        assertFalse(query.cancel(order));
    }

    private static String withCreated(final String order, final String createdAt) {
        return order.substring(0, order.length() - 1) + ",\"created_at\":" + createdAt + "}";
    }

    private static String order(
            final String id,
            final String status,
            final String side,
            final String price,
            final String size,
            final String matched) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"side\":\"" + side + "\",\"price\":\"" + price
                + "\",\"original_size\":\"" + size + "\",\"size_matched\":\"" + matched + "\",\"asset_id\":\"777\"}";
    }

    private static String resource(final String name) throws IOException {
        try (var in = PolymarketIntlVenueOrderQueryTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        responses.add(response);
    }
}
