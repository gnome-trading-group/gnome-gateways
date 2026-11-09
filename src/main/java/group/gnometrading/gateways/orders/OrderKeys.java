package group.gnometrading.gateways.orders;

/**
 * The key an order is held under by its correlation id: the id the venue echoes on every event for it. A 64-bit FNV-1a
 * hash, so a lookup by the bytes an event carries needs no allocation.
 */
public final class OrderKeys {

    private OrderKeys() {}

    public static long of(final byte[] bytes, final int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            hash ^= bytes[i] & 0xFF;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public static long of(final OrderContext ctx) {
        return of(ctx.correlationIdBytes, ctx.correlationIdLength);
    }
}
