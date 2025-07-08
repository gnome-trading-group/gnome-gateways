package group.gnometrading.gateways.fix;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.gateways.GenericSocketMarketInboundGateway;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class FIXMarketInboundGateway extends GenericSocketMarketInboundGateway implements FIXStatusListener {

    protected final FIXSession fixSession;
    protected final FIXMessage adminMessage;
    private final FIXMessage message;
    protected final FIXConfig fixConfig;

    public FIXMarketInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock,
            SocketClient socketClient,
            FIXConfig fixConfig
    ) {
        super(ringBuffer, clock, socketClient);

        this.message = new FIXMessage(fixConfig);
        this.adminMessage = new FIXMessage(fixConfig);
        this.fixSession = new FIXSession(fixConfig, socketClient, this);
        this.fixConfig = fixConfig;
    }

    @Override
    protected void handleGatewayMessage(final ByteBuffer buffer) throws IOException {
        if (!this.message.parseBuffer(buffer)) {
            return; // incomplete message
        }

        if (!fixSession.handleFIXMessage(message)) {
            handleMarketUpdate(message);
        }

        fixSession.keepAlive();
    }

    protected abstract void handleMarketUpdate(final FIXMessage message);
}
