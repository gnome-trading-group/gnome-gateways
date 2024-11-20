package group.gnometrading.gateways.fix;

import group.gnometrading.gateways.GenericSocketMarketInboundGateway;
import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;

import java.io.IOException;
import java.util.List;

public abstract class FIXMarketInboundGateway extends GenericSocketMarketInboundGateway<FIXMessage> implements FIXStatusListener {

    protected final FIXSession fixSession;
    protected final FIXMessage adminMessage;
    protected final FIXConfig fixConfig;

    public FIXMarketInboundGateway(
            final SocketClient socketClient,
            final Publication publication,
            final Decoder<FIXMessage> decoder,
            final FIXMessage messageHolder,
            final List<Listing> listings,
            final FIXConfig fixConfig
    ) {
        super(socketClient, publication, decoder, messageHolder, listings);

        this.adminMessage = new FIXMessage(fixConfig);
        this.fixSession = new FIXSession(fixConfig, socketClient, this);
        this.fixConfig = fixConfig;
    }

    @Override
    protected void handleGatewayMessage(final FIXMessage message) throws IOException {
        if (!fixSession.handleFIXMessage(message)) {
            handleMarketUpdate(message);
        }

        fixSession.keepAlive();
    }

    protected abstract void handleMarketUpdate(final FIXMessage message);
}
