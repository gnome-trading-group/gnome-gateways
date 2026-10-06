package group.gnometrading.gateways.outbound.recovery;

import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * What a starting session asks a venue about the account's orders, before any trading thread runs: what is still
 * resting, how an order ended, and to cancel one. Not for the hot path; implementations may allocate.
 *
 * <p>Implementations are not thread-safe, and use the same HTTP client and credentials as the venue's writer, so
 * recovery must finish before the writer starts.
 */
public interface VenueOrderQuery {

    /**
     * Every order resting on the listing's market for this account, across all pages. On a venue that lists one
     * market under several listings (Kalshi's YES and NO), this includes the other listings' orders too.
     */
    List<VenueOrder> listOpenOrders(Listing listing) throws IOException;

    /**
     * One order by the id execution reports carry, if the venue knows it.
     *
     * @param createdAfterMs a time before the order was placed, for venues that can only find it by searching
     *     recent orders
     */
    Optional<VenueOrder> getOrder(Listing listing, String exchangeOrderId, long createdAfterMs) throws IOException;

    /** Asks the venue to cancel the order; true if it accepted. Whether the order is then terminal is for the caller to check. */
    boolean cancel(Listing listing, VenueOrder order) throws IOException;
}
