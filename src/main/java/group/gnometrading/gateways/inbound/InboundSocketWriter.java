package group.gnometrading.gateways.inbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.collections.buffer.RingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

public abstract class InboundSocketWriter implements GnomeAgent {

    private static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 10; // 1kb
    private static final int DEFAULT_MESSAGE_BUS_CAPACITY = 1 << 7; // 128 slots

    // Held while writing, and by the reader while it closes the socket, so the socket is never closed mid-write.
    private final Object socketLock = new Object();
    private final RingBuffer<ByteBuffer> writeBuffer;
    private final RingBuffer<ByteBuffer> controlWriteBuffer;
    // Held once, so the hot loop doesn't create a method reference on every pass.
    private final MessageConsumer<ByteBuffer> writeHandler = this::handleWrite;
    private final int writeBufferSize;

    public InboundSocketWriter() {
        this(DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_MESSAGE_BUS_CAPACITY);
    }

    public InboundSocketWriter(int writeBufferSize, int messageBusCapacity) {
        this.writeBufferSize = writeBufferSize;
        this.writeBuffer = new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);
        this.controlWriteBuffer =
                new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);
    }

    private ByteBuffer createWriteBuffer() {
        return ByteBuffer.allocateDirect(this.writeBufferSize);
    }

    protected abstract void write(ByteBuffer buffer) throws IOException;

    /** Whether the socket is open to write to; messages queued while it is closed are dropped. */
    protected boolean isOpen() {
        return true;
    }

    public final Object socketLock() {
        return this.socketLock;
    }

    @Override
    public final int doWork() {
        this.writeBuffer.read(this.writeHandler);
        this.controlWriteBuffer.read(this.writeHandler);
        return 0;
    }

    private void handleWrite(ByteBuffer buffer) {
        buffer.flip();
        try {
            synchronized (this.socketLock) {
                // A message for a connection that has since closed cannot be sent, and failing on it would only
                // trigger a needless reconnect on top of the one already under way.
                if (isOpen()) {
                    this.write(buffer);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            buffer.clear();
        }
    }

    public final void publishWriteBuffer(int writeSequence) {
        this.writeBuffer.commit(writeSequence);
    }

    public final int claimWriteBuffer() {
        int writeSequence = this.writeBuffer.tryClaim();
        if (writeSequence < 0) {
            throw new RuntimeException("Write buffer is full");
        }
        return writeSequence;
    }

    public final ByteBuffer getWriteBuffer(int writeSequence) {
        return this.writeBuffer.indexAt(writeSequence);
    }

    public final void publishControlWriteBuffer(int controlWriteSequence) {
        this.controlWriteBuffer.commit(controlWriteSequence);
    }

    public final int claimControlWriteBuffer() {
        int controlWriteSequence = this.controlWriteBuffer.tryClaim();
        if (controlWriteSequence < 0) {
            throw new RuntimeException("Control write buffer is full");
        }
        return controlWriteSequence;
    }

    public final ByteBuffer getControlWriteBuffer(int controlWriteSequence) {
        return this.controlWriteBuffer.indexAt(controlWriteSequence);
    }
}
