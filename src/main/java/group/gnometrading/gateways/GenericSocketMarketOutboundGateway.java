package group.gnometrading.gateways;

import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.objects.OrderDecoder;
import io.aeron.Subscription;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class GenericSocketMarketOutboundGateway extends MarketOutboundGateway {

    private final SocketClient socketClient;
    private final ByteBuffer writeBuffer;

    public GenericSocketMarketOutboundGateway(
            final SocketClient socketClient,
            final Subscription subscription,
            final int writeBufferSize
    ) {
        super(subscription);
        this.socketClient = socketClient;
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }

    @Override
    protected void send(OrderDecoder orderDecoder) throws IOException {
        this.writeBuffer.clear();
//        if (!this.encoder.encode(this.writeBuffer, orderDecoder)) {
//            // TODO: We should exit early if we find this is the case
//            return;
//        }

        final int bytesToWrite = this.writeBuffer.remaining();
//        final int bytes = socketClient.write(writeBuffer);
//
//        if (bytes != bytesToWrite) {
//            throw new RuntimeException("Did not write all the bytes: " + bytes + " != " + bytesToWrite);
//        }
    }
}
