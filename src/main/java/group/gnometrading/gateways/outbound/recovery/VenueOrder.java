package group.gnometrading.gateways.outbound.recovery;

import group.gnometrading.schemas.Side;

/**
 * An order as the venue reports it, for startup recovery. Quantities and money use the schemas' fixed point.
 *
 * @param exchangeOrderId what execution reports and the ledger know the order by: a Kalshi client_order_id, or a
 *     Polymarket order hash
 * @param venueId what the venue needs to act on it: Kalshi's own order_id, or the Polymarket hash again
 * @param side the side on this listing, or {@link Side#None} if the venue's report doesn't say
 * @param price the limit price on this listing, or 0 if the venue's report doesn't say
 * @param size the order's original size
 * @param filledQty how much has filled
 * @param filledNotional what the fills cost in total
 * @param fees what the venue charged for them
 * @param terminal whether the order can no longer fill
 */
public record VenueOrder(
        String exchangeOrderId,
        String venueId,
        Side side,
        long price,
        long size,
        long filledQty,
        long filledNotional,
        long fees,
        boolean terminal) {}
