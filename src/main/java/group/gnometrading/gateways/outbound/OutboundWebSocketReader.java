package group.gnometrading.gateways.outbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class OutboundWebSocketReader extends OutboundSocketReader {

    private static final ByteBuffer EMPTY_PONG = ByteBuffer.allocate(0);

    protected final WebSocketClient socketClient;

    protected OutboundWebSocketReader(
            Logger logger,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue,
            EpochNanoClock clock,
            Listing listing,
            WebSocketClient socketClient) {
        super(logger, execReportBuffer, contextQueue, rejectQueue, completionQueue, clock, listing);
        this.socketClient = socketClient;
    }

    @Override
    protected final ByteBuffer readSocket() throws IOException {
        final var result = this.socketClient.read();
        if (!result.isSuccess()) {
            return null;
        }
        if (result.isClosed()) {
            onSocketClose();
            return null;
        }
        if (result.getOpcode() == Opcode.PING) {
            this.recvTimestamp = clock.nanoTime();
            pong(result.getBody());
            return null;
        }
        if (result.getOpcode() == Opcode.TEXT || result.getOpcode() == Opcode.BINARY) {
            return result.getBody();
        }
        return null;
    }

    protected void beforeConnect() throws IOException {}

    @Override
    protected final void attachSocket() throws IOException {
        beforeConnect();
        this.socketClient.connect();
        this.socketClient.configureBlocking(false);
        this.socketClient.setTcpNoDelay(true);
        this.socketClient.setKeepAlive(true);
        subscribe();
    }

    @Override
    protected final void disconnectSocket() throws Exception {
        this.socketClient.close();
    }

    private void pong(final ByteBuffer body) throws IOException {
        final ByteBuffer pongBody = (body != null && body.hasRemaining()) ? body : EMPTY_PONG;
        pongBody.rewind();
        this.socketClient.writeMessage(Opcode.PONG, pongBody);
    }
}
