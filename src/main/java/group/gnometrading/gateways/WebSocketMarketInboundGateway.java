package group.gnometrading.gateways;

import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.Resettable;
import io.aeron.Publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public abstract class WebSocketMarketInboundGateway<T extends Resettable> extends MarketInboundGateway<T> {

    private final WebSocketClient socketClient;

    public WebSocketMarketInboundGateway(
            final WebSocketClient socketClient,
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
        final var result = this.socketClient.read();
        if (result.isSuccess()) {
            return result.getBody();
        } else if (result.isClosed()) {
            // TODO: What should we do when it's closed?
            this.onSocketClose();
            return null;
        } else {
            return null;
        }
    }
}
