package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.nio.ByteBuffer;

public abstract class JSONWebSocketReader<T extends Schema> extends WebSocketReader<T> {

    protected final JSONDecoder jsonDecoder;

    public JSONWebSocketReader(
            Logger logger,
            RingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            WebSocketClient socketClient,
            JSONDecoder jsonDecoder
    ) {
        super(logger, outputBuffer, clock, socketWriter, socketClient);
        this.jsonDecoder = jsonDecoder;
    }

    @Override
    protected void handleGatewayMessage(final ByteBuffer buffer) {
        try (final var node = jsonDecoder.wrap(buffer)) {
            handleJSONMessage(node);
        }
    }

    protected abstract void handleJSONMessage(final JSONDecoder.JSONNode node);

}
