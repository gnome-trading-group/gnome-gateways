package group.gnometrading.gateways;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import io.aeron.Publication;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class JSONWebSocketMarketInboundGateway extends WebSocketMarketInboundGateway {

    public static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 10; // 1kb

    private final JSONDecoder jsonDecoder;
    protected final ByteBuffer writeBuffer;

    public JSONWebSocketMarketInboundGateway(
            WebSocketClient socketClient,
            Publication publication,
            EpochNanoClock clock,
            JSONDecoder jsonDecoder,
            int writeBufferSize
    ) {
        super(socketClient, publication, clock);
        this.jsonDecoder = jsonDecoder;
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }

    @Override
    protected void handleGatewayMessage(final ByteBuffer buffer) throws IOException {
        try (final var node = jsonDecoder.wrap(buffer)) {
            handleJSONMessage(node);
        }
    }

    protected abstract void handleJSONMessage(final JSONDecoder.JSONNode node) throws IOException;
}
