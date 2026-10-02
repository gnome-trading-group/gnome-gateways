package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Signs Polymarket US API requests: base64 Ed25519 over {@code timestamp + METHOD + path}, sent
 * as {@code X-PM-Access-Key}, {@code X-PM-Timestamp} and {@code X-PM-Signature}.
 *
 * <p>Not thread-safe — each reader and writer gets its own instance.
 */
public final class PolymarketUsAuthSigner {

    public static final String ACCESS_KEY_HEADER = "X-PM-Access-Key";
    public static final String TIMESTAMP_HEADER = "X-PM-Timestamp";
    public static final String SIGNATURE_HEADER = "X-PM-Signature";

    private static final int SEED_LENGTH = 32;
    private static final int SIG_BUF_SIZE = 64;
    // 13 (timestamp millis digits) + 6 (method) + path
    private static final int MSG_BUF_SIZE = 512;

    private final String apiKey;
    private final Signature sig;
    private final byte[] msgBuf = new byte[MSG_BUF_SIZE];
    private final byte[] sigBuf = new byte[SIG_BUF_SIZE];

    private String timestamp;
    private String signature;

    public PolymarketUsAuthSigner(final String apiKey, final PrivateKey privateKey) throws IOException {
        this.apiKey = apiKey;
        try {
            this.sig = Signature.getInstance("Ed25519");
            this.sig.initSign(privateKey);
        } catch (Exception e) {
            throw new IOException("Failed to initialize Polymarket US auth signer", e);
        }
    }

    /**
     * Polymarket US issues the secret as a base64 Ed25519 key; it is either the 32-byte seed or the
     * 64-byte seed + public key, so only the first 32 bytes are the private key.
     */
    public static PrivateKey parseSecretKey(final String base64Secret) {
        final byte[] decoded = Base64.getDecoder().decode(base64Secret);
        if (decoded.length < SEED_LENGTH) {
            throw new IllegalArgumentException(
                    "Polymarket US secret key must decode to at least 32 bytes, got " + decoded.length);
        }
        try {
            final EdECPrivateKeySpec spec =
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, Arrays.copyOf(decoded, SEED_LENGTH));
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Polymarket US Ed25519 secret key", e);
        }
    }

    public void sign(final long timestampMillis, final String method, final String path) throws IOException {
        this.timestamp = Long.toString(timestampMillis);
        int off = writeAscii(this.timestamp, 0);
        off = writeAscii(method, off);
        off = writeAscii(path, off);
        doSign(off);
    }

    public void sign(final long timestampMillis, final String method, final GnomeString path) throws IOException {
        this.timestamp = Long.toString(timestampMillis);
        int off = writeAscii(this.timestamp, 0);
        off = writeAscii(method, off);
        for (int i = 0; i < path.length(); i++) {
            this.msgBuf[off++] = path.byteAt(i);
        }
        doSign(off);
    }

    private int writeAscii(final String value, final int offset) {
        int off = offset;
        for (int i = 0; i < value.length(); i++) {
            this.msgBuf[off++] = (byte) value.charAt(i);
        }
        return off;
    }

    private void doSign(final int len) throws IOException {
        try {
            this.sig.update(this.msgBuf, 0, len);
            final int sigLen = this.sig.sign(this.sigBuf, 0, SIG_BUF_SIZE);
            // Allocates one String per request — acceptable on the connect / HTTP I/O path
            this.signature = Base64.getEncoder().encodeToString(Arrays.copyOf(this.sigBuf, sigLen));
        } catch (Exception e) {
            throw new IOException("Failed to compute Polymarket US auth signature", e);
        }
    }

    public String apiKey() {
        return this.apiKey;
    }

    public String signature() {
        return this.signature;
    }

    public String timestamp() {
        return this.timestamp;
    }
}
