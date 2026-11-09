package group.gnometrading.gateways.orders;

/** How a venue answered a cancel. One with no clear answer is an I/O failure instead. */
public enum CancelResult {
    ACCEPTED,
    REJECTED,
    /** Refused for the rate limit, by the venue or before it was sent: the order is still working. */
    RATE_LIMITED
}
