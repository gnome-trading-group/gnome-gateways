package group.gnometrading.gateways.transport;

import java.io.EOFException;

/** The venue closed the websocket with a close frame, and the status code and reason it gave. */
public final class VenueClosedException extends EOFException {

    /** RFC 6455's code for a close frame that carried none. */
    public static final int NO_STATUS = 1005;

    private final int code;
    private final String reason;

    public VenueClosedException(final int code, final String reason) {
        super("The venue closed the websocket "
                + (code == NO_STATUS
                        ? "with no status code"
                        : "(code " + code + (reason.isEmpty() ? "" : ": " + reason) + ")"));
        this.code = code;
        this.reason = reason;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
