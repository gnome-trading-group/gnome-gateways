package group.gnometrading.gateways.outbound;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewaySupervisor;
import group.gnometrading.logging.Logger;
import org.agrona.concurrent.EpochClock;

public final class OutboundGateway extends GatewaySupervisor {

    private final OutboundSocketReader reader;

    protected OutboundGateway(Logger logger, OutboundSocketReader reader, GatewayConfig config, EpochClock clock) {
        super(logger, reader::connect, config, clock, reader.clock);
        this.reader = reader;
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
