package group.gnometrading.gateways.venues.lighter;

import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.VenueTestKit.size;
import static group.gnometrading.gateways.feed.probe.ProbeTestKit.fetch;
import static group.gnometrading.gateways.feed.probe.ProbeTestKit.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import org.junit.jupiter.api.Test;

class LighterBookProbeTest {

    private final HTTPClient http = mock(HTTPClient.class);
    private final Book book = new Book(64);

    @Test
    void ordersOnOnePriceAddUp() throws Exception {
        when(http.get(HTTPProtocol.HTTPS, "lt", "/api/v1/orderBookOrders?market_id=0&limit=250"))
                .thenReturn(ok("{\"code\":200,\"total_asks\":2,\"asks\":[{\"order_index\":1,\"price\":\"4212.00\","
                        + "\"remaining_base_amount\":\"1.5\"},{\"order_index\":2,\"price\":\"4212.00\","
                        + "\"remaining_base_amount\":\"0.5\"}],\"total_bids\":1,\"bids\":[{\"order_index\":3,"
                        + "\"price\":\"4210.00\",\"remaining_base_amount\":\"3\"}]}"));

        assertTrue(fetch(new LighterBookProbe(http, "lt"), "0", book));
        assertEquals(price("4212"), book.asks().price(0));
        assertEquals(size("2"), book.asks().size(0));
        assertEquals(price("4210"), book.bids().price(0));
    }
}
