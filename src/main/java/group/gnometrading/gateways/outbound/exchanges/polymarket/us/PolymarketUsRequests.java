package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import group.gnometrading.gateways.ratelimit.RateLimitedRetry;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.util.function.LongSupplier;

/** Signed Polymarket US REST requests, shared by the writer, the reader's catch-up and recovery. */
final class PolymarketUsRequests {

    static final String ORDERS_PATH = "/v1/orders";
    static final GnomeString ORDERS_PATH_GS = new ViewString(ORDERS_PATH);
    static final String OPEN_ORDERS_PATH = "/v1/orders/open";
    static final String ORDER_PATH = "/v1/order/";
    static final String CANCEL_SUFFIX = "/cancel";
    static final String MODIFY_SUFFIX = "/modify";
    static final String ACTIVITIES_PATH = "/v1/portfolio/activities";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private PolymarketUsRequests() {}

    /** A POST of a JSON body, signed over {@code path}. */
    static HTTPResponse post(
            final HTTPClient httpClient,
            final String apiHost,
            final PolymarketUsAuthSigner signer,
            final long nowMillis,
            final GnomeString path,
            final byte[] body,
            final int bodyLength)
            throws IOException {
        signer.sign(nowMillis, "POST", path);
        return httpClient.post(
                HTTPProtocol.HTTPS,
                apiHost,
                path,
                body,
                bodyLength,
                PolymarketUsAuthSigner.ACCESS_KEY_HEADER,
                signer.apiKey(),
                PolymarketUsAuthSigner.TIMESTAMP_HEADER,
                signer.timestamp(),
                PolymarketUsAuthSigner.SIGNATURE_HEADER,
                signer.signature(),
                CONTENT_TYPE_HEADER,
                CONTENT_TYPE_JSON);
    }

    /**
     * A GET signed over its path without the query, as the venue verifies it. Sent again, signed afresh, if the venue
     * refuses it for its rate limit.
     */
    static HTTPResponse get(
            final HTTPClient httpClient,
            final String apiHost,
            final PolymarketUsAuthSigner signer,
            final LongSupplier epochMillis,
            final String path)
            throws IOException {
        HTTPResponse response;
        int attempt = 0;
        do {
            attempt++;
            response = signedGet(httpClient, apiHost, signer, epochMillis.getAsLong(), path);
        } while (RateLimitedRetry.shouldRetry(response, attempt));
        return response;
    }

    private static HTTPResponse signedGet(
            final HTTPClient httpClient,
            final String apiHost,
            final PolymarketUsAuthSigner signer,
            final long nowMillis,
            final String path)
            throws IOException {
        final int query = path.indexOf('?');
        signer.sign(nowMillis, "GET", query < 0 ? path : path.substring(0, query));
        return httpClient.get(
                HTTPProtocol.HTTPS,
                apiHost,
                path,
                PolymarketUsAuthSigner.ACCESS_KEY_HEADER,
                signer.apiKey(),
                PolymarketUsAuthSigner.TIMESTAMP_HEADER,
                signer.timestamp(),
                PolymarketUsAuthSigner.SIGNATURE_HEADER,
                signer.signature());
    }
}
