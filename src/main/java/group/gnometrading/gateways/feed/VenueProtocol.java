package group.gnometrading.gateways.feed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Everything venue-specific about one connection: how to authenticate, subscribe, unsubscribe and resync markets and
 * keep the connection alive, and what each frame means — decoded once and turned into events on the markets it names,
 * looked up in the connection's {@link SubjectTable}.
 *
 * <p>Commands run on the connection's supervisor thread; {@link #onFrame} on its reader.
 *
 * @param <C> the transport's channel the commands are sent through
 */
public interface VenueProtocol<C> {

    /** What a venue's book messages carry. */
    enum Books {
        /** Changes to apply to the book: a lost one leaves it wrong until a snapshot, so the book is probed. */
        CHANGES,
        /** The whole book every time: nothing carries over between messages, so it can't drift. */
        WHOLE
    }

    Books books();

    /** The most markets one subscribe, unsubscribe or resync command may carry. */
    int maxSubjectsPerCommand();

    /**
     * The most frames one such command sends, whichever it is: what the connection paces against the source's message
     * budget and the writer's room.
     */
    int framesPerCommand();

    /** Sets what the connection must open with, e.g. signed upgrade headers; before every connect. */
    default void authenticate(C channel) throws IOException {}

    /** A new connection is up: forget connection-level state, such as a sequence number. */
    void onConnected();

    void subscribe(C channel, List<Subject> subjects) throws IOException;

    void unsubscribe(C channel, List<Subject> subjects) throws IOException;

    /** Gets each market a fresh snapshot without disturbing the others. */
    void resync(C channel, List<Subject> subjects) throws IOException;

    /** An application-level keep-alive; nothing for a venue whose transport keep-alive is enough. */
    void keepAlive(C channel) throws IOException;

    /**
     * Reader thread: one frame, consumed whole. A message for one market that can't be read makes that market stale;
     * a frame that can't be read at all throws, and the connection is reconnected.
     */
    void onFrame(ByteBuffer frame, long receiveNanos);
}
