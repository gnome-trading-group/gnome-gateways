package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.strings.GnomeString;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An account's request budget for the one process that uses the account: the venue's buckets at a set share of its
 * rate, asked without ever waiting. A request is admitted or told how long until it could be, so the thread sending
 * it keeps working on others. Cancels may use the reserve the other classes leave; after the venue answers 429, a
 * bucket holds everything but cancels for its {@code Retry-After}. Buckets start empty: the venue's may just have been
 * drained by the process before this one, and they refill within a second. The responses passed to
 * {@link #onResponse} must come from a client capturing {@link VenueRateLimits#capturedHeaders()}.
 *
 * <p>Thread-safe: the account's lane asks it without waiting, and the threads that read the account beside the lane
 * (catch-up, listings, market loads) ask it through a {@link BudgetGate}, which waits. Its buckets take tokens by
 * compare-and-set, so neither ever spends a token the other took.
 */
public final class AccountBudget {

    private static final long ABSENT = -1;
    // The last requests admitted, and the first 429s, kept to show what the venue had been sent when it refused one.
    private static final int ADMITTED_LOG = 1 << 14;
    private static final int REFUSED_LOG = 64;

    private final RequestRules rules;
    private final RateBucket[] buckets;
    private final int retryAfterHeader;
    private final AtomicLong venueRefusals = new AtomicLong();
    // Diagnostics, written by whichever thread admits or hears the request; an entry read as it is overwritten is
    // only a miscount.
    private final long[] admittedAt = new long[ADMITTED_LOG];
    private final int[] admittedRule = new int[ADMITTED_LOG];
    private final AtomicLong admitted = new AtomicLong();
    private final long[] refusedAt = new long[REFUSED_LOG];
    private final int[] refusedRule = new int[REFUSED_LOG];
    private final AtomicInteger refused = new AtomicInteger();

    /** @param limits the venue's limits, their share already set */
    public AccountBudget(final VenueRateLimits limits, final long nowNanos) {
        this.rules = new RequestRules(limits);
        final List<VenueRateLimits.Bucket> specs = limits.buckets();
        this.buckets = new RateBucket[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            final VenueRateLimits.Bucket spec = specs.get(i);
            this.buckets[i] = new RateBucket(
                    spec.name(),
                    spec.tokensPerSecond() * limits.share(),
                    spec.burst(),
                    Math.round(spec.burst() * spec.cancelReserve()),
                    Math.round(spec.burst() * VenueRateLimits.BACKGROUND_HEADROOM),
                    nowNanos);
            this.buckets[i].forceAcquire(nowNanos, spec.burst());
        }
        this.retryAfterHeader = limits.headerIndex(VenueRateLimits.RETRY_AFTER);
    }

    /** Tokens a second the bucket new orders draw on comes back at; 0 if new orders are unlimited. */
    public double newOrderTokensPerSecond() {
        for (int rule = 0; rule < this.rules.size(); rule++) {
            final int bucket = this.rules.bucket(rule);
            if (this.rules.type(rule) == RequestClass.NEW && bucket != RequestRules.NO_BUCKET) {
                return (double) TimeUnit.SECONDS.toNanos(1) / this.buckets[bucket].intervalNanos();
            }
        }
        return 0;
    }

    /** How many times the venue has answered 429 to a request drawn on this budget: it should never. Any thread. */
    public long venueRefusals() {
        return this.venueRefusals.get();
    }

    /** The rule a request falls under, to pass to the other calls. */
    public int classify(final HTTPMethod method, final GnomeString path) {
        return this.rules.classify(method, path);
    }

    public int classify(final HTTPMethod method, final String path) {
        return this.rules.classify(method, path);
    }

    public RequestClass type(final int rule) {
        return this.rules.type(rule);
    }

    /** The tokens a request of {@code rule} takes; 0 if its venue doesn't limit it. */
    public long cost(final int rule) {
        return this.rules.bucket(rule) == RequestRules.NO_BUCKET ? 0 : this.rules.cost(rule);
    }

    /** The most any request costs. */
    public long maxCost() {
        long max = 1;
        for (int rule = 0; rule < this.rules.size(); rule++) {
            max = Math.max(max, cost(rule));
        }
        return max;
    }

    /** Tokens left now in the bucket {@code rule} draws on; -1 if its venue doesn't limit it. */
    public long tokensLeft(final int rule, final long nowNanos) {
        final int bucket = this.rules.bucket(rule);
        return bucket == RequestRules.NO_BUCKET ? -1 : this.buckets[bucket].availableTokens(nowNanos);
    }

    /** Takes the request's tokens if they're there and its bucket isn't held; false if it must wait. */
    public boolean tryAcquire(final int rule, final long nowNanos) {
        return tryAcquire(rule, this.rules.type(rule), nowNanos);
    }

    /** As {@link #tryAcquire(int, long)}, treating the request as {@code type} whatever its rule's class. */
    public boolean tryAcquire(final int rule, final RequestClass type, final long nowNanos) {
        final int bucket = this.rules.bucket(rule);
        if (bucket == RequestRules.NO_BUCKET) {
            return true;
        }
        final RateBucket rateBucket = this.buckets[bucket];
        if (type != RequestClass.CANCEL && rateBucket.nanosBlocked(nowNanos) > 0) {
            return false;
        }
        if (!rateBucket.tryAcquire(nowNanos, this.rules.cost(rule), rateBucket.headroomFor(type))) {
            return false;
        }
        final int slot = (int) (this.admitted.getAndIncrement() & (ADMITTED_LOG - 1));
        this.admittedAt[slot] = nowNanos;
        this.admittedRule[slot] = rule;
        return true;
    }

    /** How long until {@link #tryAcquire} could admit the request; 0 if it could now. */
    public long nanosUntilAvailable(final int rule, final long nowNanos) {
        return nanosUntilAvailable(rule, this.rules.type(rule), nowNanos);
    }

    /** As {@link #nanosUntilAvailable(int, long)}, treating the request as {@code type}. */
    public long nanosUntilAvailable(final int rule, final RequestClass type, final long nowNanos) {
        final int bucket = this.rules.bucket(rule);
        if (bucket == RequestRules.NO_BUCKET) {
            return 0;
        }
        final RateBucket rateBucket = this.buckets[bucket];
        final long blocked = type == RequestClass.CANCEL ? 0 : rateBucket.nanosBlocked(nowNanos);
        return Math.max(
                blocked, rateBucket.nanosUntilAvailable(nowNanos, this.rules.cost(rule), rateBucket.headroomFor(type)));
    }

    /**
     * A response to a request of {@code rule}: on a 429, its bucket holds for the venue's {@code Retry-After} (a second
     * if it gave none, ten at most), as the venue counted more than this budget did.
     */
    public void onResponse(final int rule, final HTTPResponse response, final long nowNanos) {
        final int bucket = this.rules.bucket(rule);
        if (response.getStatusCode() != HTTPResponse.STATUS_TOO_MANY_REQUESTS) {
            return;
        }
        this.venueRefusals.incrementAndGet();
        final int refusal = this.refused.getAndIncrement();
        if (refusal < REFUSED_LOG) {
            this.refusedAt[refusal] = nowNanos;
            this.refusedRule[refusal] = rule;
        }
        if (bucket == RequestRules.NO_BUCKET) {
            return;
        }
        final long retryAfterSeconds =
                this.retryAfterHeader < 0 ? ABSENT : response.headerAsLong(this.retryAfterHeader, ABSENT);
        final long penalty = retryAfterSeconds < 0
                ? VenueRateLimiter.DEFAULT_PENALTY_NANOS
                : Math.min(VenueRateLimiter.MAX_PENALTY_NANOS, TimeUnit.SECONDS.toNanos(retryAfterSeconds));
        this.buckets[bucket].blockUntil(nowNanos + penalty);
    }

    /**
     * For each of the first 429s: the refused request's class, and how many requests of each class this budget admitted
     * in the second, and the tenth of a second, before it. Allocates; for a side thread, after the fact.
     */
    public String describeRefusals() {
        final StringBuilder text = new StringBuilder();
        final int refusals = Math.min(this.refused.get(), REFUSED_LOG);
        final long admittedCount = this.admitted.get();
        final long oldest = Math.max(0, admittedCount - ADMITTED_LOG);
        // Once the log has wrapped, the oldest admission kept bounds how far back it can tell.
        final boolean wrapped = admittedCount > ADMITTED_LOG;
        final long keptSince = wrapped ? this.admittedAt[(int) (oldest & (ADMITTED_LOG - 1))] : 0;
        for (int r = 0; r < refusals; r++) {
            final long at = this.refusedAt[r];
            text.append(this.rules.type(this.refusedRule[r]));
            if (wrapped && at - TimeUnit.SECONDS.toNanos(1) - keptSince < 0) {
                text.append(" refused; what was sent the second before is no longer kept\n");
            } else {
                describeSentBefore(text, at, oldest, admittedCount);
            }
        }
        return text.toString();
    }

    private void describeSentBefore(
            final StringBuilder text, final long at, final long oldest, final long admittedCount) {
        final int[] inSecond = new int[RequestClass.values().length];
        int inTenth = 0;
        for (long n = oldest; n < admittedCount; n++) {
            final int slot = (int) (n & (ADMITTED_LOG - 1));
            final long before = at - this.admittedAt[slot];
            if (before >= 0 && before < TimeUnit.SECONDS.toNanos(1)) {
                inSecond[this.rules.type(this.admittedRule[slot]).ordinal()]++;
                if (before < TimeUnit.MILLISECONDS.toNanos(100)) {
                    inTenth++;
                }
            }
        }
        text.append(" refused; the second before:");
        int total = 0;
        for (final RequestClass type : RequestClass.values()) {
            if (inSecond[type.ordinal()] > 0) {
                text.append(' ').append(type).append('=').append(inSecond[type.ordinal()]);
                total += inSecond[type.ordinal()];
            }
        }
        text.append(" (")
                .append(total)
                .append("), the tenth before: ")
                .append(inTenth)
                .append('\n');
    }
}
