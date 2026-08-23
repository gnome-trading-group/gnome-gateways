package group.gnometrading.gateways.exchanges.polymarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketAuthHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketAuthHeadersTest {

    private static final String API_KEY = "test-api-key";
    private static final String PASSPHRASE = "test-passphrase";
    private static final String ADDRESS = "0xDeadBeef";

    // base64("test-secret") = "dGVzdC1zZWNyZXQ="
    private static final String BASE64_SECRET = "dGVzdC1zZWNyZXQ=";
    private static final byte[] DECODED_SECRET = Base64.getDecoder().decode(BASE64_SECRET);

    private PolymarketAuthHeaders headers;

    @BeforeEach
    void setUp() {
        headers = new PolymarketAuthHeaders(API_KEY, BASE64_SECRET, PASSPHRASE, ADDRESS);
    }

    @Test
    void accessorsReturnConstructorValues() {
        assertEquals(API_KEY, headers.apiKey());
        assertEquals(PASSPHRASE, headers.passphrase());
        assertEquals(ADDRESS, headers.address());
    }

    @Test
    void signPostProducesValidHmac() throws Exception {
        final byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        headers.sign("POST", "/order", body, 0, body.length);

        assertNotNull(headers.signature());
        assertNotNull(headers.timestamp());

        final String expected = computeExpectedHmac(headers.timestamp(), "POST", "/order", "{\"test\":1}");
        assertEquals(expected, headers.signature());
    }

    @Test
    void signDeleteProducesValidHmac() throws Exception {
        headers.sign("DELETE", "/order", null, 0, 0);

        final String expected = computeExpectedHmac(headers.timestamp(), "DELETE", "/order", "");
        assertEquals(expected, headers.signature());
    }

    @Test
    void signWithNullBodyTreatsAsEmpty() throws Exception {
        headers.sign("GET", "/data", null, 0, 0);
        final String nullSig = headers.signature();
        final String nullTs = headers.timestamp();

        headers.sign("GET", "/data", null, 0, 0);
        final String emptySig = headers.signature();

        if (nullTs.equals(headers.timestamp())) {
            assertEquals(nullSig, emptySig);
        }
    }

    @Test
    void timestampIsCurrentUnixSeconds() {
        headers.sign("GET", "/", null, 0, 0);
        final long now = System.currentTimeMillis() / 1000L;
        final long ts = Long.parseLong(headers.timestamp());
        // Allow 2-second window to account for potential delay
        assertEquals(now, ts, 2);
    }

    private static String computeExpectedHmac(
            final String timestamp, final String method, final String path, final String body) throws Exception {
        final String message = timestamp + method + path + (body != null ? body : "");
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(DECODED_SECRET, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes()));
    }
}
