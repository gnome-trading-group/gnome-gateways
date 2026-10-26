package group.gnometrading.gateways.feed;

/** What a market asks of the connection carrying it. */
public interface SubjectHost {

    /**
     * The market's book is wrong: get it a fresh snapshot. Called on the connection's reader thread; {@code atNanos} is
     * the receive time of the frame that showed it, shared by every market one connection-wide gap makes stale.
     */
    void requestResync(Subject subject, GapReason reason, long atNanos);

    /**
     * Another thread found the market's book wrong (the probe): the reader makes it stale, which publishes its book
     * flagged, then resyncs it. Any thread.
     */
    void requestStale(Subject subject, GapReason reason);

    /** A stream joined the market: the reader publishes its book once more. Any thread. */
    void requestRepublish(Subject subject);
}
