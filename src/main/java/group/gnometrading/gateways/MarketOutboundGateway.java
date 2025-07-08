package group.gnometrading.gateways;

import com.lmax.disruptor.EventHandler;
import group.gnometrading.disruptor.SBEWrapper;
import group.gnometrading.schemas.OrderDecoder;

import java.io.IOException;

public abstract class MarketOutboundGateway implements SocketAgent, EventHandler<SBEWrapper> {

    private final OrderDecoder orderDecoder;

    public MarketOutboundGateway() {
        this.orderDecoder = new OrderDecoder();
    }

    @Override
    public void onEvent(SBEWrapper sbeWrapper, long sequence, boolean endOfBatch) throws Exception {
        this.orderDecoder.wrap(sbeWrapper.buffer, sbeWrapper.offset, sbeWrapper.length, OrderDecoder.SCHEMA_VERSION);
        try {
            this.send(orderDecoder);
        } catch (IOException e) {
            // TODO: Somehow exit early here
            throw new RuntimeException(e);
        }
    }

    protected abstract void send(final OrderDecoder orderDecoder) throws IOException;
}
