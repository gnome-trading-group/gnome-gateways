package group.gnometrading.gateways;

import group.gnometrading.networking.websockets.WebSocketClient;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketMarketInboundGateway extends MarketInboundGateway implements SocketAgent {

    protected final WebSocketClient socketClient;

    public WebSocketMarketInboundGateway(WebSocketClient socketClient, Publication publication, EpochNanoClock clock) {
        super(publication, clock);
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
}
