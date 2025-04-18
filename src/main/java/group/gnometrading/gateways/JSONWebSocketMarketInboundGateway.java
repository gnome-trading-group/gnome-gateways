package group.gnometrading.gateways;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class JSONWebSocketMarketInboundGateway extends WebSocketMarketInboundGateway {

    public static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 10; // 1kb

    protected final JSONDecoder jsonDecoder;
    protected final JSONEncoder jsonEncoder;
    protected final ByteBuffer writeBuffer;

    public JSONWebSocketMarketInboundGateway(
            Publication publication,
            EpochNanoClock clock,
            Schema<?, ?> inputSchema,
            SchemaType outputSchemaType,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder,
            JSONEncoder jsonEncoder,
            int writeBufferSize
    ) {
        super(publication, clock, inputSchema, outputSchemaType, socketClient);
        this.jsonDecoder = jsonDecoder;
        this.jsonEncoder = jsonEncoder;
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
        this.jsonEncoder.wrap(this.writeBuffer);
    }

    @Override
    protected void handleGatewayMessage(final ByteBuffer buffer) throws IOException {
        try (final var node = jsonDecoder.wrap(buffer)) {
            handleJSONMessage(node);
        }
    }

    protected abstract void handleJSONMessage(final JSONDecoder.JSONNode node) throws IOException;
}
