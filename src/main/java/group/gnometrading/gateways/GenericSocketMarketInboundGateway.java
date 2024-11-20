package group.gnometrading.gateways;

import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.Resettable;
import io.aeron.Publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public abstract class GenericSocketMarketInboundGateway<T extends Resettable> extends MarketInboundGateway<T> {

    protected final SocketClient socketClient;

    public GenericSocketMarketInboundGateway(
            final SocketClient socketClient,
            final Publication publication,
            final Decoder<T> decoder,
            final T messageHolder,
            final List<Listing> listings
    ) {
        super(publication, decoder, messageHolder, listings);
        this.socketClient = socketClient;
    }

    @Override
    protected ByteBuffer readSocket() throws IOException {
        final int result = this.socketClient.read();
        if (result < 0) {
            this.onSocketClose();
            return null;
        } else if (result == 0) {
            return null;
        } else {
            return this.socketClient.getReadBuffer();
        }
    }
}
