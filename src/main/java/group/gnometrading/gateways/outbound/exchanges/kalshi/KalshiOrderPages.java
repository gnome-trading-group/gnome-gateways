package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Every page of a market's orders on Kalshi, each copied out of the response so the HTTP client's buffer can be
 * reused. Kalshi can't filter by client order id, so callers narrow by ticker and status or creation time and look
 * through the pages. Not for the hot path: each page is a new buffer. Not thread-safe.
 */
final class KalshiOrderPages {

    static final String ORDERS_PATH = "/trade-api/v2/portfolio/orders";
    // Well below Kalshi's largest page of 1000, which at ~0.7 KB an order comes close to the HTTP client's 1 MiB read
    // buffer.
    private static final int PAGE_LIMIT = 200;

    private final HTTPClient httpClient;
    private final String apiHost;
    private final KalshiAuthSigner authSigner;
    private final LongSupplier epochMillis;
    private final JsonDecoder jsonDecoder = new JsonDecoder();

    KalshiOrderPages(
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final LongSupplier epochMillis) {
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.epochMillis = epochMillis;
    }

    /**
     * @param filter appended to the query, e.g. {@code "&status=resting"} or {@code "&min_ts=1791397312"}
     * @return each page's {@code {"orders":[...],"cursor":...}} body
     */
    List<ByteBuffer> fetch(final String ticker, final String filter) throws IOException {
        final List<ByteBuffer> pages = new ArrayList<>();
        String cursor = "";
        do {
            final String path = ORDERS_PATH + "?ticker=" + ticker + filter + "&limit=" + PAGE_LIMIT
                    + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            // Kalshi signs the path without its query string.
            this.authSigner.sign(this.epochMillis.getAsLong(), "GET", ORDERS_PATH);
            final HTTPResponse response = this.httpClient.get(
                    HTTPProtocol.HTTPS,
                    this.apiHost,
                    path,
                    "KALSHI-ACCESS-KEY",
                    this.authSigner.apiKey(),
                    "KALSHI-ACCESS-TIMESTAMP",
                    this.authSigner.timestamp(),
                    "KALSHI-ACCESS-SIGNATURE",
                    this.authSigner.signature());
            if (!response.isSuccess() || response.getBody() == null) {
                throw new IOException("Kalshi order list failed with status " + response.getStatusCode());
            }
            final ByteBuffer body = response.getBody();
            final ByteBuffer page = ByteBuffer.allocate(body.remaining());
            page.put(body.duplicate()).flip();
            pages.add(page);
            cursor = readCursor(page.duplicate());
        } while (!cursor.isEmpty());
        return pages;
    }

    private String readCursor(final ByteBuffer page) {
        String cursor = "";
        try (var root = this.jsonDecoder.wrap(page);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("cursor")) {
                        cursor = entry.isNull() ? "" : entry.asString().toString();
                    }
                }
            }
        }
        return cursor;
    }
}
