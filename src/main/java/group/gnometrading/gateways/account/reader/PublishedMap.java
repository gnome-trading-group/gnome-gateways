package group.gnometrading.gateways.account.reader;

import org.agrona.collections.Long2ObjectHashMap;

/**
 * A map one thread reads on its hot path while another occasionally changes: every change publishes a fresh copy, so
 * a read is a lock-free lookup that never allocates and never sees a half-made change. For what changes rarely next to
 * how often it's read, such as the markets an account trades or the sessions it serves.
 *
 * @param <V> the value
 */
public final class PublishedMap<V> {

    private volatile Long2ObjectHashMap<V> current = new Long2ObjectHashMap<>();

    /** On the reading thread, without allocating. */
    public V get(final long key) {
        return this.current.get(key);
    }

    /** Off the hot path: allocates the copy it publishes. */
    public synchronized void put(final long key, final V value) {
        final Long2ObjectHashMap<V> copy = new Long2ObjectHashMap<>(this.current);
        copy.put(key, value);
        this.current = copy;
    }

    /** Off the hot path. */
    public synchronized void remove(final long key) {
        final Long2ObjectHashMap<V> copy = new Long2ObjectHashMap<>(this.current);
        copy.remove(key);
        this.current = copy;
    }

    /** The key of a listing: its exchange and its security there. */
    public static long listingKey(final int exchangeId, final long securityId) {
        return ((long) exchangeId << 32) | (securityId & 0xFFFF_FFFFL);
    }
}
