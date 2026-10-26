package group.gnometrading.gateways.orders;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewaySupervisor;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.agrona.concurrent.EpochClock;

public final class OutboundGateway extends GatewaySupervisor {

    private final OutboundSocketReader reader;

    protected OutboundGateway(Logger logger, OutboundSocketReader reader, GatewayConfig config, EpochClock clock) {
        // A session's order gateway connects once per venue, on the trading key, so it needs no spacing.
        super(logger, reader::connect, config, clock, reader.clock, ConnectLimiter.UNLIMITED);
        this.reader = reader;
        try {
            reader.configureSocket(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String roleName() {
        return "outbound-gateway";
    }

    @Override
    protected void doDisconnect() throws Exception {
        this.reader.disconnect();
    }

    @Override
    protected void doKeepAlive() throws Exception {
        this.reader.keepAlive();
    }

    @Override
    protected long recvTimestamp() {
        return this.reader.recvTimestamp;
    }
}
