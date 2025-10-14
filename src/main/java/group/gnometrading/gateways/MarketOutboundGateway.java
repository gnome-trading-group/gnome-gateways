package group.gnometrading.gateways;

import com.lmax.disruptor.EventHandler;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.Schema;

import java.io.IOException;

public abstract class MarketOutboundGateway implements SocketAgent, EventHandler<Schema> {

    private final OrderDecoder orderDecoder;

    public MarketOutboundGateway() {
        this.orderDecoder = new OrderDecoder();
    }

    @Override
    public void onEvent(Schema schema, long sequence, boolean endOfBatch) throws Exception {
        try {
            this.send(orderDecoder);
        } catch (IOException e) {
            // TODO: Somehow exit early here
            throw new RuntimeException(e);
        }
    }

    protected abstract void send(final OrderDecoder orderDecoder) throws IOException;
}
