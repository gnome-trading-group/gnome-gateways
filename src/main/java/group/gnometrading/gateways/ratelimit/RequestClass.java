package group.gnometrading.gateways.ratelimit;

/** How a request is treated when its budget runs short. */
public enum RequestClass {
    /** A new order or an amend: refused at once rather than delayed, and never spends the cancel reserve. */
    ORDER,
    /**
     * Takes the cancel reserve and ignores a penalty after a 429, since it only lowers risk. Waits briefly when even
     * the reserve is spent, then goes anyway: the venue is the authority on whether it fits.
     */
    CANCEL,
    /** Lookups, catch-up and recovery: waits its turn, and leaves room for orders. */
    BACKGROUND
}
