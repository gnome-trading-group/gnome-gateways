package group.gnometrading.gateways.inbound;

import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class WebSocketReader<T extends Schema> extends SocketReader<T> {

    protected final WebSocketClient socketClient;

    public WebSocketReader(
            Logger logger,
            SequencedRingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient) {
        super(logger, outputBuffer, clock, socketWriter, listing);
        this.socketClient = socketClient;
    }

    @Override
    protected final ByteBuffer readSocket() throws IOException {
        final var result = this.socketClient.read();

        if (result.isSuccess() && (result.getOpcode() == Opcode.TEXT || result.getOpcode() == Opcode.BINARY)) {
            return result.getBody();
        } else if (result.isClosed()) {
            this.onSocketClose();
            return null;
        } else if (result.getOpcode() == Opcode.PING) {
            pong();
            return null;
        } else {
            return null;
        }
    }

    private void pong() {
        ((WebSocketWriter) this.socketWriter).writePong();
    }

    @Override
    protected final void attachSocket() throws IOException {
        this.socketClient.connect();
        this.socketClient.configureBlocking(true);
        this.socketClient.setTcpNoDelay(true);
        this.socketClient.setKeepAlive(true);
        this.subscribe();
    }

    @Override
    public final void disconnectSocket() throws Exception {
        this.socketClient.close();
    }

    protected abstract void subscribe() throws IOException;
}
