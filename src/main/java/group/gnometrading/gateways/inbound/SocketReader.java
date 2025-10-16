package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class SocketReader<T extends Schema> implements GnomeAgent, SchemaFactory<T> {

    private static final int DEFAULT_BOOK_BUFFER_SIZE = 1 << 7; // 128 slots
    private static final int DEFAULT_REPLAY_BUFFER_SIZE = 1 << 11; // 2048 slots

    private final RingBuffer<T> ringBuffer;
    private final EpochNanoClock clock;
    protected final SocketWriter socketWriter;
    private final OneToOneRingBuffer<T> replayBuffer;
    private long sequence;

    protected long recvTimestamp;
    protected T schema;
    protected Book<T> internalBook;
    private Book<T> snapshot;

    public volatile boolean pause, isPaused, buffer;

    public SocketReader(
            RingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter
    ) {
        this.ringBuffer = outputBuffer;
        this.clock = clock;
        this.socketWriter = socketWriter;
        this.replayBuffer = new OneToOneRingBuffer<>(this::createSchemaArray, this::createSchema, DEFAULT_REPLAY_BUFFER_SIZE);
        this.internalBook = createBook();
        this.snapshot = null;

        this.pause = true;
        this.buffer = true;
        this.isPaused = false;
        this.claim();
    }

    /**
     * Reads the socket and returns a ByteBuffer containing the data.
     * <p>
     * If the socket is closed, this method should return null.
     * If there is no data to read, this method should return null.
     *
     * @return ByteBuffer containing the data
     * @throws IOException if there is an error reading the socket
     */
    protected abstract ByteBuffer readSocket() throws IOException;


    /**
     * Handle a message from the gateway.
     * <p>
     * This method is responsible for producing the normalized schema object
     * and submitting it via offer().
     *
     * @param buffer the buffer containing the message
     */
    protected abstract void handleGatewayMessage(final ByteBuffer buffer);

    protected abstract void keepAlive() throws IOException;

    /**
     * Fetch a snapshot of the market data from the gateway.
     * <p>
     * If a snapshot is not needed for the exchange (ie, full depth is sent every update),
     * then this should return null.
     *
     * @return the snapshot of the market data
     * @throws IOException if there is an error fetching the snapshot
     */
    public abstract Book<T> fetchSnapshot() throws IOException;

    /**
     * Connect to the gateway and subscribe to the market feed.
     * <p>
     * The socket should be able to connect multiple times without creating a new SocketReader.
     *
     * @throws IOException if there is an error connecting to the gateway
     */
    public void connect() throws IOException {
        this.buffer = true;
        this.pause = true;
        while (!this.isPaused) {
            Thread.yield();
        }

        this.attachSocket();
        this.internalBook.reset();
        this.replayBuffer.reset();

        this.pause = false;

        this.snapshot = this.fetchSnapshot();
        if (this.snapshot != null) {
            this.internalBook.copyFrom(this.snapshot);
        }

        this.pause = true;
        while (!this.isPaused) {
            Thread.yield();
        }

        this.replayBuffer.read(this::consumeReplay);

        this.buffer = false;
        this.pause = false;
    }

    protected abstract void attachSocket() throws IOException;
    protected abstract void disconnectSocket() throws Exception;

    private void consumeReplay(final T schema) {
        if (snapshot == null || schema.getSequenceNumber() >= snapshot.getSequenceNumber()) {
            this.internalBook.updateFrom(schema);
        }
    }

    /**
     * Disconnect from the gateway.
     *
     * @throws Exception if there is an error disconnecting from the gateway
     */
    public void disconnect() throws Exception {
        this.pause = true;
        this.buffer = true;

        while (!this.isPaused) { // Wait for the main consumer thread to pause
            Thread.yield();
        }

        this.disconnectSocket();
        this.internalBook.reset();
        this.replayBuffer.reset();
    }

    @Override
    public int doWork() throws Exception {
        if (this.pause) {
            this.isPaused = true;
            while (this.pause) {
                Thread.yield();
            }
            this.isPaused = false;
        }

        final ByteBuffer buffer = readSocket();
        while (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    protected void claim() {
        this.sequence = this.ringBuffer.next();
        this.schema = this.ringBuffer.get(this.sequence);
    }

    protected void offer() {
        if (this.buffer) {
            final int index = this.replayBuffer.tryClaim();
            if (index < 0) {
                throw new RuntimeException("Replay buffer overflow");
            }
            this.replayBuffer.indexAt(index).copyFrom(this.schema);
            this.replayBuffer.commit(index);
        } else {
            this.ringBuffer.publish(this.sequence);
            this.claim();
        }
    }

    protected void onSocketClose() {
        this.pause = true;
        throw new RuntimeException("Socket closed");
    }
}
