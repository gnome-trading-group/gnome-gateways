package group.gnometrading.gateways.account.reader;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;

/**
 * Everything venue-specific about an account's event socket: authenticating and subscribing to every order of the
 * account, reading its frames into order events, and catching up over REST after a reconnect.
 *
 * <p>Catch-up runs in two halves on two threads: {@link #fetchCatchUp} on the supervisor while the reader goes on with
 * the lane's notices, then {@link #applyCatchUp} on the reader before it reads anything from the new socket. A venue that can only finish
 * once it sees what the reader held then (orders sent while the socket was down) asks for a {@linkplain
 * #wantsFollowUp follow-up}, fetched on the supervisor and applied on the reader in turn, so the reader never blocks
 * on the venue.
 *
 * @param <C> what the protocol sends through
 */
public interface AccountProtocol<C> {

    /** Before the socket opens: the upgrade's auth headers, signed now. */
    default void authenticate(final C channel) throws IOException {}

    /**
     * Once the socket is open, holding its lock, before catch-up: the account-wide subscription, sent straight away so
     * nothing can fall between what catch-up fetches and the events that follow.
     */
    void subscribe(C channel) throws IOException;

    void keepAlive(C channel) throws IOException;

    /** Reader thread: one frame's events, applied to the orders {@code sink} holds. Allocates nothing. */
    void onFrame(ByteBuffer frame, OrderEventSink sink);

    /**
     * Supervisor thread, while the reader runs on: what happened since {@code sinceMillis} (when the socket was last
     * heard) to the account's orders, for {@link #applyCatchUp}.
     *
     * @param held a copy of the orders the reader held as the socket opened
     */
    void fetchCatchUp(long sinceMillis, Collection<OrderContext> held) throws IOException;

    /**
     * Reader thread: applies what {@link #fetchCatchUp} fetched; after a connect, before the new socket's first frame.
     */
    void applyCatchUp(OrderEventSink sink);

    /** Whether {@link #applyCatchUp} left orders it could only settle with another fetch. */
    default boolean wantsFollowUp() {
        return false;
    }

    /** Supervisor thread: the fetch {@link #wantsFollowUp} asked for. */
    default void fetchFollowUp() throws IOException {}

    /** Reader thread: applies what {@link #fetchFollowUp} fetched. */
    default void applyFollowUp(final OrderEventSink sink) {}

    /** Reader thread: the sending thread learned the venue's id for an order held under {@code key}. */
    default void onVenueOrderId(final OrderContext ctx, final long key) {}
}
