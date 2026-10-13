package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolymarketUsMarketInfoTest {

    @Test
    void readsTheTickFromTheMarket() {
        final PolymarketUsMarketInfo info = PolymarketUsMarketInfo.parse(body("{\"market\":{\"id\":\"7935\","
                + "\"slug\":\"tec-mlb-champ-2026-09-27-lad\",\"active\":true,\"orderPriceMinTickSize\":0.001,"
                + "\"outcomes\":\"[\\\"Yes\\\",\\\"No\\\"]\",\"feeCoefficient\":0.0695,\"minimumTradeQty\":1}}"));

        assertEquals(Statics.PRICE_SCALING_FACTOR / 1000, info.tickSize());
        assertEquals(Statics.SIZE_SCALING_FACTOR, info.lotSize());
    }

    @Test
    void refusesAMarketWithoutATick() {
        assertThrows(
                IllegalStateException.class, () -> PolymarketUsMarketInfo.parse(body("{\"market\":{\"id\":\"1\"}}")));
    }

    @Test
    void loadAsksAgainWhenTheVenueRefusesForItsRateLimit() throws Exception {
        final HTTPClient httpClient = mock(HTTPClient.class);
        final HTTPResponse limited = response(429, "");
        final HTTPResponse market =
                response(200, "{\"market\":{\"orderPriceMinTickSize\":0.01,\"minimumTradeQty\":1}}");
        when(httpClient.get(any(), anyString(), anyString())).thenReturn(limited, market);

        final PolymarketUsMarketInfo info = PolymarketUsMarketInfo.load(httpClient, "gateway", "slug");

        assertEquals(Statics.PRICE_SCALING_FACTOR / 100, info.tickSize());
        verify(httpClient, times(2)).get(any(), anyString(), anyString());
    }

    @Test
    void loadGivesUpAfterItsAttempts() throws Exception {
        final HTTPClient httpClient = mock(HTTPClient.class);
        final HTTPResponse limited = response(429, "");
        when(httpClient.get(any(), anyString(), anyString())).thenReturn(limited);

        assertThrows(IOException.class, () -> PolymarketUsMarketInfo.load(httpClient, "gateway", "slug"));
        verify(httpClient, times(3)).get(any(), anyString(), anyString());
    }

    private static HTTPResponse response(final int status, final String json) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getBody()).thenReturn(body(json));
        return response;
    }

    private static ByteBuffer body(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }
}
