package group.gnometrading.gateways.ratelimit;

import group.gnometrading.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.NanoClock;

/**
 * One {@link VenueRateLimiter} per venue account in the process, so that every listing trading on the same account
 * draws on one budget. Created once by whatever starts the gateways and handed to each of them.
 */
public final class VenueRateLimiters {

    private final ConcurrentHashMap<String, VenueRateLimiter> limiters = new ConcurrentHashMap<>();
    private final Logger logger;
    private final NanoClock clock;
    private final EpochClock epochClock;

    public VenueRateLimiters(final Logger logger, final NanoClock clock, final EpochClock epochClock) {
        this.logger = logger;
        this.clock = clock;
        this.epochClock = epochClock;
    }

    /**
     * The limiter for {@code account} on the venue {@code limits} describes, created with those limits the first
     * time it is asked for. {@code account} identifies the account (a key id, an address), never a secret.
     */
    public VenueRateLimiter forAccount(final VenueRateLimits limits, final String account) {
        return this.limiters.computeIfAbsent(
                limits.venue() + '/' + account,
                key -> new VenueRateLimiter(limits, this.logger, this.clock, this.epochClock));
    }
}
