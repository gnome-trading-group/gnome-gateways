package group.gnometrading.gateways;

import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.objects.MarketUpdateEncoder;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;
import org.agrona.concurrent.Agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public abstract class MarketInboundGateway<T> implements Agent {

    private final Publication publication;
    protected final MarketUpdateEncoder marketUpdateEncoder;
    private final Decoder<T> decoder;
    private final T message;
    protected final List<Listing> listings;

    public MarketInboundGateway(
            final Publication publication,
            final Decoder<T> decoder,
            final T messageHolder,
            final List<Listing> listings
    ) {
        this.publication = publication;
        this.marketUpdateEncoder = new MarketUpdateEncoder();
        this.decoder = decoder;
        this.message = messageHolder;
        this.listings = listings;
    }

    @Override
    public int doWork() throws Exception {
//        assert publication.isConnected(); // TODO: What to test here?
        final ByteBuffer input = readSocket();
        while (input != null && input.hasRemaining() && this.decoder.decode(input, message)) {
            handleGatewayMessage(message);
        }
        return 0;
    }

    protected abstract ByteBuffer readSocket() throws IOException;

    protected abstract void handleGatewayMessage(final T message) throws IOException;

    @Override
    public String roleName() {
        return getClass().getSimpleName();
    }

    protected void offer() {
        publication.offer(marketUpdateEncoder.buffer());
    }
}
