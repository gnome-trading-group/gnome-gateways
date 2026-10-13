package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.ratelimit.RateLimitedRetry;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The CLOB requests both the writer and the venue query make: an authenticated GET, a cancel, and reading the
 * cancel's answer. The writer calls them on its hot path, so none of them allocates.
 */
final class PolymarketIntlRequests {

    static final String ORDER_PATH = "/order";
    static final GnomeString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    static final String ORDER_LOOKUP_PATH = "/data/order/";

    private PolymarketIntlRequests() {}

    /**
     * A GET signed over {@code signedPath}: the path without its query, which the CLOB leaves out of the signature.
     * Sent again, signed afresh, if the venue refuses it for its rate limit.
     */
    static HTTPResponse get(
            final HTTPClient httpClient,
            final String clobHost,
            final PolymarketIntlAuthHeaders authHeaders,
            final String signedPath,
            final String path)
            throws IOException {
        HTTPResponse response;
        int attempt = 0;
        do {
            attempt++;
            response = signedGet(httpClient, clobHost, authHeaders, signedPath, path);
        } while (RateLimitedRetry.shouldRetry(response, attempt));
        return response;
    }

    private static HTTPResponse signedGet(
            final HTTPClient httpClient,
            final String clobHost,
            final PolymarketIntlAuthHeaders authHeaders,
            final String signedPath,
            final String path)
            throws IOException {
        authHeaders.sign("GET", signedPath, null, 0, 0);
        return httpClient.get(
                HTTPProtocol.HTTPS,
                clobHost,
                path,
                PolymarketIntlAuthHeaders.API_KEY_HEADER,
                authHeaders.apiKey(),
                PolymarketIntlAuthHeaders.SIGNATURE_HEADER,
                authHeaders.signature(),
                PolymarketIntlAuthHeaders.TIMESTAMP_HEADER,
                authHeaders.timestamp(),
                PolymarketIntlAuthHeaders.PASSPHRASE_HEADER,
                authHeaders.passphrase(),
                PolymarketIntlAuthHeaders.ADDRESS_HEADER,
                authHeaders.address());
    }

    /** Cancels with {@code body} ({@code {"orderID":...}}), which the signature covers. */
    static HTTPResponse cancel(
            final HTTPClient httpClient,
            final String clobHost,
            final PolymarketIntlAuthHeaders authHeaders,
            final byte[] body,
            final int bodyLength)
            throws IOException {
        authHeaders.sign("DELETE", ORDER_PATH, body, 0, bodyLength);
        return httpClient.delete(
                HTTPProtocol.HTTPS,
                clobHost,
                ORDER_PATH_GS,
                body,
                bodyLength,
                PolymarketIntlAuthHeaders.API_KEY_HEADER,
                authHeaders.apiKey(),
                PolymarketIntlAuthHeaders.SIGNATURE_HEADER,
                authHeaders.signature(),
                PolymarketIntlAuthHeaders.TIMESTAMP_HEADER,
                authHeaders.timestamp(),
                PolymarketIntlAuthHeaders.PASSPHRASE_HEADER,
                authHeaders.passphrase(),
                PolymarketIntlAuthHeaders.ADDRESS_HEADER,
                authHeaders.address());
    }

    /**
     * Whether a cancel's answer lists the order as cancelled. The CLOB answers 200 even when it refuses, naming the
     * order under {@code not_canceled} instead.
     */
    static boolean listsAsCanceled(
            final JsonDecoder jsonDecoder, final HTTPResponse response, final byte[] orderId, final int orderIdLength) {
        final ByteBuffer body = response.getBody();
        if (!response.isSuccess() || body == null || !body.hasRemaining()) {
            return false;
        }
        boolean canceled = false;
        // Read in place and rewound after, so a refusal can still be logged; a duplicate would allocate.
        final int start = body.position();
        try (var root = jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("canceled") && !entry.isNull()) {
                        canceled = contains(entry, orderId, orderIdLength);
                    }
                }
            }
        }
        body.position(start);
        return canceled;
    }

    private static boolean contains(final JsonDecoder.JsonNode list, final byte[] orderId, final int orderIdLength) {
        boolean found = false;
        try (var ids = list.asArray()) {
            while (ids.hasNextItem()) {
                try (var id = ids.nextItem()) {
                    // The decoder reads a null as whatever string comes after it.
                    found |= !id.isNull() && matches(id.asString(), orderId, orderIdLength);
                }
            }
        }
        return found;
    }

    static boolean matches(final GnomeString id, final byte[] orderId, final int orderIdLength) {
        if (id.length() != orderIdLength) {
            return false;
        }
        for (int i = 0; i < orderIdLength; i++) {
            if (id.byteAt(i) != orderId[i]) {
                return false;
            }
        }
        return true;
    }
}
