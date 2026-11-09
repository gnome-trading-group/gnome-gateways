package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import java.nio.charset.StandardCharsets;

/**
 * What order entry needs to know about one Polymarket US listing: its side of the market (long or short) and the
 * market's tick and lot, which the venue rounds to silently rather than refusing.
 */
public final class PolymarketUsMarket {

    private final PolymarketUsListing listing;
    private final PolymarketUsMarketInfo info;
    private final byte[] cancelBody;

    public PolymarketUsMarket(final Listing listing, final PolymarketUsMarketInfo info) {
        this.listing = new PolymarketUsListing(listing);
        this.info = info;
        this.cancelBody =
                ("{\"marketSlug\":\"" + this.listing.marketSlug() + "\"}").getBytes(StandardCharsets.US_ASCII);
    }

    public PolymarketUsListing listing() {
        return this.listing;
    }

    public String marketSlug() {
        return this.listing.marketSlug();
    }

    /** A cancel's body, the same for every order on the market. */
    public byte[] cancelBody() {
        return this.cancelBody;
    }

    /**
     * Whether the venue would take the price and size as they are: it cuts an off-tick price down to the tick, and a
     * quantity down to the market's lot, rather than refusing them.
     */
    public boolean validTerms(final long price, final long size) {
        return price > 0
                && price < Statics.PRICE_SCALING_FACTOR
                && price % this.info.tickSize() == 0
                && size > 0
                && size % this.info.lotSize() == 0;
    }
}
