package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiVenueOrderQuery;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiVenueOrderQueryTest {

    private static final Listing NO_LISTING = new Listing(
            501,
            new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
            new Security(3, "T", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
            "KXT-26:no",
            "KXT");

    private final HTTPClient httpClient = mock(HTTPClient.class);
    private final KalshiAuthSigner signer = mock(KalshiAuthSigner.class);
    private final List<String> paths = new ArrayList<>();
    private final Deque<HTTPResponse> responses = new ArrayDeque<>();
    private KalshiVenueOrderQuery query;

    @BeforeEach
    void setUp() throws IOException {
        when(signer.apiKey()).thenReturn("key");
        when(signer.timestamp()).thenReturn("1");
        when(signer.signature()).thenReturn("sig");
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
                    paths.add(call.getArgument(2));
                    return responses.poll();
                });
        when(httpClient.delete(
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
                    paths.add("DELETE " + call.getArgument(2));
                    return responses.poll();
                });
        query = new KalshiVenueOrderQuery(httpClient, "api.test", signer, () -> 1_700_000_000_000L);
    }

    @Test
    void listsEveryPageOfTheTickersRestingOrders() throws IOException {
        respond(200, "{\"orders\":[" + order("4071-501-3", "k-1", "resting", "4.00", "6.00") + "],\"cursor\":\"c2\"}");
        respond(200, "{\"orders\":[" + order("manual", "k-2", "resting", "0.00", "1.00") + "],\"cursor\":\"\"}");

        final List<VenueOrder> orders = query.listOpenOrders(NO_LISTING);

        assertEquals(
                List.of("4071-501-3", "manual"),
                orders.stream().map(VenueOrder::exchangeOrderId).toList());
        assertTrue(paths.get(0).startsWith("/trade-api/v2/portfolio/orders?ticker=KXT-26&status=resting"));
        assertTrue(paths.get(1).endsWith("&cursor=c2"));
    }

    @Test
    void readsFillsCostAndFeesAcrossMakerAndTaker() throws IOException {
        respond(
                200,
                "{\"orders\":[{\"client_order_id\":\"4071-501-3\",\"order_id\":\"k-1\",\"status\":\"canceled\","
                        + "\"fill_count_fp\":\"4.00\",\"remaining_count_fp\":\"0.00\","
                        + "\"taker_fill_cost_dollars\":\"1.2000\",\"maker_fill_cost_dollars\":\"0.8000\","
                        + "\"taker_fees_dollars\":\"0.0300\",\"maker_fees_dollars\":\"0.0100\",\"ticker\":\"KXT-26\"}],"
                        + "\"cursor\":\"\"}");

        final VenueOrder order = query.listOpenOrders(NO_LISTING).get(0);

        assertEquals("k-1", order.venueId());
        assertEquals(4_000_000, order.filledQty());
        assertEquals(2_000_000_000L, order.filledNotional());
        assertEquals(40_000_000L, order.fees());
        assertTrue(order.terminal());
    }

    @Test
    void findsAnOrderByClientOrderIdAmongRecentOrders() throws IOException {
        respond(
                200,
                "{\"orders\":[" + order("other", "k-9", "executed", "1.00", "0.00") + ","
                        + order("4071-501-3", "k-1", "executed", "5.00", "0.00") + "],\"cursor\":\"\"}");

        final VenueOrder order =
                query.getOrder(NO_LISTING, "4071-501-3", 1_700_000_100_000L).orElseThrow();

        assertEquals(5_000_000, order.filledQty());
        assertTrue(paths.get(0).contains("&min_ts=1700000040"), "a minute before it was placed");
    }

    @Test
    void anOrderKalshiDoesntHaveIsAbsent() throws IOException {
        respond(200, "{\"orders\":[],\"cursor\":\"\"}");
        assertFalse(query.getOrder(NO_LISTING, "4071-501-3", 0).isPresent());
    }

    @Test
    void cancelsByKalshisOwnOrderId() throws IOException {
        respond(200, "{}");
        final VenueOrder order = new VenueOrder("4071-501-3", "k-1", null, 0, 0, 0, 0, 0, false);
        assertTrue(query.cancel(NO_LISTING, order));
        assertEquals("DELETE /trade-api/v2/portfolio/events/orders/k-1", paths.get(0));
    }

    @Test
    void aFailedListingIsAnError() {
        respond(503, "");
        assertThrows(IOException.class, () -> query.listOpenOrders(NO_LISTING));
    }

    private static String order(
            final String clientOrderId,
            final String orderId,
            final String status,
            final String filled,
            final String remaining) {
        return "{\"client_order_id\":\"" + clientOrderId + "\",\"order_id\":\"" + orderId + "\",\"status\":\"" + status
                + "\",\"fill_count_fp\":\"" + filled + "\",\"remaining_count_fp\":\"" + remaining + "\"}";
    }

    private void respond(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        responses.add(response);
    }
}
