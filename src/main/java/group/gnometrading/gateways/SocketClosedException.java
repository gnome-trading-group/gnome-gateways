package group.gnometrading.gateways;

/** Thrown by a reader once it has paused itself because its connection closed, so the gateway reconnects. */
public final class SocketClosedException extends RuntimeException {

    public SocketClosedException(final Throwable cause) {
        super("Socket closed", cause);
    }
}
