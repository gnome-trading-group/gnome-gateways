package group.gnometrading.gateways.account.reader;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;

/** A protocol whose frames are JSON events: one object, or several batched in an array. */
public abstract class JsonAccountProtocol<C> implements AccountProtocol<C> {

    protected final JsonDecoder jsonDecoder = new JsonDecoder();

    @Override
    public final void onFrame(final ByteBuffer frame, final OrderEventSink sink) {
        ByteBufferUtils.skipWhitespace(frame);
        if (!frame.hasRemaining() || skipNonJson(frame)) {
            return;
        }
        final boolean batched = frame.get(frame.position()) == '[';
        try (var node = this.jsonDecoder.wrap(frame)) {
            if (batched) {
                onBatch(node, sink);
            } else {
                try (var obj = node.asObject()) {
                    onEvent(obj, sink);
                }
            }
        }
    }

    private void onBatch(final JsonDecoder.JsonNode node, final OrderEventSink sink) {
        try (var items = node.asArray()) {
            while (items.hasNextItem()) {
                try (var item = items.nextItem();
                        var obj = item.asObject()) {
                    onEvent(obj, sink);
                }
            }
        }
    }

    /** A frame that isn't JSON, such as a text keep-alive's answer: true if it was handled here. */
    protected boolean skipNonJson(final ByteBuffer frame) {
        return false;
    }

    protected abstract void onEvent(JsonDecoder.JsonObject event, OrderEventSink sink);
}
