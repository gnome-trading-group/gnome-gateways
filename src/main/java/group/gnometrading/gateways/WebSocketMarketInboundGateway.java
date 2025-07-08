package group.gnometrading.gateways;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketMarketInboundGateway extends MarketInboundGateway implements SocketAgent {

    protected final WebSocketClient socketClient;

    public WebSocketMarketInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock,
            WebSocketClient socketClient
    ) {
        super(ringBuffer, clock);
        this.socketClient = socketClient;
    }

    @Override
    protected ByteBuffer readSocket() throws IOException {
        final var result = this.socketClient.read();
        if (result.isSuccess()) {
            return result.getBody();
        } else if (result.isClosed()) {
            // TODO: What should we do when it's closed?
            this.onSocketClose();
            return null;
        } else {
            return null;
        }
    }

    @Override
    public void onClose() {
        try {
            this.socketClient.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
