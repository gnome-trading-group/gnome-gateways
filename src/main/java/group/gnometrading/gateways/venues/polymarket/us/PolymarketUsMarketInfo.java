package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.ratelimit.RateLimitedRetry;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A market's trading parameters from the public gateway's {@code GET /v1/market/slug/{slug}}, read once at startup.
 *
 * @param tickSize price increment, scaled by {@link Statics#PRICE_SCALING_FACTOR}. The venue cuts an off-tick price
 *     down to the tick instead of refusing it (verified live), so the writer refuses such orders itself.
 * @param lotSize quantity increment and minimum, scaled by {@link Statics#SIZE_SCALING_FACTOR}: whole contracts on some
 *     markets, hundredths on others. The venue cuts a quantity down to it, so the writer refuses such orders too.
 */
public record PolymarketUsMarketInfo(long tickSize, long lotSize) {

    private static final String MARKET_PATH = "/v1/market/slug/";

    /** Startup only: it allocates and blocks. */
    public static PolymarketUsMarketInfo load(final HTTPClient httpClient, final String gatewayHost, final String slug)
            throws IOException {
        HTTPResponse response;
        int attempt = 0;
        do {
            attempt++;
            response = httpClient.get(HTTPProtocol.HTTPS, gatewayHost, MARKET_PATH + slug);
        } while (RateLimitedRetry.shouldRetry(response, attempt));
        if (!response.isSuccess() || response.getBody() == null) {
            throw new IOException(
                    "Polymarket US market " + slug + " lookup failed with HTTP " + response.getStatusCode());
        }
        return parse(response.getBody());
    }

    static PolymarketUsMarketInfo parse(final ByteBuffer body) {
        final long[] increments = {-1, -1};
        final JsonDecoder decoder = new JsonDecoder();
        try (var root = decoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("market") && !entry.isNull()) {
                        readIncrements(entry, increments);
                    }
                }
            }
        }
        if (increments[0] <= 0 || increments[1] <= 0) {
            throw new IllegalStateException("Polymarket US market info has no tick size or minimum quantity");
        }
        return new PolymarketUsMarketInfo(increments[0], increments[1]);
    }

    private static void readIncrements(final JsonDecoder.JsonNode market, final long[] increments) {
        try (var obj = market.asObject()) {
            while (obj.hasNextKey()) {
                try (var field = obj.nextKey()) {
                    if (field.isNull()) {
                        continue;
                    }
                    if (field.getName().equals("orderPriceMinTickSize")) {
                        increments[0] = field.asFixedPointLong(Statics.PRICE_SCALING_FACTOR);
                    } else if (field.getName().equals("minimumTradeQty")) {
                        increments[1] = field.asFixedPointLong(Statics.SIZE_SCALING_FACTOR);
                    }
                }
            }
        }
    }
}
