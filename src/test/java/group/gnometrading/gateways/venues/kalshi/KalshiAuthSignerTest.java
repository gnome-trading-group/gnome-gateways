package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.strings.MutableString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiAuthSignerTest {

    private static final String API_KEY = "test-api-key-abc123";

    private KeyPair keyPair;
    private KalshiAuthSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        signer = new KalshiAuthSigner(API_KEY, keyPair.getPrivate());
    }

    @Test
    void apiKey_ReturnsConstructorValue() {
        assertEquals(API_KEY, signer.apiKey());
    }

    @Test
    void sign_StringPath_SetsTimestamp() throws Exception {
        signer.sign(1700000000000L, "POST", "/path");
        assertEquals("1700000000000", signer.timestamp());
    }

    @Test
    void sign_StringPath_ProducesVerifiableSignature() throws Exception {
        final long ts = 1700000000000L;
        final String method = "POST";
        final String path = "/trade-api/v2/portfolio/events/orders";

        signer.sign(ts, method, path);

        final byte[] message = (ts + method + path).getBytes(StandardCharsets.US_ASCII);
        assertTrue(verify(message, signer.signature()));
    }

    @Test
    void sign_SignatureIs88Base64Bytes() throws Exception {
        signer.sign(1700000000000L, "POST", "/trade-api/v2/portfolio/events/orders");
        assertEquals(88, signer.signatureLength());
    }

    @Test
    void sign_GnomeStringPath_ProducesVerifiableSignature() throws Exception {
        final long ts = 1700000000001L;
        final String method = "DELETE";
        final String pathStr = "/trade-api/v2/portfolio/events/orders/order-abc-123";

        signer.sign(ts, method, path(pathStr));

        final byte[] message = (ts + method + pathStr).getBytes(StandardCharsets.US_ASCII);
        assertTrue(verify(message, signer.signature()));
    }

    @Test
    void sign_GnomeStringPath_SignsOnlyThePathBeforeItsQuery() throws Exception {
        final String signed = "/trade-api/v2/portfolio/events/orders/order-abc-123";

        signer.sign(1700000000001L, "DELETE", path(signed + "?ticker=KXTEST&exchange_index=2"), signed.length());

        assertTrue(verify(("1700000000001DELETE" + signed).getBytes(StandardCharsets.US_ASCII), signer.signature()));
    }

    @Test
    void sign_UpdatesTimestampOnEachCall() throws Exception {
        signer.sign(1000L, "GET", "/path");
        assertEquals("1000", signer.timestamp());

        signer.sign(2000L, "GET", "/path");
        assertEquals("2000", signer.timestamp());
    }

    @Test
    void sign_DifferentTimestamps_ProduceDifferentSignatures() throws Exception {
        signer.sign(1000L, "POST", "/path");
        final String sig1 = signer.signature();

        signer.sign(2000L, "POST", "/path");
        final String sig2 = signer.signature();

        assertNotEquals(sig1, sig2);
    }

    @Test
    void sign_GnomeStringPath_SetsTimestamp() throws Exception {
        signer.sign(1700000000999L, "GET", path("/user_orders"));
        assertEquals("1700000000999", signer.timestamp());
    }

    @Test
    void rekey_SignsWithTheNewKeyAndApiKeyFromThenOn() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        signer.rekey("rotated-key", keyPair.getPrivate());
        signer.sign(1700000000002L, "GET", "/trade-api/v2/portfolio/orders");

        assertEquals("rotated-key", signer.apiKey());
        assertTrue(verify(
                "1700000000002GET/trade-api/v2/portfolio/orders".getBytes(StandardCharsets.US_ASCII),
                signer.signature()));
    }

    @Test
    void anRsaKeyIsRefused() throws Exception {
        final KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        final KeyPair rsaKeys = rsa.generateKeyPair();

        assertThrows(IOException.class, () -> new KalshiAuthSigner(API_KEY, rsaKeys.getPrivate()));
        assertThrows(IOException.class, () -> signer.rekey("rsa-key", rsaKeys.getPrivate()));
        assertEquals(API_KEY, signer.apiKey(), "a refused key leaves the old one signing");
    }

    private static MutableString path(final String text) {
        final MutableString path = new MutableString(text.length());
        for (int i = 0; i < text.length(); i++) {
            path.append((byte) text.charAt(i));
        }
        return path;
    }

    private boolean verify(final byte[] message, final String base64Sig) throws Exception {
        final Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(message);
        return verifier.verify(Base64.getDecoder().decode(base64Sig));
    }
}
