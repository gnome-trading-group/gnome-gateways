package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.gateways.ratelimit.RateLimitedRetry;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.utils.AsciiParsing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

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
            final LongSupplier epochMillis,
            final String ticker)
            throws IOException {
        final String path = MARKET_PATH + ticker;
        HTTPResponse response;
        int attempt = 0;
        do {
            attempt++;
            response = getMarket(httpClient, apiHost, authSigner, epochMillis.getAsLong(), path);
        } while (RateLimitedRetry.shouldRetry(response, attempt));
        if (!response.isSuccess() || response.getBody() == null) {
            return SHARD_UNKNOWN;
        }
        return readExchangeIndex(response.getBody());
    }

    private static HTTPResponse getMarket(
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final long epochMillis,
            final String path)
            throws IOException {
        authSigner.sign(epochMillis, "GET", path);
        return httpClient.get(
                HTTPProtocol.HTTPS,
                apiHost,
                path,
                "KALSHI-ACCESS-KEY",
                authSigner.apiKey(),
                "KALSHI-ACCESS-TIMESTAMP",
                authSigner.timestamp(),
                "KALSHI-ACCESS-SIGNATURE",
                authSigner.signature());
    }

    /** Cancels take their routing as a query: an order id alone can't name the shard. */
    static String routingQuery(final String ticker, final int shard) {
        return "?market_ticker=" + ticker + (shard >= 0 ? "&exchange_index=" + shard : "");
    }

    private static int readExchangeIndex(final ByteBuffer body) {
        final int markerPos = AsciiParsing.indexOf(body, body.position(), body.limit(), EXCHANGE_INDEX_MARKER);
        if (markerPos < 0) {
            return SHARD_UNKNOWN;
        }
        final int start = markerPos + EXCHANGE_INDEX_MARKER.length;
        final long shard = AsciiParsing.parseNaturalLong(
                body, start, AsciiParsing.skipDigits(body, start, body.limit()), SHARD_UNKNOWN);
        return shard > Integer.MAX_VALUE ? SHARD_UNKNOWN : (int) shard;
    }
}
