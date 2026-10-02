package group.gnometrading.gateways.exchanges.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.strings.MutableString;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketUsAuthSignerTest {

    private PublicKey publicKey;
    private byte[] seed;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        this.publicKey = keyPair.getPublic();
        this.seed = ((EdECPrivateKey) keyPair.getPrivate()).getBytes().orElseThrow();
    }

    @Test
    void signsTimestampMethodAndPath() throws Exception {
        final String secret = Base64.getEncoder().encodeToString(this.seed);
        final PolymarketUsAuthSigner signer =
                new PolymarketUsAuthSigner("key-id", PolymarketUsAuthSigner.parseSecretKey(secret));

        signer.sign(1_700_000_000_123L, "GET", "/v1/ws/markets");

        assertEquals("key-id", signer.apiKey());
        assertEquals("1700000000123", signer.timestamp());
        assertTrue(verify("1700000000123GET/v1/ws/markets", signer.signature()));
    }

    @Test
    void acceptsSixtyFourByteSecret() throws Exception {
        // Some key exports carry seed + public key; only the first 32 bytes are the private key.
        final byte[] extended = Arrays.copyOf(this.seed, 64);
        final String secret = Base64.getEncoder().encodeToString(extended);
        final PolymarketUsAuthSigner signer =
                new PolymarketUsAuthSigner("key-id", PolymarketUsAuthSigner.parseSecretKey(secret));

        signer.sign(42L, "POST", "/v1/orders");

        assertTrue(verify("42POST/v1/orders", signer.signature()));
    }

    @Test
    void gnomeStringPathMatchesStringPath() throws Exception {
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key-id",
                PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(this.seed)));

        signer.sign(7L, "POST", "/v1/order/abc/cancel");
        final String fromString = signer.signature();
        signer.sign(7L, "POST", new MutableString("/v1/order/abc/cancel"));

        // Ed25519 is deterministic, so identical messages give identical signatures.
        assertArrayEquals(
                Base64.getDecoder().decode(fromString), Base64.getDecoder().decode(signer.signature()));
    }

    @Test
    void rejectsShortSecret() {
        final String secret = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> PolymarketUsAuthSigner.parseSecretKey(secret));
    }

    private boolean verify(final String message, final String signatureB64) throws Exception {
        final Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(this.publicKey);
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signatureB64));
    }
}
