package group.gnometrading.gateways.orders;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.RejectReason;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * What the order path's allocation tests and benchmark share: a sink that holds orders and counts the reports a
 * venue's events make, canned HTTP answers, frames fed to a decoder as a reader feeds them, and the measurements.
 */
public final class OrderPathTestKit {

    /** Under this many bytes over a measured run is noise from the measurement itself, as in the feed's tests. */
    public static final long MAX_BYTES = 2_048;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private OrderPathTestKit() {}

    /** Holds orders by their correlation key and counts what the events made of them, without allocating. */
    public static final class CountingSink implements OrderEventSink {
        private final Long2ObjectHashMap<OrderContext> orders = new Long2ObjectHashMap<>(64, 0.6f);
        public long acks;
        public long fills;
        public long cancels;
        public long rejects;
        public long releases;
        public long receiveNanos;

        public void hold(final OrderContext ctx) {
            this.orders.put(OrderKeys.of(ctx), ctx);
        }

        public int held() {
            return this.orders.size();
        }

        @Override
        public OrderContext order(final long correlationKey) {
            return this.orders.get(correlationKey);
        }

        @Override
        public Iterable<OrderContext> orders() {
            return this.orders.values();
        }

        @Override
        public void acknowledge(
                final OrderContext ctx, final long cumulativeQty, final long leavesQty, final long eventNanos) {
            ctx.acked = true;
            this.acks++;
        }

        @Override
        public void fill(
                final OrderContext ctx,
                final long fillQty,
                final long fillPrice,
                final long fee,
                final Liquidity liquidity,
                final boolean filled,
                final long eventNanos) {
            this.fills++;
        }

        @Override
        public void cancel(final OrderContext ctx, final long eventNanos) {
            this.cancels++;
            this.orders.remove(OrderKeys.of(ctx));
        }

        @Override
        public void reject(final OrderContext ctx, final RejectReason reason, final long eventNanos) {
            this.rejects++;
            this.orders.remove(OrderKeys.of(ctx));
        }

        @Override
        public void release(final OrderContext ctx) {
            this.releases++;
            this.orders.remove(OrderKeys.of(ctx));
        }

        @Override
        public long receiveNanos() {
            return this.receiveNanos;
        }
    }

    /** An answer as the HTTP client hands it over; its body is read from the start each time it's reused. */
    public static final class Answer {
        private final HTTPResponse response = new HTTPResponse() {};
        private final ByteBuffer body;
        private final int status;

        public Answer(final int status, final String body) {
            this.status = status;
            this.body = ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
        }

        public HTTPResponse get() {
            this.body.rewind();
            return this.response.update(this.status >= 200 && this.status < 300, this.status, this.body);
        }
    }

    /** A websocket frame, decoded as a reader decodes it: its top-level object handed to {@code handler}. */
    public static final class Frame {
        private final ByteBuffer bytes;

        public Frame(final String json) {
            this.bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        }

        public void decode(final JsonDecoder decoder, final Consumer<JsonDecoder.JsonObject> handler) {
            this.bytes.rewind();
            try (var node = decoder.wrap(this.bytes);
                    var obj = node.asObject()) {
                handler.accept(obj);
            }
        }
    }

    /** Runs {@code work} {@code warmUp} times, then fails if {@code measured} more runs allocate. */
    public static void assertNoAllocation(
            final String name, final int warmUp, final int measured, final Runnable work) {
        for (int i = 0; i < warmUp; i++) {
            work.run();
        }
        final long thread = Thread.currentThread().getId();
        final long before = THREADS.getThreadAllocatedBytes(thread);
        for (int i = 0; i < measured; i++) {
            work.run();
        }
        final long allocated = THREADS.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated < MAX_BYTES, name + " allocated " + allocated + " bytes over " + measured + " runs");
    }

    /** Times each of {@code measured} runs after a warm-up and prints percentiles and allocation, as the feed's do. */
    /**
     * As {@link #time}, timing only {@code timed} of each run: {@code before} and {@code after} set each run up and
     * see it through untimed (their allocation still counts).
     */
    public static void timeSection(
            final String name,
            final int warmUp,
            final int measured,
            final Runnable before,
            final Runnable timed,
            final Runnable after) {
        for (int i = 0; i < warmUp; i++) {
            before.run();
            timed.run();
            after.run();
        }
        final long[] nanos = new long[measured];
        final long thread = Thread.currentThread().getId();
        final long allocatedBefore = THREADS.getThreadAllocatedBytes(thread);
        for (int i = 0; i < measured; i++) {
            before.run();
            final long start = System.nanoTime();
            timed.run();
            nanos[i] = System.nanoTime() - start;
            after.run();
        }
        final long allocated = THREADS.getThreadAllocatedBytes(thread) - allocatedBefore;
        Arrays.sort(nanos);
        System.out.printf(
                "%-52s p50 %7d ns  p99 %7d ns  p99.9 %8d ns  max %9d ns  alloc %d B%n",
                name,
                nanos[measured / 2],
                nanos[(int) (measured * 0.99)],
                nanos[(int) (measured * 0.999)],
                nanos[measured - 1],
                allocated);
    }

    public static void time(final String name, final int warmUp, final int measured, final Runnable work) {
        for (int i = 0; i < warmUp; i++) {
            work.run();
        }
        final long[] nanos = new long[measured];
        final long thread = Thread.currentThread().getId();
        final long before = THREADS.getThreadAllocatedBytes(thread);
        for (int i = 0; i < measured; i++) {
            final long start = System.nanoTime();
            work.run();
            nanos[i] = System.nanoTime() - start;
        }
        final long allocated = THREADS.getThreadAllocatedBytes(thread) - before;
        Arrays.sort(nanos);
        System.out.printf(
                "%-52s p50 %7d ns  p99 %7d ns  p99.9 %8d ns  max %9d ns  alloc %d B%n",
                name,
                nanos[measured / 2],
                nanos[(int) (measured * 0.99)],
                nanos[(int) (measured * 0.999)],
                nanos[measured - 1],
                allocated);
    }
}
