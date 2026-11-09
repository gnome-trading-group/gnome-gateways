package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPResponse;
import java.io.IOException;

/**
 * The failure of a request beside the lane that got no usable answer. A request its {@link BudgetGate} refused comes
 * back as a 429 that never reached the venue, so it is told apart: read as the venue's, it would look like the budget
 * undercounting.
 */
public final class RequestFailure {

    private RequestFailure() {}

    /** @param request what was asked, as the start of a sentence: "Kalshi order list" */
    public static IOException of(final String request, final HTTPResponse response) {
        if (response.isRefusedLocally()) {
            return new IOException(request + " wasn't sent: the account's budget had no room for it in time");
        }
        return new IOException(request + " failed with status " + response.getStatusCode());
    }
}
