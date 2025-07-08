package group.gnometrading.gateways;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class GenericSocketMarketInboundGateway extends MarketInboundGateway implements SocketAgent {

    protected final SocketClient socketClient;

    public GenericSocketMarketInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock,
            SocketClient socketClient
    ) {
        super(ringBuffer, clock);
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
