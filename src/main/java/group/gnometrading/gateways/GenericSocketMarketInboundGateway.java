package group.gnometrading.gateways;

import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class GenericSocketMarketInboundGateway extends MarketInboundGateway implements SocketAgent {

    protected final SocketClient socketClient;

    public GenericSocketMarketInboundGateway(
            Publication publication,
            EpochNanoClock clock,
            Schema<?, ?> inputSchema,
            SchemaType outputSchemaType,
            SocketClient socketClient
    ) {
        super(publication, clock, inputSchema, outputSchemaType);
        this.socketClient = socketClient;
    }

    @Override
    protected ByteBuffer readSocket() throws IOException {
        final int result = this.socketClient.read();
        if (result < 0) {
            this.onSocketClose();
            return null;
        } else if (result == 0) {
            return null;
        } else {
            return this.socketClient.getReadBuffer();
        }
    }
}
