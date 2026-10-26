package group.gnometrading.gateways;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spaces out connects to one source across every gateway in the process. A collector starting or a venue coming back
 * after an outage would otherwise reconnect all its streams at once, and venues answer a burst of upgrades with 429s
 * or by refusing the account for a while.
 *
 * <p>Each connect takes the next free slot, {@code 1 / connectsPerSecond} after the last one taken; a caller whose slot
 * is in the future sleeps until it. Reserving a slot is one compare-and-set, so any number of supervisor threads can
 * share a limiter.
 */
public final class ConnectLimiter {

    /** For gateways whose connects need no spacing. */
    public static final ConnectLimiter UNLIMITED = new ConnectLimiter("unlimited", 0, 0);

    // Kalshi refuses websocket upgrades well below the rate others take; the default is a polite spacing for the rest.
    private static final Map<String, Double> CONNECTS_PER_SECOND = Map.of("KALSHI", 2.0);
    private static final double DEFAULT_CONNECTS_PER_SECOND = 5.0;
    private static final Map<String, ConnectLimiter> BY_SOURCE = new ConcurrentHashMap<>();

    private final String name;
    private final long intervalNanos;
    private final AtomicLong nextSlotNanos;

    ConnectLimiter(final String name, final double connectsPerSecond, final long nowNanos) {
        this.name = name;
        this.intervalNanos = connectsPerSecond <= 0 ? 0 : Math.round(TimeUnit.SECONDS.toNanos(1) / connectsPerSecond);
        this.nextSlotNanos = new AtomicLong(nowNanos);
    }

    /** The process's limiter for a source, by its exchange code: one per source however many gateways ask. */
    public static ConnectLimiter forSource(final String exchangeCode) {
        return BY_SOURCE.computeIfAbsent(
                exchangeCode,
                code -> new ConnectLimiter(
                        code, CONNECTS_PER_SECOND.getOrDefault(code, DEFAULT_CONNECTS_PER_SECOND), System.nanoTime()));
    }

    public String name() {
        return name;
    }

    /** Waits for this connect's slot. */
    public void acquire() throws InterruptedException {
        final long waitNanos = reserve(System.nanoTime());
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    /** Takes the next free slot at or after {@code nowNanos}, returning how long until it. */
    long reserve(final long nowNanos) {
        if (intervalNanos == 0) {
            return 0;
        }
        while (true) {
            final long next = nextSlotNanos.get();
            final long slot = next - nowNanos > 0 ? next : nowNanos;
            if (nextSlotNanos.compareAndSet(next, slot + intervalNanos)) {
                return slot - nowNanos;
            }
        }
    }
}
