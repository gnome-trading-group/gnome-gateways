package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlVenueOrderQuery;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.SchemaType;
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
            new Exchange(2, "POLYMARKET", "Polymarket", "global", SchemaType.MBP_10),
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
    void anOrderTheVenueDoesntKnowIsAbsent() throws IOException {
        respond(404, "");
        assertFalse(query.getOrder(LISTING, HASH, 0).isPresent());
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

    private void respond(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        responses.add(response);
    }
}
