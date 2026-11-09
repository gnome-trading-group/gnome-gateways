package group.gnometrading.gateways.orders;

/**
 * Where a listing's orders can trade against others: the venue market they match on, and whether the listing prices
 * that market's complement, whose price p is the first side's {@code 1 - p} with the side flipped. Each venue says
 * which for its listings; two orders on one market can meet whichever of its listings each was sent on.
 *
 * @param market the venue's own name for the market
 * @param complement whether the listing prices the market's complement
 */
public record CrossMarket(String market, boolean complement) {}
