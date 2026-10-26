package group.gnometrading.gateways.venues.polymarket.intl;

import static group.gnometrading.gateways.feed.VenueTestKit.price;
import static group.gnometrading.gateways.feed.probe.ProbeTestKit.fetch;
import static group.gnometrading.gateways.feed.probe.ProbeTestKit.ok;
import static group.gnometrading.gateways.feed.probe.ProbeTestKit.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import org.junit.jupiter.api.Test;

class PolymarketIntlBookProbeTest {

    private final HTTPClient http = mock(HTTPClient.class);
    private final Book book = new Book(64);

    @Test
    void aFailedRequestIsNoAnswer() throws Exception {
        when(http.get(any(HTTPProtocol.class), anyString(), anyString())).thenReturn(response(false, null));

        assertFalse(fetch(new PolymarketIntlBookProbe(http, "clob"), "tok", book));
    }

    @Test
    void levelsComeBestLastButLandBestFirst() throws Exception {
        when(http.get(HTTPProtocol.HTTPS, "clob", "/book?token_id=tok"))
                .thenReturn(ok("{\"market\":\"c\",\"asset_id\":\"tok\",\"bids\":[{\"price\":\"0.01\",\"size\":\"5\"},"
                        + "{\"price\":\"0.08\",\"size\":\"3\"}],\"asks\":[{\"price\":\"0.99\",\"size\":\"1\"},"
                        + "{\"price\":\"0.10\",\"size\":\"6\"}],\"hash\":\"h\"}"));

        assertTrue(fetch(new PolymarketIntlBookProbe(http, "clob"), "tok", book));
        assertEquals(price("0.08"), book.bids().price(0));
        assertEquals(price("0.10"), book.asks().price(0));
    }
}
