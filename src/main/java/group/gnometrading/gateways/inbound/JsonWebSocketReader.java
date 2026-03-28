package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sm.Listing;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class JsonWebSocketReader<T extends Schema> extends WebSocketReader<T> {

    protected final JsonDecoder jsonDecoder;

    public JsonWebSocketReader(
            Logger logger,
            RingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            SocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient);
        this.jsonDecoder = jsonDecoder;
    }

    @Override
    protected final void handleGatewayMessage(final ByteBuffer buffer) {
        try (var node = jsonDecoder.wrap(buffer)) {
            handleJsonMessage(node);
        }
    }

    protected abstract void handleJsonMessage(JsonDecoder.JsonNode node);
}
