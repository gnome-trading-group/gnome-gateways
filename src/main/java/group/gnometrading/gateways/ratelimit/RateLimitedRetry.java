package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPResponse;

/**
 * When a read the venue refused for its rate limit is worth sending again. Only for requests that change nothing,
 * which may be sent twice. The limiter holds the retry until the venue's penalty is over, so it needs no backoff of
 * its own.
 */
public final class RateLimitedRetry {

    public static final int MAX_ATTEMPTS = 3;

    private RateLimitedRetry() {}

    /**
     * Whether to send again after {@code response} to attempt {@code attempt} (from 1). Not after a local refusal: the
     * limiter only refuses a read when the wait would be too long, and a retry straight away would only be refused
     * again.
     */
    public static boolean shouldRetry(final HTTPResponse response, final int attempt) {
        return attempt < MAX_ATTEMPTS
                && response.getStatusCode() == HTTPResponse.STATUS_TOO_MANY_REQUESTS
                && !response.isRefusedLocally();
    }
}
