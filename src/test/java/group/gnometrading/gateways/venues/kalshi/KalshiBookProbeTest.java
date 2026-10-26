package group.gnometrading.gateways.venues.kalshi;

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

class KalshiBookProbeTest {

    private final HTTPClient http = mock(HTTPClient.class);
    private final Book book = new Book(64);

    @Test
    void noBidsAreYesAsksAtOneMinusThePrice() throws Exception {
        when(http.get(HTTPProtocol.HTTPS, "api", "/trade-api/v2/markets/T/orderbook?depth=10"))
                .thenReturn(ok("{\"orderbook_fp\":{\"yes_dollars\":[[\"0.4000\",\"10.00\"],[\"0.4500\",\"5.00\"]],"
                        + "\"no_dollars\":[[\"0.5000\",\"7.00\"],[\"0.5300\",\"2.00\"]]}}"));

        assertTrue(fetch(new KalshiBookProbe(http, "api"), "T", book));

        assertEquals(price("0.45"), book.bids().price(0));
        assertEquals(size("5"), book.bids().size(0));
        assertEquals(price("0.47"), book.asks().price(0), "NO bid 0.53 is a YES ask at 0.47");
        assertEquals(size("2"), book.asks().size(0));
        assertEquals(price("0.50"), book.asks().price(1));
    }

    @Test
    void anEmptySideIsStillAnAnswer() throws Exception {
        when(http.get(HTTPProtocol.HTTPS, "api", "/trade-api/v2/markets/T/orderbook?depth=10"))
                .thenReturn(ok("{\"orderbook_fp\":{\"yes_dollars\":[[\"0.4000\",\"10.00\"]],\"no_dollars\":null}}"));

        assertTrue(fetch(new KalshiBookProbe(http, "api"), "T", book));
        assertEquals(0, book.asks().depth());
    }
}
