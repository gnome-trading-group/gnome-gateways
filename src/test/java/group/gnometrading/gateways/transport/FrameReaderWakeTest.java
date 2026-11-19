package group.gnometrading.gateways.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.SocketPoller;
import group.gnometrading.networking.sockets.SocketWaker;
import group.gnometrading.schemas.RejectReason;
import java.nio.ByteBuffer;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Test;

/** A wakeable reader takes one last look for handed-over work before each read, and doesn't park after one. */
class FrameReaderWakeTest {

    /** A blocking transport with nothing to read; it records the waker it was given. */
    private static final class QuietTransport implements Transport<Object> {
        SocketWaker waker;
        int reads;

        @Override
        public Object channel() {
            return null;
        }

        @Override
        public FrameWriter writer() {
            return null;
        }

        @Override
        public void configure(final GatewayConfig config) {}

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public ByteBuffer read() {
            this.reads++;
            return null;
        }

        @Override
        public boolean sawTraffic() {
            return false;
        }

        @Override
        public boolean blocks() {
            return true;
        }

        @Override
        public void wakeReadsWith(final SocketWaker waker) {
            this.waker = waker;
        }
    }

    /** Counts its looks for handed-over work. */
    private static final class Looks implements FrameReader.Handler {
        int looks;

        @Override
        public void beforeRead() {
            this.looks++;
        }

        @Override
        public void onFrame(final ByteBuffer frame, final long receiveNanos) {}
    }

    @Test
    void aWakeableReaderLooksAgainOnceArmedAndCountsItsWaitAsWork() throws Exception {
        final QuietTransport transport = new QuietTransport();
        final Looks handler = new Looks();
        final FrameReader reader = reader(transport, handler);
        try (SocketWaker waker = SocketWaker.create()) {
            reader.wakeWith(waker);
            assertSame(waker, transport.waker, "the transport's reads are woken by the same waker");
            assertEquals(1, reader.doWork(), "a wait on the socket is the pass's idling");
            assertEquals(2, handler.looks, "a look, then the last look once armed");
            assertEquals(1, transport.reads);
        }
    }

    @Test
    void withoutAWakerAReaderIsAsBefore() {
        final QuietTransport transport = new QuietTransport();
        final Looks handler = new Looks();
        final FrameReader reader = reader(transport, handler);
        assertEquals(0, reader.doWork());
        assertEquals(1, handler.looks);
    }

    @Test
    void reportNoticesWakeTheReaderButRegistrationsDont() throws Exception {
        final ManyToOneRingBuffer<OrderContext> queue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 16);
        final ReaderNotices notices = new ReaderNotices(queue);
        final SocketPoller poller = new SocketPoller();
        try (SocketWaker waker = SocketWaker.create()) {
            notices.wakeReader(waker);
            final OrderContext ctx = new OrderContext();
            waker.arm();
            notices.register(ctx);
            assertEquals(SocketPoller.TIMED_OUT, poller.await(waker.fd(), 0), "a registration wrote a wake");
            notices.reject(ctx, RejectReason.GATEWAY_REJECTED);
            assertEquals(SocketPoller.WOKEN, poller.await(waker.fd(), 0), "a report wrote no wake");
            notices.reject(ctx, RejectReason.GATEWAY_REJECTED);
            assertEquals(SocketPoller.TIMED_OUT, poller.await(waker.fd(), 0), "an unarmed waker wrote a wake");
        }
    }

    /**
     * A reader whose first pass is let through as its supervisor would after connecting: a reader starts paused, and
     * resume() waits for it to run, so it is resumed from another thread.
     */
    private static FrameReader reader(final QuietTransport transport, final Looks handler) {
        final ReaderPauseControl pause = new ReaderPauseControl();
        final Thread supervisor = new Thread(pause::resume);
        supervisor.setDaemon(true);
        supervisor.start();
        return new FrameReader(
                "test", "account-reader", new NullLogger(), transport, pause, new SystemEpochNanoClock(), handler);
    }
}
