package group.gnometrading.gateways.outbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class OutboundJsonWebSocketReader extends OutboundWebSocketReader {

    protected final JsonDecoder jsonDecoder;

    protected OutboundJsonWebSocketReader(
            Logger logger,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ManyToOneRingBuffer<OrderContext> contextQueue,
            ManyToOneRingBuffer<OrderContext> rejectQueue,
            ManyToOneRingBuffer<OrderContext> completionQueue,
            EpochNanoClock clock,
            Listing listing,
            WebSocketClient socketClient,
            JsonDecoder jsonDecoder) {
        super(logger, execReportBuffer, contextQueue, rejectQueue, completionQueue, clock, listing, socketClient);
        this.jsonDecoder = jsonDecoder;
    }

    @Override
    protected final void handleGatewayMessage(final ByteBuffer buffer) throws Exception {
        ByteBufferUtils.skipWhitespace(buffer);
        if (!buffer.hasRemaining()) {
            return;
        }
        if (skipNonJsonMessage(buffer)) {
            return;
        }
        try (var node = this.jsonDecoder.wrap(buffer)) {
            try (var obj = node.asObject()) {
                handleJsonMessage(obj);
            }
        }
    }

    /**
     * Called before JSON parsing to allow subclasses to handle non-JSON messages.
     * Return true if the message was consumed and JSON parsing should be skipped.
     */
    protected boolean skipNonJsonMessage(final ByteBuffer buffer) throws IOException {
        return false;
    }

    protected abstract void handleJsonMessage(JsonDecoder.JsonObject obj) throws Exception;
}
