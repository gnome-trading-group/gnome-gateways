package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class InboundJsonWebSocketReader<T extends Schema> extends InboundWebSocketReader<T> {

    protected final JsonDecoder jsonDecoder;

    public InboundJsonWebSocketReader(
            Logger logger,
            SequencedRingBuffer<T> outputBuffer,
            EpochNanoClock clock,
            InboundSocketWriter socketWriter,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, outputBuffer, clock, socketWriter, listing, socketClient);
        this.jsonDecoder = jsonDecoder;
    }

    @Override
    protected final void handleGatewayMessage(final ByteBuffer buffer) {
        ByteBufferUtils.skipWhitespace(buffer);
        if (!buffer.hasRemaining()) {
            return;
        }
        if (handleNonJsonMessage(buffer)) {
            buffer.position(buffer.limit());
            return;
        }
        try (var node = jsonDecoder.wrap(buffer)) {
            handleJsonMessage(node);
        }
    }

    /**
     * Allows venue readers to consume application-level text frames such as {@code PONG}.
     * Implementations must not advance the buffer when returning {@code false}.
     */
    protected boolean handleNonJsonMessage(ByteBuffer buffer) {
        return false;
    }

    protected abstract void handleJsonMessage(JsonDecoder.JsonNode node);
}
