package group.gnometrading.gateways;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class MarketInboundGateway implements GnomeAgent {

    private final RingBuffer<Schema<?, ?>> ringBuffer;
    protected final EpochNanoClock clock;

    protected long recvTimestamp;
    protected Schema<?, ?> schema;

    private boolean shouldReconnect;
    private long sequence;

    public MarketInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock
    ) {
        this.ringBuffer = ringBuffer;
        this.clock = clock;
        this.shouldReconnect = false;
    }


    @Override
    public int doWork() throws Exception {
        if (this.shouldReconnect) {
            this.shouldReconnect = false;
            this.reconnect();
        }

        final ByteBuffer buffer = readSocket();
        while (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(final ByteBuffer buffer) throws IOException;

    protected abstract void reconnect();

    /**
     * Set this market gateway as marked to be reconnected. The reconnection
     * should *only* happen within the same thread, so external threads can use
     * this to signify it's time to reconnect.
     */
    public void markReconnect() {
        this.shouldReconnect = true;
    }

    @Override
    public String roleName() {
        return getClass().getSimpleName();
    }

    protected void claim() {
        this.sequence = this.ringBuffer.next();
        this.schema = this.ringBuffer.get(this.sequence);
    }

    protected void offer() {
        this.ringBuffer.publish(this.sequence);
    }
}
