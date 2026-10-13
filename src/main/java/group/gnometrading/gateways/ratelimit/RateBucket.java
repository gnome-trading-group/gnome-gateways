package group.gnometrading.gateways.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A token bucket kept as the generic cell rate algorithm: one theoretical arrival time, moved forward by each
 * request's cost, so taking tokens is a single compare-and-set however many threads share the bucket. Times are
 * {@link System#nanoTime()}-style and only ever compared by difference.
 */
public final class RateBucket {

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final String name;
    private final long burstTokens;
    private final long reserveTokens;
    private final long backgroundHeadroomTokens;
    private final AtomicLong theoreticalArrival;
    private final AtomicLong blockedUntil;
    private volatile long intervalNanos;

    /**
     * @param reserveTokens tokens orders and background requests must leave for cancels
     * @param backgroundHeadroomTokens tokens background requests must leave on top of the reserve, for orders
     */
    RateBucket(
            final String name,
            final double tokensPerSecond,
            final long burstTokens,
            final long reserveTokens,
            final long backgroundHeadroomTokens,
            final long nowNanos) {
        if (burstTokens <= 0 || reserveTokens < 0 || backgroundHeadroomTokens < 0) {
            throw new IllegalArgumentException("Bucket " + name + " needs a positive burst and no negative headroom");
        }
        if (reserveTokens + backgroundHeadroomTokens >= burstTokens) {
            throw new IllegalArgumentException("Bucket " + name + " leaves nothing for background requests");
        }
        this.name = name;
        this.burstTokens = burstTokens;
        this.reserveTokens = reserveTokens;
        this.backgroundHeadroomTokens = backgroundHeadroomTokens;
        this.theoreticalArrival = new AtomicLong(nowNanos);
        this.blockedUntil = new AtomicLong(nowNanos);
        this.intervalNanos = intervalFor(tokensPerSecond);
    }

    public String name() {
        return this.name;
    }

    long burstTokens() {
        return this.burstTokens;
    }

    /** Tokens a request of {@code requestClass} must leave in the bucket. */
    long headroomFor(final RequestClass requestClass) {
        return switch (requestClass) {
            case CANCEL -> 0;
            case ORDER -> this.reserveTokens;
            case BACKGROUND -> this.reserveTokens + this.backgroundHeadroomTokens;
        };
    }

    /**
     * Refills at {@code tokensPerSecond} from now on. The tokens owed are kept as tokens, not as time: kept as time,
     * a slower rate would make room for more tokens just when fewer should go.
     */
    void setRate(final double tokensPerSecond, final long nowNanos) {
        final long newInterval = intervalFor(tokensPerSecond);
        while (true) {
            final long oldInterval = this.intervalNanos;
            final long arrival = this.theoreticalArrival.get();
            final long debt = later(arrival, nowNanos) - nowNanos;
            final long rescaled = nowNanos + Math.round((double) debt / oldInterval * newInterval);
            if (this.theoreticalArrival.compareAndSet(arrival, rescaled)) {
                this.intervalNanos = newInterval;
                return;
            }
        }
    }

    private long intervalFor(final double tokensPerSecond) {
        if (!(tokensPerSecond > 0)) {
            throw new IllegalArgumentException("Bucket " + this.name + " needs a positive rate");
        }
        return Math.max(1, Math.round(NANOS_PER_SECOND / tokensPerSecond));
    }

    /** Takes {@code cost} tokens if that leaves at least {@code headroomTokens}. */
    boolean tryAcquire(final long nowNanos, final long cost, final long headroomTokens) {
        final long interval = this.intervalNanos;
        final long tolerance = (this.burstTokens - headroomTokens) * interval;
        while (true) {
            final long arrival = this.theoreticalArrival.get();
            final long next = later(arrival, nowNanos) + cost * interval;
            if (next - nowNanos > tolerance) {
                return false;
            }
            if (this.theoreticalArrival.compareAndSet(arrival, next)) {
                return true;
            }
        }
    }

    /** Takes {@code cost} tokens whether or not they are there, going into debt that later requests wait out. */
    void forceAcquire(final long nowNanos, final long cost) {
        final long interval = this.intervalNanos;
        while (true) {
            final long arrival = this.theoreticalArrival.get();
            if (this.theoreticalArrival.compareAndSet(arrival, later(arrival, nowNanos) + cost * interval)) {
                return;
            }
        }
    }

    /** How long until {@link #tryAcquire} with these arguments could succeed; 0 if it could now. */
    long nanosUntilAvailable(final long nowNanos, final long cost, final long headroomTokens) {
        final long interval = this.intervalNanos;
        final long tolerance = (this.burstTokens - headroomTokens) * interval;
        final long next = later(this.theoreticalArrival.get(), nowNanos) + cost * interval;
        return Math.max(0, next - nowNanos - tolerance);
    }

    /** Tokens left now, rounded down. */
    long availableTokens(final long nowNanos) {
        final long debt = later(this.theoreticalArrival.get(), nowNanos) - nowNanos;
        return Math.max(0, this.burstTokens - Math.floorDiv(debt + this.intervalNanos - 1, this.intervalNanos));
    }

    /** Holds every request but cancels until {@code untilNanos}, or longer if already held longer. */
    void blockUntil(final long untilNanos) {
        while (true) {
            final long current = this.blockedUntil.get();
            if (untilNanos - current <= 0 || this.blockedUntil.compareAndSet(current, untilNanos)) {
                return;
            }
        }
    }

    /** How long requests other than cancels are held for; 0 if they aren't. */
    long nanosBlocked(final long nowNanos) {
        return Math.max(0, this.blockedUntil.get() - nowNanos);
    }

    private static long later(final long first, final long second) {
        return first - second > 0 ? first : second;
    }
}
