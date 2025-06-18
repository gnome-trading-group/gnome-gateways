package group.gnometrading.gateways;

import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketMarketInboundGateway extends MarketInboundGateway implements SocketAgent {

    protected final WebSocketClient socketClient;

    public WebSocketMarketInboundGateway(
            Publication publication,
            EpochNanoClock clock,
            Schema<?, ?> inputSchema,
            SchemaType outputSchemaType,
            WebSocketClient socketClient
    ) {
        super(publication, clock, inputSchema, outputSchemaType);
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
        super.onClose();
        try {
            this.socketClient.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
