package group.gnometrading.gateways.fix;

import group.gnometrading.networking.client.AbstractSocketMessageClient;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class FixSocketMessageClient extends AbstractSocketMessageClient {

    private final FixMessage inboundMessage;

    public FixSocketMessageClient(
            final InetSocketAddress address,
            final GnomeSocketFactory socketFactory,
            final FixConfig config,
            final int readBufferSize,
            final int writeBufferSize)
            throws IOException {
        super(address, socketFactory, readBufferSize, writeBufferSize);
        this.inboundMessage = new FixMessage(config);
    }

    @Override
    @SuppressWarnings("checkstyle:DesignForExtension")
    public boolean isCompleteMessage(final ByteBuffer byteBuffer) {
        return this.inboundMessage.parseBuffer(byteBuffer);
    }

    @SuppressWarnings("checkstyle:DesignForExtension")
    public FixMessage getMessage() {
        return this.inboundMessage;
    }
}
