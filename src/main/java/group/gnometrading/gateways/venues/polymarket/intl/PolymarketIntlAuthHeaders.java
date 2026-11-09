package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.networking.crypto.HmacSha256;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Polymarket International's L2 request auth: URL-safe base64 HMAC-SHA256 over {@code timestamp + method + path +
 * body}, under the API key's secret. Signing allocates nothing; the timestamp and signature are kept as ASCII bytes
 * for header values, and as strings only when asked for. Not thread-safe: each sending thread gets its own instance.
 */
public final class PolymarketIntlAuthHeaders {

    static final String API_KEY_HEADER = "POLY_API_KEY";
    static final String SIGNATURE_HEADER = "POLY_SIGNATURE";
    static final String TIMESTAMP_HEADER = "POLY_TIMESTAMP";
    static final String PASSPHRASE_HEADER = "POLY_PASSPHRASE";
    static final String ADDRESS_HEADER = "POLY_ADDRESS";

    private static final int MAX_TIMESTAMP_DIGITS = 20;
    private static final int MAX_TEXT_BYTES = 512;

    private String apiKey;
    private String passphrase;
    private String address;
    private byte[] apiKeyBytes;
    private byte[] passphraseBytes;
    private byte[] addressBytes;
    private HmacSha256 mac;
    // Polymarket issues the secret, and expects the signature, in URL-safe base64.
    private final Base64.Encoder base64 = Base64.getUrlEncoder();
    private final byte[] hmacBuf = new byte[HmacSha256.LENGTH];
    private final byte[] signatureBuf = new byte[4 * ((HmacSha256.LENGTH + 2) / 3)];
    private final byte[] timestampBuf = new byte[MAX_TIMESTAMP_DIGITS];
    private final ByteBuffer timestampBuffer = ByteBuffer.wrap(this.timestampBuf);
    private final byte[] textBuf = new byte[MAX_TEXT_BYTES];
    private int timestampLength;
    private int signatureLength;

    public PolymarketIntlAuthHeaders(
            final String apiKey, final String base64Secret, final String passphrase, final String address) {
        rekey(apiKey, base64Secret, passphrase, address);
    }

    /** Signs with new API credentials from now on, e.g. after the venue refused the old ones. On the signing thread. */
    public void rekey(final String apiKey, final String base64Secret, final String passphrase, final String address) {
        final HmacSha256 replacement = new HmacSha256(Base64.getUrlDecoder().decode(base64Secret));
        if (this.mac != null) {
            this.mac.close();
        }
        this.mac = replacement;
        this.apiKey = apiKey;
        this.passphrase = passphrase;
        this.address = address;
        this.apiKeyBytes = apiKey.getBytes(StandardCharsets.US_ASCII);
        this.passphraseBytes = passphrase.getBytes(StandardCharsets.US_ASCII);
        this.addressBytes = address.getBytes(StandardCharsets.US_ASCII);
        this.signatureLength = 0;
    }

    public void sign(
            final String method, final String path, final byte[] body, final int bodyOffset, final int bodyLength) {
        sign(System.currentTimeMillis() / 1000L, method, path, body, bodyOffset, bodyLength);
    }

    /** Signs {@code timestamp + method + path + body} with HMAC-SHA256, as the CLOB's L2 auth requires. */
    public void sign(
            final long timestampSeconds,
            final String method,
            final String path,
            final byte[] body,
            final int bodyOffset,
            final int bodyLength) {
        this.timestampBuffer.clear();
        ByteBufferUtils.putLongAscii(this.timestampBuffer, timestampSeconds);
        this.timestampLength = this.timestampBuffer.position();
        this.mac.update(this.timestampBuf, 0, this.timestampLength);
        update(method);
        update(path);
        if (body != null && bodyLength > 0) {
            this.mac.update(body, bodyOffset, bodyLength);
        }
        this.mac.doFinal(this.hmacBuf, 0);
        this.signatureLength = this.base64.encode(this.hmacBuf, this.signatureBuf);
    }

    /** As the other {@code sign}, over the first {@code pathLength} bytes of {@code path}. */
    public void sign(
            final long timestampSeconds,
            final String method,
            final GnomeString path,
            final int pathLength,
            final byte[] body,
            final int bodyOffset,
            final int bodyLength) {
        this.timestampBuffer.clear();
        ByteBufferUtils.putLongAscii(this.timestampBuffer, timestampSeconds);
        this.timestampLength = this.timestampBuffer.position();
        this.mac.update(this.timestampBuf, 0, this.timestampLength);
        update(method);
        for (int i = 0; i < pathLength; i++) {
            this.textBuf[i] = path.byteAt(i);
        }
        this.mac.update(this.textBuf, 0, pathLength);
        if (body != null && bodyLength > 0) {
            this.mac.update(body, bodyOffset, bodyLength);
        }
        this.mac.doFinal(this.hmacBuf, 0);
        this.signatureLength = this.base64.encode(this.hmacBuf, this.signatureBuf);
    }

    private void update(final String text) {
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            this.textBuf[i] = (byte) text.charAt(i);
        }
        this.mac.update(this.textBuf, 0, length);
    }

    public String apiKey() {
        return this.apiKey;
    }

    /** The last signature as a string; allocates, so for the blocking client, not the order path. */
    public String signature() {
        return new String(this.signatureBuf, 0, this.signatureLength, StandardCharsets.US_ASCII);
    }

    /** The last signature's base64 bytes, the first {@link #signatureLength()} of them. */
    public byte[] signatureBytes() {
        return this.signatureBuf;
    }

    public int signatureLength() {
        return this.signatureLength;
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

    public byte[] apiKeyBytes() {
        return this.apiKeyBytes;
    }

    public byte[] passphraseBytes() {
        return this.passphraseBytes;
    }

    public byte[] addressBytes() {
        return this.addressBytes;
    }

    public String passphrase() {
        return this.passphrase;
    }

    public String address() {
        return this.address;
    }
}
