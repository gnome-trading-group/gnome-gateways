package group.gnometrading.gateways;

import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.converters.SchemaConversionRegistry;
import group.gnometrading.schemas.converters.SchemaConverter;
import io.aeron.Publication;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class MarketInboundGateway implements Agent {

    private final Publication publication;
    private final SchemaConverter<Schema<?, ?>, Schema<?, ?>> schemaConverter;
    protected final Schema<?, ?> inputSchema;
    protected final EpochNanoClock clock;
    protected long recvTimestamp;
    private boolean shouldReconnect;

    public MarketInboundGateway(
            Publication publication,
            EpochNanoClock clock,
            Schema<?, ?> inputSchema,
            SchemaType outputSchemaType
    ) {
        this.publication = publication;
        this.clock = clock;
        this.inputSchema = inputSchema;
        this.shouldReconnect = false;

        if (outputSchemaType != inputSchema.schemaType) {
            schemaConverter = (SchemaConverter<Schema<?, ?>, Schema<?, ?>>) SchemaConversionRegistry.getConverter(inputSchema.schemaType, outputSchemaType);
        } else {
            schemaConverter = null;
        }
    }

    @Override
    public int doWork() throws Exception {
        if (this.shouldReconnect) {
            this.reconnect();
        }

        final ByteBuffer buffer = readSocket();
        while (buffer != null && buffer.hasRemaining()) {
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(final ByteBuffer buffer) throws IOException;

    protected abstract void reconnect();

    /**
     * Set this market gateway as marked to be reconnected. The reconnection
     * should *only* happen within the same thread, so external threads can use
     * this to signify it's time to reconnect.
     */
    public void markReconnect() {
        this.shouldReconnect = true;
    }

    @Override
    public String roleName() {
        return getClass().getSimpleName();
    }

    protected long offer() {
        long result;
        if (schemaConverter != null) {
            final var output = schemaConverter.convert(this.inputSchema);
            if (output != null) {
                result = publication.offer(output.buffer);
            } else {
                result = 0;
            }
        } else {
            result = publication.offer(this.inputSchema.buffer);
        }
        if (result == Publication.ADMIN_ACTION) {
            return offer(); // TODO: Should we sleep here?
        }
        if (result < 0) {
            throw new RuntimeException("Invalid offer: " + result);
        }
        return result;
    }
}
