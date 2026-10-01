package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A market's trading parameters from the CLOB's {@code GET /clob-markets/{condition_id}}, read once at
 * startup.
 *
 * @param tickSize price increment, scaled by {@link Statics#PRICE_SCALING_FACTOR}
 * @param minOrderSize smallest order in shares, scaled by {@link Statics#SIZE_SCALING_FACTOR}
 * @param negRisk whether orders settle through the neg-risk exchange
 * @param feeRate base fee rate {@code r}; the fee is {@code shares × r × (p(1−p))^e}
 * @param feeExponent fee curve exponent {@code e}
 * @param feeTakerOnly whether only takers pay the fee
 * @param takerOrderDelay whether the venue holds incoming taker orders briefly
 * @param minOrderAgeSeconds how long an order must rest before it can be cancelled
 */
public record PolymarketIntlMarketInfo(
        long tickSize,
        long minOrderSize,
        boolean negRisk,
        double feeRate,
        double feeExponent,
        boolean feeTakerOnly,
        boolean takerOrderDelay,
        int minOrderAgeSeconds) {

    /** The order-signing version this gateway implements; the venue reports its own at {@code /version}. */
    public static final int SUPPORTED_CLOB_VERSION = 2;

    private static final String VERSION_PATH = "/version";
    private static final String MARKET_PATH = "/clob-markets/";

    /**
     * Fetches the market's parameters, after checking the venue still accepts the order version this
     * gateway signs. Startup only: it allocates and blocks.
     */
    public static PolymarketIntlMarketInfo load(
            final HTTPClient httpClient, final String clobHost, final String conditionId) throws IOException {
        final int version = parseVersion(get(httpClient, clobHost, VERSION_PATH));
        if (version != SUPPORTED_CLOB_VERSION) {
            throw new IllegalStateException("Polymarket CLOB reports order version " + version
                    + "; this gateway signs version " + SUPPORTED_CLOB_VERSION);
        }
        return parse(get(httpClient, clobHost, MARKET_PATH + conditionId));
    }

    static int parseVersion(final ByteBuffer body) {
        final JsonDecoder decoder = new JsonDecoder();
        try (var root = decoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("version")) {
                        return entry.asInt();
                    }
                }
            }
        }
        throw new IllegalStateException("Polymarket /version response has no version");
    }

    static PolymarketIntlMarketInfo parse(final ByteBuffer body) {
        final Builder builder = new Builder();
        final JsonDecoder decoder = new JsonDecoder();
        try (var root = decoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    builder.accept(entry);
                }
            }
        }
        return builder.build();
    }

    private static ByteBuffer get(final HTTPClient httpClient, final String host, final String path)
            throws IOException {
        final HTTPResponse response = httpClient.get(HTTPProtocol.HTTPS, host, path);
        if (!response.isSuccess()) {
            throw new IOException("Polymarket GET " + path + " failed with HTTP " + response.getStatusCode());
        }
        return response.getBody();
    }

    private static final class Builder {
        private long tickSize = -1;
        private long minOrderSize = -1;
        private boolean negRisk;
        private double feeRate;
        private double feeExponent = 1.0;
        private boolean feeTakerOnly = true;
        private boolean takerOrderDelay;
        private int minOrderAgeSeconds;

        void accept(final JsonDecoder.JsonNode entry) {
            final GnomeString name = entry.getName();
            if (name.equals("mts")) {
                tickSize = entry.asFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (name.equals("mos")) {
                minOrderSize = entry.asFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (name.equals("nr")) {
                negRisk = entry.asBoolean();
            } else if (name.equals("itode")) {
                takerOrderDelay = entry.asBoolean();
            } else if (name.equals("oas")) {
                minOrderAgeSeconds = entry.asInt();
            } else if (name.equals("fd")) {
                acceptFeeDetails(entry);
            }
        }

        private void acceptFeeDetails(final JsonDecoder.JsonNode entry) {
            try (var fd = entry.asObject()) {
                while (fd.hasNextKey()) {
                    try (var field = fd.nextKey()) {
                        final GnomeString name = field.getName();
                        if (name.equals("r")) {
                            feeRate = field.asDouble();
                        } else if (name.equals("e")) {
                            feeExponent = field.asDouble();
                        } else if (name.equals("to")) {
                            feeTakerOnly = field.asBoolean();
                        }
                    }
                }
            }
        }

        PolymarketIntlMarketInfo build() {
            if (tickSize <= 0 || minOrderSize < 0) {
                throw new IllegalStateException("Polymarket market info is missing its tick size or minimum size");
            }
            return new PolymarketIntlMarketInfo(
                    tickSize,
                    minOrderSize,
                    negRisk,
                    feeRate,
                    feeExponent,
                    feeTakerOnly,
                    takerOrderDelay,
                    minOrderAgeSeconds);
        }
    }
}
