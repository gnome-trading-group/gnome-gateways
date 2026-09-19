package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.strings.MutableString;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiAuthSignerTest {

    private static final String API_KEY = "test-api-key-abc123";

    private KeyPair keyPair;
    private KalshiAuthSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
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
    void sign_GnomeStringPath_ProducesVerifiableSignature() throws Exception {
        final long ts = 1700000000001L;
        final String method = "DELETE";
        final String pathStr = "/trade-api/v2/portfolio/events/orders/order-abc-123";

        final MutableString path = new MutableString(pathStr.length());
        for (int i = 0; i < pathStr.length(); i++) {
            path.append((byte) pathStr.charAt(i));
        }

        signer.sign(ts, method, path);

        final byte[] message = (ts + method + pathStr).getBytes(StandardCharsets.US_ASCII);
        assertTrue(verify(message, signer.signature()));
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
        final MutableString path = new MutableString("/user_orders".length());
        for (byte b : "/user_orders".getBytes(StandardCharsets.US_ASCII)) {
            path.append(b);
        }
        signer.sign(1700000000999L, "GET", path);
        assertEquals("1700000000999", signer.timestamp());
    }

    private boolean verify(final byte[] message, final String base64Sig) throws Exception {
        final Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(keyPair.getPublic());
        verifier.update(message);
        return verifier.verify(Base64.getDecoder().decode(base64Sig));
    }
}
