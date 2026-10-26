package group.gnometrading.gateways.feed.transport;

import group.gnometrading.gateways.GatewayConfig;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One connection's wire: a websocket now, a FIX session later. It answers its own keep-alives (websocket pings); a
 * venue's protocol talks through {@link #channel}.
 *
 * <p>{@link #open} and {@link #close} run on the connection's supervisor while its reader is paused; {@link #read} on
 * the reader.
 *
 * @param <C> what a venue's protocol sends through
 */
public interface Transport<C> {

    C channel();

    FrameWriter writer();

    /** Socket settings, applied once; the transport keeps them across reconnects. */
    void configure(GatewayConfig config) throws IOException;

    void open() throws IOException;

    void close() throws Exception;

    /**
     * The next frame of venue data, or null when there is none yet or the frame was the transport's own (a ping).
     * Throws once the connection is gone.
     */
    ByteBuffer read() throws IOException;

    /** Whether the last {@link #read} saw any traffic at all, data or the transport's own: proof the link is alive. */
    boolean sawTraffic();
}
