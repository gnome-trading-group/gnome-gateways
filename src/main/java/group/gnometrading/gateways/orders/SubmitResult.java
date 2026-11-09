package group.gnometrading.gateways.orders;

/** How a venue answered an order submission. */
public enum SubmitResult {
    ACCEPTED,
    REJECTED,
    /** No clear answer (timeout, dropped connection, 5xx, unreadable reply): it may be live. */
    UNKNOWN,
    /** Refused because the venue already has an order with this id. */
    DUPLICATE,
    /** Refused for the rate limit, by the venue or before it was sent: nothing was placed. */
    RATE_LIMITED
}
