package group.gnometrading.gateways.inbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.RingBuffer;
import group.gnometrading.concurrent.GnomeAgent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

public abstract class SocketWriter implements GnomeAgent {

    private static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 10; // 1kb
    private static final int DEFAULT_MESSAGE_BUS_CAPACITY = 1 << 7; // 128 slots

    private final RingBuffer<ByteBuffer> writeBuffer;
    private final RingBuffer<ByteBuffer> controlWriteBuffer;
    private final int writeBufferSize;

    private int writeSequence, controlWriteSequence;

    public SocketWriter() {
        this(DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_MESSAGE_BUS_CAPACITY);
    }

    public SocketWriter(int writeBufferSize, int messageBusCapacity) {
        this.writeBufferSize = writeBufferSize;
        this.writeBuffer = new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);
        this.controlWriteBuffer = new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);

        this.writeSequence = this.controlWriteSequence = -1;
    }

    private ByteBuffer createWriteBuffer() {
        return ByteBuffer.allocateDirect(this.writeBufferSize);
    }

    protected abstract void write(ByteBuffer buffer) throws IOException;

    @Override
    public int doWork() {
        this.writeBuffer.read(this::handleWrite);
        this.controlWriteBuffer.read(this::handleWrite);
        return 0;
    }

    private void handleWrite(ByteBuffer buffer) {
        buffer.flip();
        try {
            this.write(buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buffer.clear();
    }

    public boolean hasPendingWrites() {
        return this.controlWriteSequence >= 0 || this.writeSequence >= 0;
    }

    public void publishWriteBuffer() {
        this.writeBuffer.commit(this.writeSequence);
        this.writeSequence = -1;
    }

    public ByteBuffer getWriteBuffer() {
        this.writeSequence = this.writeBuffer.tryClaim();
        if (this.writeSequence < 0) {
            throw new RuntimeException("Write buffer is full");
        }
        return this.writeBuffer.indexAt(this.writeSequence);
    }

    public void publishControlWriteBuffer() {
        this.controlWriteBuffer.commit(this.controlWriteSequence);
        this.controlWriteSequence = -1;
    }

    public ByteBuffer getControlWriteBuffer() {
        this.controlWriteSequence = this.controlWriteBuffer.tryClaim();
        if (this.controlWriteSequence < 0) {
            throw new RuntimeException("Control write buffer is full");
        }
        return this.controlWriteBuffer.indexAt(this.controlWriteSequence);
    }
}
