package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.orders.CrossMarket;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.util.List;

/** Kalshi: what its feed and its order entry need to know about it. */
public final class KalshiVenue {

    public static final VenueFeed FEED = new VenueFeed(
            KalshiVenue::subjectOf,
            // Prices in tenths of a cent: 999 a side.
            1000,
            new GatewayConfig.Builder().build(),
            "kalshi.ws.url",
            1 << 18,
            // The Basic tier reads 20 a second; the probe takes a small share of one.
            300);

    private static final String ORDERS = "/trade-api/v2/portfolio/events/orders";
    private static final String PORTFOLIO_ORDERS = "/trade-api/v2/portfolio/orders";
    private static final String BALANCE = "/trade-api/v2/portfolio/balance";

    /**
     * Kalshi's self-trade prevention for every order: when two of the account's own orders meet, the one crossing is
     * cancelled, not the resting quote, so a strategy's quotes survive another's mistake.
     */
    public static final String SELF_TRADE_PREVENTION = "taker_at_cross";

    private KalshiVenue() {}

    /**
     * A listing's market: its ticker, without the {@code :yes} or {@code :no} a binary market's two listings carry.
     * One subscription covers both; the NO listing sees the complement.
     */
    public static SubjectRef subjectOf(final String exchangeSecurityId) {
        final int colon = exchangeSecurityId.indexOf(':');
        if (colon < 0) {
            return new SubjectRef(exchangeSecurityId, Projection.IDENTITY);
        }
        final boolean no = "no".equalsIgnoreCase(exchangeSecurityId.substring(colon + 1));
        return new SubjectRef(exchangeSecurityId.substring(0, colon), no ? Projection.COMPLEMENT : Projection.IDENTITY);
    }

    /** Where a listing's orders can trade against others: its market, the same one its book is. */
    public static CrossMarket crossMarketOf(final String exchangeSecurityId) {
        final SubjectRef subject = subjectOf(exchangeSecurityId);
        return new CrossMarket(subject.key(), subject.projection() == Projection.COMPLEMENT);
    }

    /**
     * The Advanced tier: separate read and write token buckets per account, shared by every key on it. Creating or
     * amending an order costs 10 write tokens; cancelling one, a batch of them, or every order on the account (one
     * charge for all of them) costs 2. Looking up one order by its id costs 2 read tokens, everything else 10. The
     * balance has its own limit of about one a second. A 429 carries no Retry-After.
     */
    public static VenueRateLimits rateLimits() {
        return VenueRateLimits.of(
                "Kalshi",
                List.of(
                        new VenueRateLimits.Bucket("write", 300, 900, 0.2),
                        new VenueRateLimits.Bucket("read", 300, 900, 0),
                        new VenueRateLimits.Bucket("balance", 1, 1, 0)),
                List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, ORDERS + "/", "/amend", "write", 10, RequestClass.AMEND),
                        new VenueRateLimits.Rule(HTTPMethod.POST, ORDERS, null, "write", 10, RequestClass.NEW),
                        new VenueRateLimits.Rule(HTTPMethod.DELETE, ORDERS, null, "write", 2, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(
                                HTTPMethod.GET, PORTFOLIO_ORDERS + "/", null, "read", 2, RequestClass.CATCH_UP),
                        // The account's orders by ticker and time: how a submit with no clear answer is looked for.
                        new VenueRateLimits.Rule(
                                HTTPMethod.GET, PORTFOLIO_ORDERS, null, "read", 10, RequestClass.CATCH_UP),
                        new VenueRateLimits.Rule(HTTPMethod.GET, BALANCE, null, "balance", 1, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(HTTPMethod.GET, "", null, "read", 10, RequestClass.BACKGROUND),
                        new VenueRateLimits.Rule(null, "", null, "write", 10, RequestClass.NEW)),
                List.of(VenueRateLimits.RETRY_AFTER));
    }
}
