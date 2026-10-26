package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.GatewayConfig;
import java.util.function.Function;

/**
 * What a venue's market data feed needs to know about the venue, beyond its {@link VenueProtocol}.
 *
 * @param subjects maps a listing's exchange security id to the market it reads and how
 * @param levelsPerSide the deepest book the venue sends; a deeper one is flagged and fetched again
 * @param gatewayConfig its connections' timeouts, e.g. how long a quiet one may stay silent
 * @param urlProperty the property holding its websocket URL
 * @param readBufferBytes large enough for its biggest frame
 * @param probesPerMinute the REST reads its probe may make, well inside the venue's public limit; 0 for a venue that
 *     sends whole books, which isn't probed
 */
public record VenueFeed(
        Function<String, SubjectRef> subjects,
        int levelsPerSide,
        GatewayConfig gatewayConfig,
        String urlProperty,
        int readBufferBytes,
        double probesPerMinute) {}
