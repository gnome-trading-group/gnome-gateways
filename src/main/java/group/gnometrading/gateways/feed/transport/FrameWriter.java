package group.gnometrading.gateways.feed.transport;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.collections.buffer.RingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * The thread that sends on a connection. Any thread queues a framed message; this one writes them in order. Control
 * messages (pings, pongs) have their own queue so a backlog of commands never delays them.
 *
 * <p>Each message belongs to the socket it was queued for: one still queued when the connection is reopened is dropped,
 * as it was meant for a session the venue no longer knows (a Kalshi command naming the old subscription, say).
 */
public abstract class FrameWriter implements GnomeAgent {

    static final int QUEUE_SLOTS = 1 << 7;

    /** A queued message and the socket it was queued for. */
    private static final class Slot {
        final ByteBuffer frame;
        long generation;

        Slot(final ByteBuffer frame) {
            this.frame = frame;
        }
    }

    // Held while writing, and while the socket is closed, so it is never closed mid-write.
    private final Object socketLock = new Object();
    private final RingBuffer<Slot> messages;
    private final RingBuffer<Slot> control;
    private final MessageConsumer<Slot> writeHandler = this::write;
    private final int slotSize;
    // Which socket messages are queued for now; moved on under the socket lock as a connection is reopened.
    private volatile long generation;

    protected FrameWriter(final int slotSize) {
        this.slotSize = slotSize;
        this.messages = new ManyToOneRingBuffer<>(Slot[]::new, this::newSlot, QUEUE_SLOTS);
        this.control = new ManyToOneRingBuffer<>(Slot[]::new, this::newSlot, QUEUE_SLOTS);
    }

    private Slot newSlot() {
        return new Slot(ByteBuffer.allocateDirect(slotSize));
    }

    /** How many more messages may be queued now; a caller sending several together checks first. */
    public final int messageRoom() {
        return messages.remaining();
    }

    /**
     * With the socket lock held, as the socket is reopened: what is still queued was meant for the old one and is
     * dropped rather than sent on the new.
     */
    public final void newSocket() {
        generation++;
    }

    /** The most bytes one framed message may take. */
    public final int slotSize() {
        return slotSize;
    }

    public final Object socketLock() {
        return socketLock;
    }

    protected abstract void send(ByteBuffer frame) throws IOException;

    /** Whether there is a socket to send on; messages queued while there isn't are dropped. */
    protected abstract boolean isOpen();

    @Override
    public final int doWork() {
        control.read(writeHandler);
        messages.read(writeHandler);
        return 0;
    }

    private void write(final Slot slot) {
        final ByteBuffer frame = slot.frame;
        frame.flip();
        // A slot committed empty: its message failed to frame and was never meant to be sent.
        if (!frame.hasRemaining()) {
            frame.clear();
            return;
        }
        try {
            synchronized (socketLock) {
                if (slot.generation == generation && isOpen()) {
                    send(frame);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            frame.clear();
        }
    }

    /** A slot to frame a message into, then {@link #commit}; throws when the queue is full. */
    protected final int claim(final boolean isControl) {
        final int sequence = (isControl ? control : messages).tryClaim();
        if (sequence < 0) {
            throw new IllegalStateException((isControl ? "Control" : "Message") + " queue is full");
        }
        return sequence;
    }

    protected final ByteBuffer slot(final boolean isControl, final int sequence) {
        return (isControl ? control : messages).indexAt(sequence).frame;
    }

    /** Always called for a claimed slot, framed or not: the queue stops at the first slot never committed. */
    protected final void commit(final boolean isControl, final int sequence) {
        final RingBuffer<Slot> queue = isControl ? control : messages;
        queue.indexAt(sequence).generation = generation;
        queue.commit(sequence);
    }
}
