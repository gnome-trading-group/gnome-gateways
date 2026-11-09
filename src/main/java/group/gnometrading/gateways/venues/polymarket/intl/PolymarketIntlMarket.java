package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;

/**
 * What order entry needs to know about one Polymarket International listing: its outcome token (as the venue lists it
 * and as the signed order encodes it), whether it settles through the neg-risk exchange, and the step its order
 * amounts are rounded to.
 */
public final class PolymarketIntlMarket {

    private final String tokenId;
    private final byte[] tokenIdBytes;
    private final PolymarketIntlMarketInfo info;
    private final long amountIncrement;

    private PolymarketIntlMarket(final String tokenId, final PolymarketIntlMarketInfo info) {
        this.tokenId = tokenId;
        this.tokenIdBytes = PolymarketIntlOrderSigner.tokenIdBytes(tokenId);
        this.info = info;
        this.amountIncrement = amountIncrement(info.tickSize());
    }

    /** From a listing whose exchange security id is {@code {condition_id}:{token_id}}. */
    public static PolymarketIntlMarket of(final Listing listing, final PolymarketIntlMarketInfo info) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.indexOf(':');
        return new PolymarketIntlMarket(
                colon >= 0 ? exchangeSecurityId.substring(colon + 1) : exchangeSecurityId, info);
    }

    public String tokenId() {
        return this.tokenId;
    }

    /** The token id as a 32-byte uint256, for signing. */
    public byte[] tokenIdBytes() {
        return this.tokenIdBytes;
    }

    public boolean negRisk() {
        return this.info.negRisk();
    }

    /**
     * The smallest step of an order's second amount (dollars for a limit BUY, shares for a market BUY): two decimals
     * more than the tick's, as Polymarket's client rounds them.
     */
    public long amountIncrement() {
        return this.amountIncrement;
    }

    private static long amountIncrement(final long tickSize) {
        int tickDecimals = 0;
        long scaled = tickSize;
        while (scaled % Statics.PRICE_SCALING_FACTOR != 0) {
            scaled *= 10;
            tickDecimals++;
        }
        long increment = Statics.SIZE_SCALING_FACTOR;
        for (int i = 0; i < tickDecimals + 2; i++) {
            increment /= 10;
        }
        return Math.max(1, increment);
    }
}
