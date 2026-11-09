package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.sm.Listing;
import java.nio.charset.StandardCharsets;

/**
 * What order entry needs to know about one Kalshi listing: its market's ticker, whether it is the market's NO side
 * (whose prices and sides are the complement of the YES book Kalshi trades), and the exchange shard the market lives
 * on, which Kalshi routes orders and cancels to.
 */
public final class KalshiMarket {

    private final String ticker;
    private final boolean noSide;
    // -1 until known, when Kalshi routes by ticker alone: it reaches the right shard, at the cost of every shard's
    // write budget and some latency.
    private int exchangeIndex = KalshiApiUtil.SHARD_UNKNOWN;
    // Cancels take their routing as a query; an order id alone can't name the shard.
    private byte[] routingQuery;

    private KalshiMarket(final String ticker, final boolean noSide) {
        this.ticker = ticker;
        this.noSide = noSide;
        this.routingQuery =
                KalshiApiUtil.routingQuery(ticker, KalshiApiUtil.SHARD_UNKNOWN).getBytes(StandardCharsets.US_ASCII);
    }

    /** From a listing whose exchange security id is {@code <ticker>} or {@code <ticker>:yes|no}. */
    public static KalshiMarket of(final Listing listing) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        final String ticker = colon >= 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
        final String suffix = colon >= 0 ? exchangeSecurityId.substring(colon + 1) : "";
        return new KalshiMarket(ticker, "no".equalsIgnoreCase(suffix));
    }

    public String ticker() {
        return this.ticker;
    }

    public boolean noSide() {
        return this.noSide;
    }

    public int exchangeIndex() {
        return this.exchangeIndex;
    }

    /** Off the hot path: builds the market's routing query. */
    public void exchangeIndex(final int exchangeIndex) {
        this.exchangeIndex = exchangeIndex;
        this.routingQuery =
                KalshiApiUtil.routingQuery(this.ticker, exchangeIndex).getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code ?market_ticker=...[&exchange_index=...]}, appended to a cancel's path after signing it. */
    public byte[] routingQuery() {
        return this.routingQuery;
    }
}
