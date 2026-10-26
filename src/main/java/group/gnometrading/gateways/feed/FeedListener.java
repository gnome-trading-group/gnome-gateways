package group.gnometrading.gateways.feed;

/** What a connection tells whoever placed markets on it. Called on the connection's supervisor thread. */
public interface FeedListener {

    FeedListener NONE = new FeedListener() {};

    /** Resynced too often: taken off the connection; whoever placed it decides when to try again. */
    default void onQuarantined(Subject subject) {}
}
