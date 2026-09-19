package group.gnometrading.gateways.inbound;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewaySupervisor;
import group.gnometrading.logging.Logger;
import org.agrona.concurrent.EpochClock;

public final class InboundGateway extends GatewaySupervisor {

    private final InboundSocketReader<?> socketReader;

    public InboundGateway(Logger logger, GatewayConfig config, InboundSocketReader<?> socketReader, EpochClock clock) {
        super(logger, socketReader::connect, config, clock, socketReader.clock);
        this.socketReader = socketReader;
    }

    @Override
    protected void doDisconnect() throws Exception {
        this.socketReader.disconnect();
    }

    @Override
    protected void doKeepAlive() throws Exception {
        this.socketReader.keepAlive();
    }

    @Override
    protected long recvTimestamp() {
        return this.socketReader.recvTimestamp;
    }
}
