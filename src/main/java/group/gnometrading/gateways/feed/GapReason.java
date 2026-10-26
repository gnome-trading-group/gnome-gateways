package group.gnometrading.gateways.feed;

/** Why a market's data stopped being trusted. */
public enum GapReason {
    /** The venue's message sequence for this market skipped. */
    SEQUENCE,
    /**
     * A sequence shared by every market on the connection skipped (Kalshi): each market resyncs, and the gap counts once
     * against the connection rather than against each market.
     */
    CONNECTION_SEQUENCE,
    /** A nonce chain broke (Lighter). */
    NONCE,
    /** The venue's own best prices disagree with the book (Polymarket Intl). */
    BEST_PRICE,
    /** A message for the market couldn't be read. */
    UNREADABLE,
    /** The REST book disagreed twice. */
    PROBE_MISMATCH,
    /** The book went deeper than the levels kept for it, so what was dropped could surface later. */
    BOOK_OVERFLOW
}
