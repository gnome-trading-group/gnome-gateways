package group.gnometrading.gateways.venues.hyperliquid;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;

/** Hyperliquid: what its feed needs to know about it. No order entry yet. */
public final class HyperliquidVenue {

    public static final VenueFeed FEED = new VenueFeed(
            HyperliquidVenue::subjectOf,
            // l2Book sends 20 levels a side.
            32,
            new GatewayConfig.Builder().build(),
            "hyperliquid.ws.url",
            1 << 19,
            // Whole books every message: nothing to probe.
            0);

    private HyperliquidVenue() {}

    /** A listing's market: its coin. */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        return new SubjectRef(exchangeSecurityId, Projection.IDENTITY);
    }
}
