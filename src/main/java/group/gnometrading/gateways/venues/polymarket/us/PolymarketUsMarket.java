package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.PriceGrid;
import java.nio.charset.StandardCharsets;

/**
 * What order entry needs to know about one Polymarket US listing: its side of the market (long or short) and the
 * market's tick and lot, which the venue rounds to silently rather than refusing.
 */
public final class PolymarketUsMarket {

    private final PolymarketUsListing listing;
    private final PolymarketUsMarketInfo info;
    private final byte[] cancelBody;
    // Set by whoever keeps the listing's spec current, read by the lane.
    private volatile PriceGrid grid;

    public PolymarketUsMarket(final Listing listing, final PolymarketUsMarketInfo info) {
        this.listing = new PolymarketUsListing(listing);
        this.info = info;
        this.grid = PriceGrid.single(info.tickSize(), Statics.PRICE_SCALING_FACTOR);
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
        return this.grid.valid(price) && size > 0 && size % this.info.lotSize() == 0;
    }

    /**
     * The listing's current price grid, from its spec, replacing the tick read when the market was loaded. Its end
     * must be a dollar: the grid is what keeps every price sent under it.
     */
    public void priceGrid(final PriceGrid newGrid) {
        if (newGrid.end() != Statics.PRICE_SCALING_FACTOR) {
            throw new IllegalArgumentException("a Polymarket US grid must end at a dollar: " + newGrid);
        }
        this.grid = newGrid;
    }
}
