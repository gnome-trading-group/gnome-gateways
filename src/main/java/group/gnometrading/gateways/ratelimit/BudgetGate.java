package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.http.RequestGate;
import group.gnometrading.strings.GnomeString;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import org.agrona.concurrent.NanoClock;

/**
 * Holds an HTTP client's requests to an account's {@link AccountBudget}, for a thread reading the account beside its
 * lane: catch-up after the event stream dropped, open-order listings, market loads. Every request is treated as one
 * class, whatever its path, so a catch-up listing and a session's listing of the same path draw differently. A request
 * waits for its tokens up to a limit, then is refused unsent, which its caller handles as any failed request. The
 * client must capture {@link VenueRateLimits#capturedHeaders()}, so the venue's 429s hold the lane as well.
 */
public final class BudgetGate implements RequestGate {

    private final AccountBudget budget;
    private final RequestClass type;
    private final long maxWaitNanos;
    private final NanoClock clock;
    private final LongConsumer pause;

    public BudgetGate(
            final AccountBudget budget, final RequestClass type, final long maxWaitNanos, final NanoClock clock) {
        this(budget, type, maxWaitNanos, clock, LockSupport::parkNanos);
    }

    /** @param pause waits about the given nanoseconds before a request that must wait checks its budget again */
    BudgetGate(
            final AccountBudget budget,
            final RequestClass type,
            final long maxWaitNanos,
            final NanoClock clock,
            final LongConsumer pause) {
        if (type == RequestClass.CANCEL) {
            throw new IllegalArgumentException("Cancels go through the lane, which never waits");
        }
        this.budget = budget;
        this.type = type;
        this.maxWaitNanos = maxWaitNanos;
        this.clock = clock;
        this.pause = pause;
    }

    @Override
    public int beforeRequest(final HTTPMethod method, final String path) {
        return admit(this.budget.classify(method, path));
    }

    @Override
    public int beforeRequest(final HTTPMethod method, final GnomeString path) {
        return admit(this.budget.classify(method, path));
    }

    @Override
    public void afterResponse(final int tag, final HTTPResponse response) {
        if (!response.isRefusedLocally()) {
            this.budget.onResponse(tag, response, this.clock.nanoTime());
        }
    }

    private int admit(final int rule) {
        final long start = this.clock.nanoTime();
        long now = start;
        while (!this.budget.tryAcquire(rule, this.type, now)) {
            final long wait = this.budget.nanosUntilAvailable(rule, this.type, now);
            if (now + wait - start > this.maxWaitNanos) {
                return REFUSE;
            }
            this.pause.accept(Math.max(1, wait));
            now = this.clock.nanoTime();
        }
        return rule;
    }
}
