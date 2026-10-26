package group.gnometrading.gateways.venues.lighter;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;

/** Lighter: what its feed needs to know about it. No order entry yet. */
public final class LighterVenue {

    public static final VenueFeed FEED = new VenueFeed(
            LighterVenue::subjectOf, 256, new GatewayConfig.Builder().build(), "lighter.ws.url", 1 << 19, 60);

    private LighterVenue() {}

    /** A listing's market: its market index. */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        return new SubjectRef(exchangeSecurityId, Projection.IDENTITY);
    }
}
