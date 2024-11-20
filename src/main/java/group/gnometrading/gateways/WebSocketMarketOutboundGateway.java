package group.gnometrading.gateways;

import group.gnometrading.gateways.codecs.Encoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.objects.OrderDecoder;
import io.aeron.Subscription;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketMarketOutboundGateway extends MarketOutboundGateway {

    private final WebSocketClient socketClient;
    private final Encoder encoder;
    private final ByteBuffer writeBuffer;

    public WebSocketMarketOutboundGateway(
            final WebSocketClient socketClient,
            final Subscription subscription,
            final Encoder encoder,
            final int writeBufferSize
    ) {
        super(subscription);
        this.socketClient = socketClient;
        this.encoder = encoder;
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }

    @Override
    protected void send(final OrderDecoder orderDecoder) throws IOException {
        this.writeBuffer.clear();
        if (!this.encoder.encode(this.writeBuffer, orderDecoder)) {
            // TODO: Exit early here?
            return;
        }
        final int bytesToWrite = this.writeBuffer.remaining();
//        final int bytes = socketClient.writeMessage(writeBuffer);

//        if (bytes != bytesToWrite) {
//            throw new RuntimeException("Did not write all the bytes: " + bytes + " != " + bytesToWrite);
//        }
    }
}
