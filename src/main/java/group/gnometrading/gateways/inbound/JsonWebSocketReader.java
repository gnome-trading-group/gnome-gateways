package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class JsonWebSocketReader<T extends Schema> extends WebSocketReader<T> {

    protected final JsonDecoder jsonDecoder;

    public JsonWebSocketReader(
            Logger logger,
            SequencedRingBuffer<T> outputBuffer,
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
        skipWhitespace(buffer);
        if (!buffer.hasRemaining()) {
            return;
        }
        if (handleNonJsonMessage(buffer.asReadOnlyBuffer())) {
            buffer.position(buffer.limit());
            return;
        }
        try (var node = jsonDecoder.wrap(buffer)) {
            handleJsonMessage(node);
        }
    }

    /** Allows venue readers to consume application-level text frames such as {@code PONG}. */
    protected boolean handleNonJsonMessage(ByteBuffer buffer) {
        return false;
    }

    private static void skipWhitespace(final ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            final byte next = buffer.get(buffer.position());
            if (next != ' ' && next != '\n' && next != '\r' && next != '\t') {
                return;
            }
            buffer.get();
        }
    }

    protected abstract void handleJsonMessage(JsonDecoder.JsonNode node);
}
