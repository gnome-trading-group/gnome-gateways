package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;

/**
 * A Polymarket US listing: one side of a market, {@code {slug}:long} (YES) or {@code {slug}:short} (NO). The OMS
 * prices a listing's orders in that side's own terms, as on Kalshi's NO listings, while the venue prices every order
 * in long terms: buying NO at 0.40 is sent as BUY_SHORT at 0.60 (verified live, 2026-10-08). The venue's prices are
 * converted both ways here.
 */
public final class PolymarketUsListing {

    private final String marketSlug;
    private final boolean isShort;

    public PolymarketUsListing(final Listing listing) {
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colon = exchangeSecurityId.lastIndexOf(':');
        this.marketSlug = colon > 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
        this.isShort = colon > 0 && "short".equalsIgnoreCase(exchangeSecurityId.substring(colon + 1));
    }

    public String marketSlug() {
        return this.marketSlug;
    }

    boolean isShort() {
        return this.isShort;
    }

    /** The venue's intent for an order on this listing. */
    int intent(final Side side) {
        if (this.isShort) {
            return side == Side.Bid
                    ? PolymarketUsOrderFields.INTENT_BUY_SHORT
                    : PolymarketUsOrderFields.INTENT_SELL_SHORT;
        }
        return side == Side.Bid ? PolymarketUsOrderFields.INTENT_BUY_LONG : PolymarketUsOrderFields.INTENT_SELL_LONG;
    }

    /** Whether a venue order's intent trades this listing. */
    boolean trades(final int intent) {
        if (this.isShort) {
            return intent == PolymarketUsOrderFields.INTENT_BUY_SHORT
                    || intent == PolymarketUsOrderFields.INTENT_SELL_SHORT;
        }
        return intent == PolymarketUsOrderFields.INTENT_BUY_LONG || intent == PolymarketUsOrderFields.INTENT_SELL_LONG;
    }

    /** A price between this listing's terms and the venue's: the same both ways. */
    long convertPrice(final long price) {
        return this.isShort ? Statics.PRICE_SCALING_FACTOR - price : price;
    }

    static String intentName(final int intent) {
        return switch (intent) {
            case PolymarketUsOrderFields.INTENT_BUY_LONG -> "ORDER_INTENT_BUY_LONG";
            case PolymarketUsOrderFields.INTENT_SELL_LONG -> "ORDER_INTENT_SELL_LONG";
            case PolymarketUsOrderFields.INTENT_BUY_SHORT -> "ORDER_INTENT_BUY_SHORT";
            case PolymarketUsOrderFields.INTENT_SELL_SHORT -> "ORDER_INTENT_SELL_SHORT";
            default -> throw new IllegalArgumentException("No Polymarket US intent " + intent);
        };
    }
}
