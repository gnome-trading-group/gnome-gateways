package group.gnometrading.gateways.orders.recovery;

/**
 * An order resting anywhere on the account, as {@link VenueOrderQuery#listAllOpenOrders()} finds it: not tied to a
 * listing the caller has loaded, so it carries what the venue needs to cancel it on its own.
 *
 * @param order the order, as {@link VenueOrderQuery#listOpenOrders} would report it
 * @param market the venue's market it rests on, as its listings' exchange security ids name it before any side suffix:
 *     a Kalshi ticker, a Polymarket US market slug, a Polymarket Intl token id
 * @param createdMillis when the venue says it was placed, epoch milliseconds
 */
public record AccountOrder(VenueOrder order, String market, long createdMillis) {}
