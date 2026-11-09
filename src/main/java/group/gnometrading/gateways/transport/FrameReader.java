package group.gnometrading.gateways.transport;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

/**
 * The read loop of one connection, on its own hot-path thread: waits while its supervisor has it paused, reads a frame
 * and hands it, with when it arrived, to its {@link Handler}. A dead socket or a frame the handler can't read pauses
 * the reader itself and throws {@link SocketClosedException}, for the error handler to have the supervisor reconnect.
 * Shared by market data connections and account connections, which differ in what they do with a frame and how they
 * connect, not in how they read.
 */
public final class FrameReader implements GnomeAgent {

    /** What a connection does on its reader's thread. */
    public interface Handler {
        /** Before each read, while not paused: work other threads hand the reader. */
        default void beforeRead() {}

        /**
         * Whether to read the socket this pass. False holds what arrives on it (the socket buffers it) while the
         * handler's other work goes on.
         */
        default boolean mayRead() {
            return true;
        }

        /** A frame, whose bytes are valid only during the call; throwing marks the connection unreadable. */
        void onFrame(ByteBuffer frame, long receiveNanos);
    }

    private final String name;
    private final String roleName;
    private final Logger logger;
    private final Transport<?> transport;
    private final ReaderPauseControl pause;
    private final EpochNanoClock nanoClock;
    private final Handler handler;
    private volatile RawDataSink raw = RawDataSink.NO_OP;
    private volatile long lastTrafficNanos;

    /**
     * @param name the connection's name, in its log lines
     * @param roleName the reader thread's role
     */
    public FrameReader(
            final String name,
            final String roleName,
            final Logger logger,
            final Transport<?> transport,
            final ReaderPauseControl pause,
            final EpochNanoClock nanoClock,
            final Handler handler) {
        this.name = name;
        this.roleName = roleName;
        this.logger = logger;
        this.transport = transport;
        this.pause = pause;
        this.nanoClock = nanoClock;
        this.handler = handler;
    }

    @Override
    public int doWork() {
        if (!this.pause.awaitIfPaused()) {
            return 0;
        }
        this.handler.beforeRead();
        if (!this.handler.mayRead()) {
            return 0;
        }
        final ByteBuffer frame;
        try {
            frame = this.transport.read();
        } catch (Exception e) {
            this.pause.pauseSelf();
            this.logger.logf(LogMessage.SOCKET_DISCONNECTED, "%s: %s", this.name, e);
            throw new SocketClosedException(e);
        }
        if (this.transport.sawTraffic()) {
            this.lastTrafficNanos = this.nanoClock.nanoTime();
        }
        if (frame == null) {
            return 0;
        }
        final long receiveNanos = this.nanoClock.nanoTime();
        this.raw.capture(receiveNanos, frame);
        try {
            this.handler.onFrame(frame, receiveNanos);
        } catch (RuntimeException e) {
            // A frame that can't be read at all leaves everything on the connection in doubt.
            this.pause.pauseSelf();
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "%s: unreadable frame: %s", this.name, e);
            throw new SocketClosedException(e);
        }
        return 1;
    }

    /** When the socket last carried anything, frames or pings; read by the supervisor's silence check. */
    public long lastTrafficNanos() {
        return this.lastTrafficNanos;
    }

    /** Restarts the silence clock, as a fresh connection has carried nothing yet. */
    public void trafficAt(final long nanos) {
        this.lastTrafficNanos = nanos;
    }

    /** Where every frame read is also written raw, for disputes and replays; nowhere unless set. */
    public void setRawSink(final RawDataSink sink) {
        this.raw = sink;
    }

    @Override
    public void onClose() {
        this.pause.readerExited();
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return this.roleName;
    }
}
