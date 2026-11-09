package group.gnometrading.gateways.orders;

/** What looking an order up at the venue found. */
public enum LookupResult {
    FOUND,
    /**
     * The venue's records don't have it. Only a lookup that sees orders in every state may say so; one that sees only
     * open orders answers UNKNOWN instead, since a filled or cancelled order is missing from it too.
     */
    NOT_FOUND,
    /** The lookup failed or couldn't tell. */
    UNKNOWN
}
