package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;

/**
 * Not thread-safe — Writer and Reader each get their own instance.
 */
public final class KalshiAuthSigner {

    // 512 bytes covers RSA-2048 (256 bytes) and RSA-4096 (512 bytes) output
    private static final int SIG_BUF_SIZE = 512;
    // 13 (timestamp millis digits) + 6 (method) + 200 (path max) < 512
    private static final int MSG_BUF_SIZE = 512;

    private final String apiKey;
    private final Signature sig;
    private final byte[] msgBuf = new byte[MSG_BUF_SIZE];
    private final byte[] sigBuf = new byte[SIG_BUF_SIZE];

    private String timestamp;
    private String signature;

    public KalshiAuthSigner(final String apiKey, final PrivateKey privateKey) throws IOException {
        this.apiKey = apiKey;
        try {
            this.sig = Signature.getInstance("RSASSA-PSS");
            this.sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            this.sig.initSign(privateKey);
        } catch (Exception e) {
            throw new IOException("Failed to initialize Kalshi auth signer", e);
        }
    }

    public void sign(final long timestampMillis, final String method, final String path) throws IOException {
        this.timestamp = Long.toString(timestampMillis);
        int off = 0;
        for (int i = 0; i < this.timestamp.length(); i++) {
            this.msgBuf[off++] = (byte) this.timestamp.charAt(i);
        }
        for (int i = 0; i < method.length(); i++) {
            this.msgBuf[off++] = (byte) method.charAt(i);
        }
        for (int i = 0; i < path.length(); i++) {
            this.msgBuf[off++] = (byte) path.charAt(i);
        }
        doSign(off);
    }

    public void sign(final long timestampMillis, final String method, final GnomeString path) throws IOException {
        this.timestamp = Long.toString(timestampMillis);
        int off = 0;
        for (int i = 0; i < this.timestamp.length(); i++) {
            this.msgBuf[off++] = (byte) this.timestamp.charAt(i);
        }
        for (int i = 0; i < method.length(); i++) {
            this.msgBuf[off++] = (byte) method.charAt(i);
        }
        for (int i = 0; i < path.length(); i++) {
            this.msgBuf[off++] = path.byteAt(i);
        }
        doSign(off);
    }

    private void doSign(final int len) throws IOException {
        try {
            this.sig.update(this.msgBuf, 0, len);
            final int sigLen = this.sig.sign(this.sigBuf, 0, SIG_BUF_SIZE);
            // Allocates one String per request — acceptable on HTTP I/O path
            this.signature = Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(this.sigBuf, sigLen));
        } catch (Exception e) {
            throw new IOException("Failed to compute Kalshi auth signature", e);
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
