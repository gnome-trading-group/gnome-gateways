package group.gnometrading.gateways.account.reader;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.transport.FrameWriter;
import group.gnometrading.gateways.transport.Transport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** An account socket in memory: frames the test pushes, and a log of what the connection did to it, in order. */
public final class FakeAccountTransport implements Transport<FakeAccountTransport> {

    public final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
    public final List<String> log = new CopyOnWriteArrayList<>();
    // Set to break the socket: the next read throws, as a dead connection's does, until it is reopened.
    public volatile boolean broken;
    private volatile boolean open;
    private boolean traffic;
    private final FrameWriter writer = new FrameWriter(64) {
        @Override
        protected void send(final ByteBuffer frame) {}

        @Override
        protected boolean isOpen() {
            return open;
        }
    };

    public void push(final String frame) {
        this.frames.add(frame);
    }

    @Override
    public FakeAccountTransport channel() {
        return this;
    }

    @Override
    public FrameWriter writer() {
        return this.writer;
    }

    @Override
    public void configure(final GatewayConfig config) {}

    @Override
    public void open() {
        this.log.add("open");
        this.broken = false;
        this.open = true;
    }

    @Override
    public void close() {
        if (this.open) {
            this.log.add("close");
        }
        this.open = false;
    }

    @Override
    public ByteBuffer read() throws IOException {
        if (this.broken) {
            throw new IOException("connection reset");
        }
        final String frame = this.frames.poll();
        this.traffic = frame != null;
        return frame == null ? null : ByteBuffer.wrap(frame.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public boolean sawTraffic() {
        return this.traffic;
    }
}
