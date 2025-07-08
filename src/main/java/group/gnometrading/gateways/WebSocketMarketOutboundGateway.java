package group.gnometrading.gateways;

import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.schemas.OrderDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class WebSocketMarketOutboundGateway extends MarketOutboundGateway {

    private final WebSocketClient socketClient;
    private final ByteBuffer writeBuffer;

    public WebSocketMarketOutboundGateway(
            final WebSocketClient socketClient,
            final int writeBufferSize
    ) {
        this.socketClient = socketClient;
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }

    @Override
    protected void send(final OrderDecoder orderDecoder) throws IOException {
        this.writeBuffer.clear();
//        if (!this.encoder.encode(this.writeBuffer, orderDecoder)) {
//            // TODO: Exit early here?
//            return;
//        }
        final int bytesToWrite = this.writeBuffer.remaining();
//        final int bytes = socketClient.writeMessage(writeBuffer);

//        if (bytes != bytesToWrite) {
//            throw new RuntimeException("Did not write all the bytes: " + bytes + " != " + bytesToWrite);
//        }
    }
}
