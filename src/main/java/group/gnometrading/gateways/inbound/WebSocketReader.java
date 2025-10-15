package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketReader<T extends Schema> extends SocketReader<T> {

    protected final WebSocketClient socketClient;

    public WebSocketReader(
            RingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            WebSocketClient socketClient
    ) {
        super(outputBuffer, clock, socketWriter);
        this.socketClient = socketClient;
    }

    @Override
    protected ByteBuffer readSocket() throws IOException {
        final var result = this.socketClient.read();
        if (result.isSuccess()) {
            return result.getBody();
        } else if (result.isClosed()) {
            this.onSocketClose();
            return null;
        } else {
            return null;
        }
    }

    @Override
    protected void attachSocket() throws IOException {
        this.socketClient.connect();
        this.socketClient.configureBlocking(true);
        this.socketClient.setTcpNoDelay(true);
        this.socketClient.setKeepAlive(true);
        this.subscribe();
    }

    @Override
    public void disconnectSocket() throws Exception {
        this.socketClient.close();
    }

    protected abstract void subscribe() throws IOException;
}
