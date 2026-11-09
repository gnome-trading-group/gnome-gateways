package group.gnometrading.gateways.account.lane;

import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.strings.MutableString;

/**
 * A request's method and path, worked out before it is sent: enough to charge it against the account's budget, and
 * then to write. A venue that signs only part of the path (Kalshi signs it without its query) says how much.
 */
public final class RequestShape {

    public HTTPMethod method;
    public final MutableString path;
    /** How many of the path's bytes the request's signature covers. */
    public int signedLength;

    public RequestShape(final int maxPathLength) {
        this.path = new MutableString(maxPathLength);
    }

    /** Starts a path signed whole unless {@link #signedLength} is set after. */
    public MutableString start(final HTTPMethod method) {
        this.method = method;
        this.path.reset();
        this.signedLength = -1;
        return this.path;
    }

    public int signed() {
        return this.signedLength < 0 ? this.path.length() : this.signedLength;
    }
}
