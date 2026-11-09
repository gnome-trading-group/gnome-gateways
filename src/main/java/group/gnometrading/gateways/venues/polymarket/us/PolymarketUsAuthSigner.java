package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.networking.crypto.Ed25519Signer;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Signs Polymarket US API requests: base64 Ed25519 over {@code timestamp + METHOD + path}, sent
 * as {@code X-PM-Access-Key}, {@code X-PM-Timestamp} and {@code X-PM-Signature}. Signing allocates nothing; the
 * timestamp and signature are kept as ASCII bytes for header values, and as strings only when asked for.
 *
 * <p>Not thread-safe — each reader and writer gets its own instance.
 */
public final class PolymarketUsAuthSigner {

    public static final String ACCESS_KEY_HEADER = "X-PM-Access-Key";
    public static final String TIMESTAMP_HEADER = "X-PM-Timestamp";
    public static final String SIGNATURE_HEADER = "X-PM-Signature";

    private static final int SEED_LENGTH = 32;
    // 13 (timestamp millis digits) + 6 (method) + path
    private static final int MSG_BUF_SIZE = 512;
    private static final int MAX_TIMESTAMP_DIGITS = 20;

    private String apiKey;
    private byte[] apiKeyBytes;
    private Ed25519Signer ed25519;
    private final Base64.Encoder base64 = Base64.getEncoder();
    private final byte[] msgBuf = new byte[MSG_BUF_SIZE];
    private final byte[] sigBuf = new byte[Ed25519Signer.SIGNATURE_LENGTH];
    private final byte[] sigBase64 = new byte[4 * ((Ed25519Signer.SIGNATURE_LENGTH + 2) / 3)];
    private final byte[] timestampBuf = new byte[MAX_TIMESTAMP_DIGITS];
    private final ByteBuffer timestampBuffer = ByteBuffer.wrap(this.timestampBuf);
    private int timestampLength;
    private int sigBase64Length;

    public PolymarketUsAuthSigner(final String apiKey, final PrivateKey privateKey) throws IOException {
        rekey(apiKey, privateKey);
    }

    /** Signs with a new key from now on, e.g. after the venue refused the old one. On the signing thread. */
    public void rekey(final String apiKey, final PrivateKey privateKey) throws IOException {
        if (!(privateKey instanceof EdECPrivateKey edKey) || edKey.getBytes().isEmpty()) {
            throw new IOException("Failed to initialize Polymarket US auth signer: not an Ed25519 private key");
        }
        final Ed25519Signer replacement = new Ed25519Signer(edKey.getBytes().get());
        if (this.ed25519 != null) {
            this.ed25519.close();
        }
        this.ed25519 = replacement;
        this.apiKey = apiKey;
        this.apiKeyBytes = apiKey.getBytes(StandardCharsets.US_ASCII);
        this.sigBase64Length = 0;
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
        int off = writeTimestamp(timestampMillis);
        off = writeAscii(method, off);
        off = writeAscii(path, off);
        doSign(off);
    }

    public void sign(final long timestampMillis, final String method, final GnomeString path) throws IOException {
        sign(timestampMillis, method, path, path.length());
    }

    /** Signs the first {@code pathLength} bytes of {@code path}: the venue verifies a path without its query. */
    public void sign(final long timestampMillis, final String method, final GnomeString path, final int pathLength)
            throws IOException {
        int off = writeTimestamp(timestampMillis);
        off = writeAscii(method, off);
        for (int i = 0; i < pathLength; i++) {
            this.msgBuf[off++] = path.byteAt(i);
        }
        doSign(off);
    }

    private int writeTimestamp(final long timestampMillis) {
        this.timestampBuffer.clear();
        ByteBufferUtils.putLongAscii(this.timestampBuffer, timestampMillis);
        this.timestampLength = this.timestampBuffer.position();
        System.arraycopy(this.timestampBuf, 0, this.msgBuf, 0, this.timestampLength);
        return this.timestampLength;
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
            this.ed25519.sign(this.msgBuf, 0, len, this.sigBuf, 0);
            this.sigBase64Length = this.base64.encode(this.sigBuf, this.sigBase64);
        } catch (final RuntimeException e) {
            throw new IOException("Failed to compute Polymarket US auth signature", e);
        }
    }

    public String apiKey() {
        return this.apiKey;
    }

    public byte[] apiKeyBytes() {
        return this.apiKeyBytes;
    }

    /** The last signature as a string; allocates, so for the blocking client, not the order path. */
    public String signature() {
        return new String(this.sigBase64, 0, this.sigBase64Length, StandardCharsets.US_ASCII);
    }

    /** The last signature's base64 bytes, the first {@link #signatureLength()} of them. */
    public byte[] signatureBytes() {
        return this.sigBase64;
    }

    public int signatureLength() {
        return this.sigBase64Length;
    }

    /** The last timestamp as a string; allocates, so for the blocking client, not the order path. */
    public String timestamp() {
        return new String(this.timestampBuf, 0, this.timestampLength, StandardCharsets.US_ASCII);
    }

    /** The last timestamp's ASCII digits, the first {@link #timestampLength()} of them. */
    public byte[] timestampBytes() {
        return this.timestampBuf;
    }

    public int timestampLength() {
        return this.timestampLength;
    }
}
