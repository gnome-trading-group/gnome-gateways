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

    public SocketWriter() {
        this(DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_MESSAGE_BUS_CAPACITY);
    }

    public SocketWriter(int writeBufferSize, int messageBusCapacity) {
        this.writeBufferSize = writeBufferSize;
        this.writeBuffer = new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);
        this.controlWriteBuffer = new ManyToOneRingBuffer<>(ByteBuffer[]::new, this::createWriteBuffer, messageBusCapacity);
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

    public void publishWriteBuffer(int writeSequence) {
        this.writeBuffer.commit(writeSequence);
    }

    public int claimWriteBuffer() {
        int writeSequence = this.writeBuffer.tryClaim();
        if (writeSequence < 0) {
            throw new RuntimeException("Write buffer is full");
        }
        return writeSequence;
    }

    public ByteBuffer getWriteBuffer(int writeSequence) {
        return this.writeBuffer.indexAt(writeSequence);
    }

    public void publishControlWriteBuffer(int controlWriteSequence) {
        this.controlWriteBuffer.commit(controlWriteSequence);
    }

    public int claimControlWriteBuffer() {
        int controlWriteSequence = this.controlWriteBuffer.tryClaim();
        if (controlWriteSequence < 0) {
            throw new RuntimeException("Control write buffer is full");
        }
        return controlWriteSequence;
    }

    public ByteBuffer getControlWriteBuffer(int controlWriteSequence) {
        return this.controlWriteBuffer.indexAt(controlWriteSequence);
    }
}
