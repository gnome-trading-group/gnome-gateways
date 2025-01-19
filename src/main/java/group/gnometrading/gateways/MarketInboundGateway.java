package group.gnometrading.gateways;

import group.gnometrading.objects.MarketUpdateEncoder;
import group.gnometrading.objects.MarketUpdateFlagsEncoder;
import group.gnometrading.objects.MessageHeaderEncoder;
import io.aeron.Publication;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class MarketInboundGateway implements Agent {

    private final Publication publication;
    protected final MarketUpdateEncoder marketUpdateEncoder;
    protected final MarketUpdateFlagsEncoder marketUpdateFlagsEncoder;
    protected final EpochNanoClock clock;
    protected long recvTimestamp;

    public MarketInboundGateway(final Publication publication, final EpochNanoClock clock) {
        this.publication = publication;
        this.marketUpdateEncoder = new MarketUpdateEncoder();
        this.marketUpdateFlagsEncoder = new MarketUpdateFlagsEncoder();
        this.clock = clock;

        final int totalMessageSize = MessageHeaderEncoder.ENCODED_LENGTH + MarketUpdateEncoder.BLOCK_LENGTH;
        final UnsafeBuffer directBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(totalMessageSize));

        this.marketUpdateEncoder.wrapAndApplyHeader(directBuffer, 0, new MessageHeaderEncoder());
        this.marketUpdateFlagsEncoder.wrap(directBuffer, this.marketUpdateEncoder.offset() + MarketUpdateEncoder.flagsEncodingOffset());
    }

    @Override
    public int doWork() throws Exception {
//        assert publication.isConnected(); // TODO: What to test here?
        final ByteBuffer buffer = readSocket();
        while (buffer != null && buffer.hasRemaining()) {
            StringBuilder s = new StringBuilder();
            for (int i = buffer.position(); i < buffer.limit(); i++) {
                s.append((char) buffer.get(i));
            }
            this.recvTimestamp = clock.nanoTime();
            handleGatewayMessage(buffer);
        }
        return 0;
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(final ByteBuffer buffer) throws IOException;

    @Override
    public String roleName() {
        return getClass().getSimpleName();
    }

    protected long offer() {
        final long result = publication.offer(marketUpdateEncoder.buffer());
        if (result < 0) {
            throw new RuntimeException("Invalid offer: " + result);
        }
        return result;
    }
}
