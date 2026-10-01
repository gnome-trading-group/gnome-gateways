package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolymarketIntlMarketInfoTest {

    // Captured from GET https://clob.polymarket.com/clob-markets/{condition_id} on 2026-10-01.
    private static final String LIVE_RESPONSE =
            "{\"gst\":\"2026-10-01T18:45:00Z\",\"r\":{\"mi\":50,\"ma\":4.5,\"moas\":30},"
                    + "\"t\":[{\"t\":\"30832906867199915342888320351856964164180073964149724713946648532860513266622\",\"o\":\"Yes\"},"
                    + "{\"t\":\"78546959674779340117340396156968781977757001842497240027375881342764329442735\",\"o\":\"No\"}],"
                    + "\"c\":\"0x851181d7131741f90700890797ee7d8a01d4611930bc809f2272425a5f8f350b\",\"sd\":1,\"mos\":5,"
                    + "\"mts\":0.01,\"mbf\":1000,\"tbf\":1000,\"ao\":true,\"nr\":true,\"cbos\":true,"
                    + "\"aot\":\"2026-09-18T10:00:25Z\",\"ibce\":true,\"fd\":{\"r\":0.05,\"e\":1,\"to\":true},\"v\":\"v1\"}";

    @Test
    void parsesLiveResponse() {
        final PolymarketIntlMarketInfo info = PolymarketIntlMarketInfo.parse(bytes(LIVE_RESPONSE));

        assertEquals(Statics.PRICE_SCALING_FACTOR / 100, info.tickSize());
        assertEquals(5 * Statics.SIZE_SCALING_FACTOR, info.minOrderSize());
        assertTrue(info.negRisk());
        assertEquals(0.05, info.feeRate(), 1e-12);
        assertEquals(1.0, info.feeExponent(), 1e-12);
        assertTrue(info.feeTakerOnly());
        assertFalse(info.takerOrderDelay(), "itode is omitted when false");
        assertEquals(0, info.minOrderAgeSeconds());
    }

    @Test
    void parsesDocumentedOptionalFields() {
        final PolymarketIntlMarketInfo info = PolymarketIntlMarketInfo.parse(
                bytes("{\"mos\":5,\"mts\":0.001,\"itode\":true,\"fd\":{\"r\":0.02,\"e\":2,\"to\":true},\"oas\":60}"));

        assertEquals(Statics.PRICE_SCALING_FACTOR / 1000, info.tickSize());
        assertFalse(info.negRisk());
        assertEquals(2.0, info.feeExponent(), 1e-12);
        assertTrue(info.takerOrderDelay());
        assertEquals(60, info.minOrderAgeSeconds());
    }

    @Test
    void missingTickSizeIsAnError() {
        assertThrows(IllegalStateException.class, () -> PolymarketIntlMarketInfo.parse(bytes("{\"mos\":5}")));
    }

    @Test
    void loadChecksTheVenueOrderVersionFirst() throws Exception {
        final HTTPClient httpClient = mock(HTTPClient.class);
        final HTTPResponse version = response("{\"version\":3}");
        when(httpClient.get(HTTPProtocol.HTTPS, "clob.polymarket.com", "/version"))
                .thenReturn(version);

        assertThrows(
                IllegalStateException.class,
                () -> PolymarketIntlMarketInfo.load(httpClient, "clob.polymarket.com", "0xabc"));
        verify(httpClient, never()).get(eq(HTTPProtocol.HTTPS), anyString(), eq("/clob-markets/0xabc"));
    }

    @Test
    void loadFetchesTheMarketWhenTheVersionMatches() throws Exception {
        final HTTPClient httpClient = mock(HTTPClient.class);
        final HTTPResponse version = response("{\"version\":2}");
        final HTTPResponse market = response(LIVE_RESPONSE);
        when(httpClient.get(HTTPProtocol.HTTPS, "clob.polymarket.com", "/version"))
                .thenReturn(version);
        when(httpClient.get(HTTPProtocol.HTTPS, "clob.polymarket.com", "/clob-markets/0xabc"))
                .thenReturn(market);

        assertTrue(PolymarketIntlMarketInfo.load(httpClient, "clob.polymarket.com", "0xabc")
                .negRisk());
    }

    private static HTTPResponse response(final String json) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getBody()).thenReturn(bytes(json));
        return response;
    }

    private static ByteBuffer bytes(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }
}
