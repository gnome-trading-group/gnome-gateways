package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.networking.crypto.RsaPssSha256Signer;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * Signs Kalshi API requests: base64 RSA-PSS (SHA-256, 32-byte salt) over {@code timestamp + METHOD + path}, the path
 * without its query. Signing allocates nothing; the timestamp and signature are kept as ASCII bytes for header values,
 * and as strings only when asked for. Not thread-safe: each sending thread gets its own instance.
 */
public final class KalshiAuthSigner {

    // 13 (timestamp millis digits) + 6 (method) + the path
    private static final int MSG_BUF_SIZE = 512;
    private static final int MAX_TIMESTAMP_DIGITS = 20;

    private String apiKey;
    private byte[] apiKeyBytes;
    private RsaPssSha256Signer rsa;
    private final Base64.Encoder base64 = Base64.getEncoder();
    private final byte[] msgBuf = new byte[MSG_BUF_SIZE];
    private byte[] sigBuf;
    private byte[] sigBase64;
    private final byte[] timestampBuf = new byte[MAX_TIMESTAMP_DIGITS];
    private final ByteBuffer timestampBuffer = ByteBuffer.wrap(this.timestampBuf);
    private int timestampLength;
    private int sigBase64Length;

    public KalshiAuthSigner(final String apiKey, final PrivateKey privateKey) throws IOException {
        rekey(apiKey, privateKey);
    }

    /** Signs with a new key from now on, e.g. after the venue refused the old one. On the signing thread. */
    public void rekey(final String apiKey, final PrivateKey privateKey) throws IOException {
        final RsaPssSha256Signer replacement;
        try {
            replacement = new RsaPssSha256Signer(privateKey.getEncoded());
        } catch (final RuntimeException e) {
            throw new IOException("Failed to initialize Kalshi auth signer", e);
        }
        if (this.rsa != null) {
            this.rsa.close();
        }
        this.rsa = replacement;
        this.apiKey = apiKey;
        this.apiKeyBytes = apiKey.getBytes(StandardCharsets.US_ASCII);
        this.sigBuf = new byte[replacement.signatureLength()];
        this.sigBase64 = new byte[4 * ((this.sigBuf.length + 2) / 3)];
        this.sigBase64Length = 0;
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
            this.rsa.sign(this.msgBuf, 0, len, this.sigBuf, 0);
            this.sigBase64Length = this.base64.encode(this.sigBuf, this.sigBase64);
        } catch (final RuntimeException e) {
            throw new IOException("Failed to compute Kalshi auth signature", e);
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
