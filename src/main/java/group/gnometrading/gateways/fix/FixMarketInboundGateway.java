package group.gnometrading.gateways.fix;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.schemas.Schema;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.agrona.concurrent.EpochNanoClock;

public abstract class FixMarketInboundGateway implements FixStatusListener {

    protected final FixSession fixSession;
    protected final FixMessage adminMessage;
    private final FixMessage message;
    protected final FixConfig fixConfig;

    public FixMarketInboundGateway(
            RingBuffer<Schema> ringBuffer, EpochNanoClock clock, SocketClient socketClient, FixConfig fixConfig) {
        //        super(ringBuffer, clock, socketClient);

        this.message = new FixMessage(fixConfig);
        this.adminMessage = new FixMessage(fixConfig);
        this.fixSession = new FixSession(fixConfig, socketClient, this);
        this.fixConfig = fixConfig;
    }

    protected final void handleGatewayMessage(final ByteBuffer buffer) throws IOException {
        if (!this.message.parseBuffer(buffer)) {
            return; // incomplete message
        }

        if (!fixSession.handleFixMessage(message)) {
            handleMarketUpdate(message);
        }

        fixSession.keepAlive();
    }

    protected abstract void handleMarketUpdate(FixMessage message);
}
