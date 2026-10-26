package group.gnometrading.gateways.feed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-minute allowance shared by every user in the process: the client messages a source's connections send, for
 * venues that limit them per IP (Lighter: 200 a minute), or the REST reads its probe makes. Never waits: a caller takes
 * what is free now and tries the rest later. Starts full, whatever clock it is read with.
 */
public final class RateBudget {

    public static final RateBudget UNLIMITED = new RateBudget(0);

    private static final Map<String, RateBudget> BY_NAME = new ConcurrentHashMap<>();

    private final long intervalNanos;
    private final long burst;
    // The time the allowance would next be spent up to; far in the past is a full allowance.
    private final AtomicLong theoreticalArrival = new AtomicLong(Long.MIN_VALUE);

    RateBudget(final double perMinute) {
        this.intervalNanos = perMinute <= 0 ? 0 : Math.round(TimeUnit.MINUTES.toNanos(1) / perMinute);
        // A minute's worth may go at once, the limit being per minute, not per instant; at least one.
        this.burst = perMinute <= 0 ? 0 : Math.max(1, (long) Math.ceil(perMinute));
    }

    /** The process's budget by name, e.g. a source's code; {@code perMinute} 0 or less is unlimited. */
    public static RateBudget named(final String name, final double perMinute) {
        if (perMinute <= 0) {
            return UNLIMITED;
        }
        return BY_NAME.computeIfAbsent(name, key -> new RateBudget(perMinute));
    }

    /**
     * Takes room for up to {@code wanted} items of {@code cost} units each, whole items only, and returns how many may
     * go now.
     */
    public int tryAcquire(final int wanted, final int cost, final long nowNanos) {
        if (intervalNanos == 0 || wanted <= 0) {
            return Math.max(0, wanted);
        }
        final long unitNanos = intervalNanos * Math.max(1, cost);
        // An item costing more than a minute's allowance still goes, once it has saved up for it.
        final long fullAt = nowNanos - Math.max(burst, cost) * intervalNanos;
        while (true) {
            final long arrival = theoreticalArrival.get();
            final long start = Math.max(arrival, fullAt);
            final int free = (int) Math.min(wanted, (nowNanos - start) / unitNanos);
            if (free <= 0) {
                return 0;
            }
            if (theoreticalArrival.compareAndSet(arrival, start + free * unitNanos)) {
                return free;
            }
        }
    }
}
