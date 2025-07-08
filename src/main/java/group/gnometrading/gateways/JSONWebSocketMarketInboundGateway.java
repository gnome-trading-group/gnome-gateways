package group.gnometrading.gateways;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class JSONWebSocketMarketInboundGateway extends WebSocketMarketInboundGateway {

    public static final int DEFAULT_WRITE_BUFFER_SIZE = 1 << 10; // 1kb

    protected final JSONDecoder jsonDecoder;
    protected final JSONEncoder jsonEncoder;
    protected final ByteBuffer writeBuffer;

    public JSONWebSocketMarketInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder,
            JSONEncoder jsonEncoder,
            int writeBufferSize
    ) {
        super(ringBuffer, clock, socketClient);
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
