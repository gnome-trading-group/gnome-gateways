package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A market's exchange shard, and the routing a cancel needs to reach it. Each Kalshi market trades on one shard; a
 * request without it routes by ticker alone, which reaches the right shard but costs every shard's write budget.
 */
final class KalshiApiUtil {

    static final int SHARD_UNKNOWN = -1;
    private static final String MARKET_PATH = "/trade-api/v2/markets/";
    private static final byte[] EXCHANGE_INDEX_MARKER = "\"exchange_index\":".getBytes(StandardCharsets.UTF_8);

    private KalshiApiUtil() {}

    /** The market's shard, or {@link #SHARD_UNKNOWN} if Kalshi didn't say. */
    static int fetchExchangeIndex(
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final long epochMillis,
            final String ticker)
            throws IOException {
        final String path = MARKET_PATH + ticker;
        authSigner.sign(epochMillis, "GET", path);
        final HTTPResponse response = httpClient.get(
                HTTPProtocol.HTTPS,
                apiHost,
                path,
                "KALSHI-ACCESS-KEY",
                authSigner.apiKey(),
                "KALSHI-ACCESS-TIMESTAMP",
                authSigner.timestamp(),
                "KALSHI-ACCESS-SIGNATURE",
                authSigner.signature());
        if (!response.isSuccess() || response.getBody() == null) {
            return SHARD_UNKNOWN;
        }
        return readExchangeIndex(response.getBody());
    }

    /** Cancels take their routing as a query: an order id alone can't name the shard. */
    static String routingQuery(final String ticker, final int shard) {
        return "?market_ticker=" + ticker + (shard >= 0 ? "&exchange_index=" + shard : "");
    }

    private static int readExchangeIndex(final ByteBuffer body) {
        final int markerPos = indexOf(body, EXCHANGE_INDEX_MARKER);
        if (markerPos < 0) {
            return SHARD_UNKNOWN;
        }
        int pos = markerPos + EXCHANGE_INDEX_MARKER.length;
        int value = SHARD_UNKNOWN;
        while (pos < body.limit() && body.get(pos) >= '0' && body.get(pos) <= '9') {
            value = (value < 0 ? 0 : value * 10) + (body.get(pos) - '0');
            pos++;
        }
        return value;
    }

    private static int indexOf(final ByteBuffer buf, final byte[] pattern) {
        outer:
        for (int i = buf.position(); i <= buf.limit() - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
