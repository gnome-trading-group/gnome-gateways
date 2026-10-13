package group.gnometrading.gateways.ratelimit;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.http.RequestGate;
import group.gnometrading.strings.GnomeString;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.NanoClock;

/**
 * Holds one venue account's requests to its rate limits, for every HTTP client that sends on that account: the
 * order writers, readers and recovery of every listing in the process. Thread-safe.
 *
 * <p>Other processes may share the account and come and go, so this process can't know its share of the budget.
 * It starts at {@link #INITIAL_BUDGET} of the venue's rate, halves it when the venue answers 429, and gives back
 * {@link #BUDGET_STEP} for each second without one, up to the configured share.
 */
public final class VenueRateLimiter implements RequestGate {

    static final double INITIAL_BUDGET = 0.8;
    static final double MIN_BUDGET = 0.1;
    static final double BUDGET_STEP = 0.05;
    static final long ADJUST_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long a 429 without Retry-After holds orders and background requests. */
    static final long DEFAULT_PENALTY_NANOS = TimeUnit.SECONDS.toNanos(1);

    static final long MAX_PENALTY_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The longest a venue's "none left until reset" header holds requests, in case its clock is off. */
    static final long MAX_HEADER_BLOCK_NANOS = TimeUnit.SECONDS.toNanos(2);

    static final long CANCEL_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    static final long BACKGROUND_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int NO_BUCKET = -1;
    private static final long ABSENT = -1;

    private final VenueRateLimits limits;
    private final Logger logger;
    private final NanoClock clock;
    private final EpochClock epochClock;
    private final LongConsumer pause;

    private final RateBucket[] buckets;
    private final double[] baseRates;

    private final HTTPMethod[] ruleMethods;
    private final byte[][] rulePrefixes;
    private final byte[][] ruleSuffixes;
    private final int[] ruleBuckets;
    private final long[] ruleCosts;
    private final RequestClass[] ruleTypes;

    private final int retryAfterHeader;
    private final int remainingHeader;
    private final int resetHeader;

    private final AtomicLong refusals = new AtomicLong();
    private volatile boolean throttling;
    private volatile long lastAdjustNanos;
    private double budget;
    private long lastDecreaseNanos;

    VenueRateLimiter(
            final VenueRateLimits limits, final Logger logger, final NanoClock clock, final EpochClock epochClock) {
        this(limits, logger, clock, epochClock, LockSupport::parkNanos);
    }

    /** @param pause waits about the given nanoseconds before a request that must wait checks its bucket again */
    VenueRateLimiter(
            final VenueRateLimits limits,
            final Logger logger,
            final NanoClock clock,
            final EpochClock epochClock,
            final LongConsumer pause) {
        this.limits = limits;
        this.pause = pause;
        this.logger = logger;
        this.clock = clock;
        this.epochClock = epochClock;
        final long now = clock.nanoTime();
        this.budget = Math.min(INITIAL_BUDGET, limits.share());
        this.lastAdjustNanos = now;
        this.lastDecreaseNanos = now - ADJUST_INTERVAL_NANOS;

        final List<VenueRateLimits.Bucket> specs = limits.buckets();
        this.buckets = new RateBucket[specs.size()];
        this.baseRates = new double[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            final VenueRateLimits.Bucket spec = specs.get(i);
            this.baseRates[i] = spec.tokensPerSecond();
            this.buckets[i] = new RateBucket(
                    spec.name(),
                    spec.tokensPerSecond() * this.budget,
                    spec.burst(),
                    Math.round(spec.burst() * spec.cancelReserve()),
                    Math.round(spec.burst() * VenueRateLimits.BACKGROUND_HEADROOM),
                    now);
        }

        final List<VenueRateLimits.Rule> rules = limits.rules();
        this.ruleMethods = new HTTPMethod[rules.size()];
        this.rulePrefixes = new byte[rules.size()][];
        this.ruleSuffixes = new byte[rules.size()][];
        this.ruleBuckets = new int[rules.size()];
        this.ruleCosts = new long[rules.size()];
        this.ruleTypes = new RequestClass[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            final VenueRateLimits.Rule rule = rules.get(i);
            this.ruleMethods[i] = rule.method();
            this.rulePrefixes[i] = rule.prefix().getBytes(StandardCharsets.US_ASCII);
            this.ruleSuffixes[i] =
                    rule.suffix() == null ? new byte[0] : rule.suffix().getBytes(StandardCharsets.US_ASCII);
            this.ruleBuckets[i] = bucketIndex(specs, rule.bucket());
            this.ruleCosts[i] = rule.cost();
            this.ruleTypes[i] = rule.type();
        }

        this.retryAfterHeader = limits.headerIndex(VenueRateLimits.RETRY_AFTER);
        this.remainingHeader = limits.headerIndex(VenueRateLimits.POLY_REMAINING);
        this.resetHeader = limits.headerIndex(VenueRateLimits.POLY_RESET);
    }

