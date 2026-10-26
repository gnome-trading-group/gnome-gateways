package group.gnometrading.gateways.feed.probe;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.book.Book;
import java.io.IOException;

/**
 * Fetches a market's book from the venue's REST API, a path independent of its websocket, so the probe can tell
 * whether the book the websocket built is right. Used by the prober's thread only, and allocates nothing per probe:
 * each market's request is built once, by {@link #prepare}, when it is added.
 */
public interface BookProbeClient {

    /** A market's request, built once: the path, and the body for a venue that posts. */
    record Request(String path, byte[] body) {}

    Request prepare(Subject subject);

    /**
     * Fills {@code into}, already cleared, with the market's book in the same terms its websocket book is kept in.
     * Returns false when the venue gave no answer to compare with (an error or a refusal).
     */
    boolean fetch(Request request, Book into) throws IOException;
}
