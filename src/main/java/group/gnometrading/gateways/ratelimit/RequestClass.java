package group.gnometrading.gateways.ratelimit;

/**
 * How a request is treated when its budget runs short, most urgent first. The order matches the gateway schema's
 * {@code RequestClass}.
 */
public enum RequestClass {
    /**
     * Takes the cancel reserve and ignores a penalty after a 429, since it only lowers risk. Waits briefly when even
     * the reserve is spent, then goes anyway: the venue is the authority on whether it fits.
     */
    CANCEL,
    /**
     * Finding out what happened to orders already sent: lookups of a submit with no clear answer, and catch-up after
     * the account's event stream dropped. Fills stop for every session on the account without it, so it may use half
     * the cancel reserve that orders must leave.
     */
    CATCH_UP,
    /** Changes a working order; never spends the cancel reserve. */
    AMEND,
    /** A new order; never spends the cancel reserve. */
    NEW,
    /** Listings and recovery that nothing waits on urgently: leaves room for orders on top of the cancel reserve. */
    BACKGROUND;

    /** A new order or an amend: refused rather than delayed by a limiter that can't hold it. */
    public boolean isOrder() {
        return this == AMEND || this == NEW;
    }
}
