package group.gnometrading.gateways.outbound.exchanges.polymarket;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PolymarketAuthHeaders {

    static final String API_KEY_HEADER = "POLY_API_KEY";
    static final String SIGNATURE_HEADER = "POLY_SIGNATURE";
    static final String TIMESTAMP_HEADER = "POLY_TIMESTAMP";
    static final String PASSPHRASE_HEADER = "POLY_PASSPHRASE";
    static final String ADDRESS_HEADER = "POLY_ADDRESS";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String apiKey;
    private final String passphrase;
    private final String address;
    private final Mac mac;
    private final byte[] hmacBuf = new byte[32]; // HMAC-SHA256 output is always 32 bytes

    private String signature;
    private String timestamp;

    public PolymarketAuthHeaders(
            final String apiKey, final String base64Secret, final String passphrase, final String address) {
        this.apiKey = apiKey;
        this.passphrase = passphrase;
        this.address = address;
        try {
            this.mac = Mac.getInstance(HMAC_ALGORITHM);
            this.mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Secret), HMAC_ALGORITHM));
        } catch (final Exception ex) {
            throw new RuntimeException("Failed to initialize HMAC-SHA256", ex);
        }
    }

    public void sign(
            final String method, final String path, final byte[] body, final int bodyOffset, final int bodyLength) {
        final long ts = System.currentTimeMillis() / 1000L;
        this.timestamp = Long.toString(ts);
        try {
            this.mac.reset();
            this.mac.update(this.timestamp.getBytes(StandardCharsets.UTF_8));
            this.mac.update(method.getBytes(StandardCharsets.UTF_8));
            this.mac.update(path.getBytes(StandardCharsets.UTF_8));
            if (body != null && bodyLength > 0) {
                this.mac.update(body, bodyOffset, bodyLength);
            }
            this.mac.doFinal(this.hmacBuf, 0);
            this.signature = Base64.getEncoder().encodeToString(this.hmacBuf);
        } catch (final Exception ex) {
            throw new RuntimeException("Failed to sign request", ex);
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

    public String passphrase() {
        return this.passphrase;
    }

    public String address() {
        return this.address;
    }
}
