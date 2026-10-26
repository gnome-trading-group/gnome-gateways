package group.gnometrading.gateways.feed;

/**
 * Where a venue market stands. Records are published as good only in {@link #LIVE} and {@link #SUSPECT}. Leaving them,
 * the market's last book goes out once flagged badData; after that only its trades go out, flagged too (a trade
 * happened even if the book is in doubt), until a new snapshot makes it live again.
 */
public enum SubjectState {
    /** Asked for; its snapshot hasn't arrived. */
    SUBSCRIBING,
    LIVE,
    /** Quiet for longer than usual; a probe decides. Its records are still trusted. */
    SUSPECT,
    /** Its book is known or thought wrong; being resynced. */
    STALE,
    /** Subscribed, but the venue sent no book: a market nobody quotes. */
    EMPTY,
    /** Failed too often; unsubscribed until it is retried. */
    QUARANTINED,
    /** Being taken off its connection. */
    REMOVING;

    private static final SubjectState[] VALUES = values();

    static SubjectState of(final int ordinal) {
        return VALUES[ordinal];
    }

    public boolean trusted() {
        return this == LIVE || this == SUSPECT;
    }
}
