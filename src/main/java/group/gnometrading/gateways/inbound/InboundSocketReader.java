package group.gnometrading.gateways.inbound;

import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class InboundSocketReader<T extends Schema> implements GnomeAgent, SchemaFactory<T> {

    private static final int DEFAULT_BOOK_BUFFER_SIZE = 1 << 7; // 128 slots
    private static final int DEFAULT_REPLAY_BUFFER_SIZE = 1 << 11; // 2048 slots

    private final Logger logger;
    private final SequencedRingBuffer<T> sequencedRingBuffer;
    public final EpochNanoClock clock;
    protected final InboundSocketWriter socketWriter;
    protected final Listing listing;
    private final OneToOneRingBuffer<T> replayBuffer;

    public volatile long recvTimestamp = 0L;
    private RawDataSink rawDataSink = RawDataSink.NO_OP;
    protected T schema;
    protected Book<T> internalBook;
    private Book<T> snapshot;

    public volatile boolean pause;
    public volatile boolean isPaused;
    public volatile boolean buffer;

    public InboundSocketReader(
            Logger logger,
            SequencedRingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            InboundSocketWriter socketWriter,
            Listing listing) {
        this.logger = logger;
        this.sequencedRingBuffer = outputBuffer;
        this.clock = clock;
        this.socketWriter = socketWriter;
        this.listing = listing;
        this.replayBuffer =
                new OneToOneRingBuffer<>(this::createSchemaArray, this::createSchema, DEFAULT_REPLAY_BUFFER_SIZE);
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
    protected abstract void handleGatewayMessage(ByteBuffer buffer);

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
    public final void connect() throws IOException {
        this.buffer = true;
        pauseReader();

        // Connecting closes any previous connection, which the writer thread may be sending on.
        if (this.socketWriter != null) {
            synchronized (this.socketWriter.socketLock()) {
                this.attachSocket();
            }
        } else {
            this.attachSocket();
        }
        // Silence is measured from the new connection: left at the old connection's last message, the supervisor
        // would still see the silence that caused this reconnect and reconnect again before anything arrives.
        this.recvTimestamp = clock.nanoTime();
        this.internalBook.reset();
        this.replayBuffer.reset();

        resumeReader();

        this.snapshot = this.fetchSnapshot();
        if (this.snapshot != null) {
            this.internalBook.copyFrom(this.snapshot);
        }

        pauseReader();

        this.replayBuffer.read(this::consumeReplay);

        this.buffer = false;
        resumeReader();
    }

    /** Returns once the reader thread has stopped and will not touch the socket or buffers until resumed. */
    private void pauseReader() {
        this.pause = true;
        while (!this.isPaused) {
            Thread.yield();
        }
    }

    /**
     * Returns once the reader thread is running again. Waiting for it matters: until it notices, {@link #isPaused}
     * still reads true from before, and a pause straight after would take that stale value as an acknowledgement
     * while the reader is in fact about to run.
     */
    private void resumeReader() {
        this.pause = false;
        while (this.isPaused) {
            Thread.yield();
        }
    }

    /**
     * Applies the gateway's socket settings to the reader's connection. Called once, before the first connect;
     * readers whose connection keeps its settings across reconnects need nothing more.
     */
    public void configureSocket(final GatewayConfig config) throws IOException {}

    protected abstract void attachSocket() throws IOException;

    protected abstract void disconnectSocket() throws Exception;

    private void consumeReplay(final T schema) {
        if (snapshot == null) {
            this.schema.copyFrom(schema);
            this.sequencedRingBuffer.publish();
            this.claim();
        } else if (schema.getSequenceNumber() >= snapshot.getSequenceNumber()) {
            this.internalBook.updateFrom(schema);
        }
    }

    /**
     * Disconnect from the gateway.
     *
     * @throws Exception if there is an error disconnecting from the gateway
     */
    public final void disconnect() throws Exception {
        logger.log(LogMessage.SOCKET_DISCONNECTING);
        this.buffer = true;
        pauseReader();

        // The writer thread sends on the same socket; closing it mid-write would free it under the writer.
        if (this.socketWriter != null) {
            synchronized (this.socketWriter.socketLock()) {
                this.disconnectSocket();
            }
        } else {
            this.disconnectSocket();
        }
        this.internalBook.reset();
        this.replayBuffer.reset();
        logger.log(LogMessage.SOCKET_DISCONNECTED);
    }

    public final void setRawDataSink(RawDataSink sink) {
        this.rawDataSink = sink;
    }

    @Override
    public final int doWork() throws Exception {
        if (this.pause) {
            this.isPaused = true;
            while (this.pause) {
                Thread.yield();
            }
            this.isPaused = false;
        }

        final ByteBuffer buffer;
        try {
            buffer = readSocket();
        } catch (SocketClosedException e) {
            // The reader saw the close itself and has already paused and logged it.
            throw e;
        } catch (IOException | RuntimeException e) {
            // Whatever broke the read (a dead connection, a message larger than the buffer, a framing error)
            // breaks every read after it on this connection too, so stop and let it be reconnected. Rethrown
            // unpaused, it would fail again at once on every pass, and the error handler would kill the process.
            onSocketClose(e);
            return 0;
        }
        while (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            this.rawDataSink.capture(this.recvTimestamp, buffer);
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    protected final void claim() {
        this.schema = this.sequencedRingBuffer.claim();
    }

    protected final void offer() {
        if (this.buffer) {
            final int index = this.replayBuffer.tryClaim();
            if (index < 0) {
                throw new RuntimeException("Replay buffer overflow");
            }
            this.replayBuffer.indexAt(index).copyFrom(this.schema);
            this.replayBuffer.commit(index);
        } else {
            this.sequencedRingBuffer.publish();
            this.claim();
        }
    }

    protected final void onSocketClose() {
        onSocketClose(null);
    }

    private void onSocketClose(final Exception cause) {
        this.pause = true;
        logger.log(LogMessage.SOCKET_DISCONNECTED);
        throw new SocketClosedException(cause);
    }
}
