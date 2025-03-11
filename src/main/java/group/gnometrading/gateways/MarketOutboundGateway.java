package group.gnometrading.gateways;

import group.gnometrading.schemas.OrderDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

import java.io.IOException;

public abstract class MarketOutboundGateway implements SocketAgent {

    private static final int FRAGMENT_LIMIT = 1;

    private final Subscription subscription;
    private final OrderDecoder orderDecoder;

    public MarketOutboundGateway(final Subscription subscription) {
        this.subscription = subscription;
        this.orderDecoder = new OrderDecoder();
    }

    @Override
    public int doWork() throws Exception {
        subscription.poll(this::onFragment, FRAGMENT_LIMIT);
        return 0;
    }

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        this.orderDecoder.wrap(buffer, offset, length, OrderDecoder.SCHEMA_VERSION);
        try {
            this.send(orderDecoder);
        } catch (IOException e) {
            // TODO: Somehow exit early here
            throw new RuntimeException(e);
        }
    }

    @Override
    public String roleName() {
        return getClass().getSimpleName();
    }

    protected abstract void send(final OrderDecoder orderDecoder) throws IOException;
}
