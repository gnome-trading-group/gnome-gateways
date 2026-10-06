package group.gnometrading.gateways.outbound.recovery;

/**
 * An order as the venue reports it, for startup recovery. Quantities and money use the schemas' fixed point.
 *
 * @param exchangeOrderId what execution reports and the ledger know the order by: a Kalshi client_order_id, or a
 *     Polymarket order hash
 * @param venueId what the venue needs to act on it: Kalshi's own order_id, or the Polymarket hash again
 * @param filledQty how much has filled
 * @param filledNotional what the fills cost in total
 * @param fees what the venue charged for them
 * @param terminal whether the order can no longer fill
 */
public record VenueOrder(
        String exchangeOrderId, String venueId, long filledQty, long filledNotional, long fees, boolean terminal) {}