    /** The response headers each HTTP client gated by this limiter must capture, in this order. */
    public String[] capturedHeaders() {
        return this.limits.capturedHeaders();
    }

    @Override
    public int beforeRequest(final HTTPMethod method, final String path) {
        return admit(classify(method, path));
    }

    @Override
    public int beforeRequest(final HTTPMethod method, final GnomeString path) {
        return admit(classify(method, path));
    }

    @Override
    public void afterResponse(final int tag, final HTTPResponse response) {
        if (response.isRefusedLocally()) {
            return;
        }
        final int bucket = this.ruleBuckets[tag];
        if (response.getStatusCode() == STATUS_TOO_MANY_REQUESTS) {
            onVenueRateLimited(tag, bucket, response);
        } else if (bucket != NO_BUCKET && this.remainingHeader >= 0) {
            holdUntilVenueReset(bucket, response);
        }
    }

    /** The share of the venue's rate this process currently allows itself. */
    synchronized double budget() {
        return this.budget;
    }

    int classify(final HTTPMethod method, final String path) {
        int end = path.indexOf('?');
        end = end < 0 ? path.length() : end;
        for (int i = 0; i < this.ruleMethods.length; i++) {
            if (methodMatches(i, method) && matches(path, end, this.rulePrefixes[i], this.ruleSuffixes[i])) {
                return i;
            }
        }
        throw new IllegalStateException("The last rule matches every request");
    }

    int classify(final HTTPMethod method, final GnomeString path) {
        int end = path.length();
        for (int i = 0; i < end; i++) {
            if (path.byteAt(i) == '?') {
                end = i;
                break;
            }
        }
        for (int i = 0; i < this.ruleMethods.length; i++) {
            if (methodMatches(i, method) && matches(path, end, this.rulePrefixes[i], this.ruleSuffixes[i])) {
                return i;
            }
        }
        throw new IllegalStateException("The last rule matches every request");
    }

    /** Lets the request of {@code rule} through, waiting if its class may wait; {@link #REFUSE} if not. */
    int admit(final int rule) {
        final int bucketIndex = this.ruleBuckets[rule];
        if (bucketIndex == NO_BUCKET) {
            return rule;
        }
        final RateBucket bucket = this.buckets[bucketIndex];
        final RequestClass type = this.ruleTypes[rule];
        final long cost = this.ruleCosts[rule];
        final long headroom = bucket.headroomFor(type);
        final long start = this.clock.nanoTime();
        maybeRaiseBudget(start);
        long now = start;
        while (true) {
            if (tryTake(bucket, type, cost, headroom, now)) {
                onAdmitted(type);
                return rule;
            }
            if (type == RequestClass.ORDER) {
                return refuse(bucket);
            }
            final long wait = nanosToWait(bucket, type, cost, headroom, now);
            final long maxWait = type == RequestClass.CANCEL ? CANCEL_WAIT_NANOS : BACKGROUND_WAIT_NANOS;
            if (now + wait - (start + maxWait) > 0) {
                return giveUpWaiting(rule, bucket, type, cost, now);
            }
            this.pause.accept(Math.max(1, wait));
            now = this.clock.nanoTime();
        }
    }

    private static boolean tryTake(
            final RateBucket bucket, final RequestClass type, final long cost, final long headroom, final long now) {
        return (type == RequestClass.CANCEL || bucket.nanosBlocked(now) == 0) && bucket.tryAcquire(now, cost, headroom);
    }

    private static long nanosToWait(
            final RateBucket bucket, final RequestClass type, final long cost, final long headroom, final long now) {
        final long blocked = type == RequestClass.CANCEL ? 0 : bucket.nanosBlocked(now);
        return blocked > 0 ? blocked : bucket.nanosUntilAvailable(now, cost, headroom);
    }

    /** A cancel goes anyway, since the venue is the authority on whether it fits; anything else is refused. */
    private int giveUpWaiting(
            final int rule, final RateBucket bucket, final RequestClass type, final long cost, final long now) {
        if (type == RequestClass.CANCEL) {
            bucket.forceAcquire(now, cost);
            return rule;
        }
        return refuse(bucket);
    }

    private void onAdmitted(final RequestClass type) {
        if (type == RequestClass.ORDER && this.throttling) {
            endThrottling();
        }
    }

    private int refuse(final RateBucket bucket) {
        this.refusals.incrementAndGet();
        if (!this.throttling) {
            startThrottling(bucket);
        }
        return REFUSE;
    }

    private synchronized void startThrottling(final RateBucket bucket) {
        if (this.throttling) {
            return;
        }
        this.throttling = true;
        this.logger.logf(
                LogMessage.RATE_LIMIT_THROTTLING,
                "%s rate limit: refusing requests locally, %s bucket spent (budget %.0f%% of the venue's rate)",
                this.limits.venue(),
                bucket.name(),
                this.budget * 100);
    }

    private synchronized void endThrottling() {
        if (!this.throttling) {
            return;
        }
        this.throttling = false;
        this.logger.logf(
                LogMessage.RATE_LIMIT_RECOVERED,
                "%s rate limit: sending again after %d requests refused locally",
                this.limits.venue(),
                this.refusals.getAndSet(0));
    }

    private void onVenueRateLimited(final int rule, final int bucket, final HTTPResponse response) {
        final long now = this.clock.nanoTime();
        final long retryAfterSeconds =
                this.retryAfterHeader < 0 ? ABSENT : response.headerAsLong(this.retryAfterHeader, ABSENT);
        final long penalty = retryAfterSeconds < 0
                ? DEFAULT_PENALTY_NANOS
                : Math.min(MAX_PENALTY_NANOS, TimeUnit.SECONDS.toNanos(retryAfterSeconds));
        if (bucket != NO_BUCKET) {
            this.buckets[bucket].blockUntil(now + penalty);
        }
        final double lowered = lowerBudget(now);
        this.logger.logf(
                LogMessage.VENUE_RATE_LIMITED,
                "%s answered 429 to a %s request: holding for %d ms, budget now %.0f%% of the venue's rate",
                this.limits.venue(),
                this.ruleTypes[rule],
                TimeUnit.NANOSECONDS.toMillis(penalty),
                lowered * 100);
    }

    /** Polymarket International reports what's left of the bucket: at none, wait for the reset it names. */
    private void holdUntilVenueReset(final int bucket, final HTTPResponse response) {
        if (response.headerAsLong(this.remainingHeader, ABSENT) != 0) {
            return;
        }
        final long resetSeconds = this.resetHeader < 0 ? ABSENT : response.headerAsLong(this.resetHeader, ABSENT);
        final long holdNanos = resetSeconds < 0
                ? DEFAULT_PENALTY_NANOS
                : TimeUnit.MILLISECONDS.toNanos(TimeUnit.SECONDS.toMillis(resetSeconds) - this.epochClock.time());
        final long clamped = Math.min(MAX_HEADER_BLOCK_NANOS, Math.max(0, holdNanos));
        if (clamped > 0) {
            this.buckets[bucket].blockUntil(this.clock.nanoTime() + clamped);
        }
    }

    private synchronized double lowerBudget(final long now) {
        // A burst of 429s answers one overspend; halving for each would throw away far more than it needs.
        if (now - this.lastDecreaseNanos >= ADJUST_INTERVAL_NANOS) {
            this.budget = Math.max(MIN_BUDGET, this.budget / 2);
            this.lastDecreaseNanos = now;
            applyBudget(now);
        }
        this.lastAdjustNanos = now;
        return this.budget;
    }

    private void maybeRaiseBudget(final long now) {
        if (now - this.lastAdjustNanos >= ADJUST_INTERVAL_NANOS) {
            raiseBudget(now);
        }
    }

    private synchronized void raiseBudget(final long now) {
        final long elapsed = now - this.lastAdjustNanos;
        if (elapsed < ADJUST_INTERVAL_NANOS) {
            return;
        }
        final long steps = elapsed / ADJUST_INTERVAL_NANOS;
        this.lastAdjustNanos += steps * ADJUST_INTERVAL_NANOS;
        final double raised = Math.min(this.limits.share(), this.budget + steps * BUDGET_STEP);
        if (raised != this.budget) {
            this.budget = raised;
            applyBudget(now);
        }
    }

    private void applyBudget(final long now) {
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i].setRate(this.baseRates[i] * this.budget, now);
        }
    }

    private boolean methodMatches(final int rule, final HTTPMethod method) {
        return this.ruleMethods[rule] == null || this.ruleMethods[rule] == method;
    }

    private static boolean matches(final String path, final int end, final byte[] prefix, final byte[] suffix) {
        if (end < prefix.length + suffix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (path.charAt(i) != prefix[i]) {
                return false;
            }
        }
        final int suffixStart = end - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (path.charAt(suffixStart + i) != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(final GnomeString path, final int end, final byte[] prefix, final byte[] suffix) {
        if (end < prefix.length + suffix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (path.byteAt(i) != prefix[i]) {
                return false;
            }
        }
        final int suffixStart = end - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (path.byteAt(suffixStart + i) != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int bucketIndex(final List<VenueRateLimits.Bucket> buckets, final String name) {
        if (name == null) {
            return NO_BUCKET;
        }
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No rate-limit bucket " + name);
    }
}
