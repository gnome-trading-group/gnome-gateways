package group.gnometrading.gateways.inbound;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.Schedule;
import org.agrona.concurrent.EpochClock;

public class MarketInboundGateway implements GnomeAgent {

    private final Logger logger;
    private final SocketReader<?> socketReader;
    private final MarketInboundGatewayConfig config;
    private final Schedule reconnectSchedule;
    private final Schedule keepAliveSchedule;
    private final Schedule sanityCheckSchedule;
    private final SocketConnectController connectController;

    public MarketInboundGateway(
            Logger logger,
            MarketInboundGatewayConfig config,
            SocketReader<?> socketReader,
            EpochClock clock
    ) {
        this.logger = logger;
        this.socketReader = socketReader;
        this.config = config;

        this.reconnectSchedule = new Schedule(clock, config.reconnectInterval().toMillis(), this::reconnect);
        this.keepAliveSchedule = new Schedule(clock, config.keepAliveInterval().toMillis(), this::keepAlive);
        this.sanityCheckSchedule = new Schedule(clock, config.sanityCheckInterval().toMillis(), this::sanityCheck);
        this.connectController = new SocketConnectController(
                this.logger,
                this.socketReader,
                this.config.connectTimeout(),
                this.config.maxReconnectAttempts(),
                this.config.initialBackoff()
        );
    }

    private void keepAlive() {
        try {
            this.socketReader.keepAlive();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sanityCheck() {
        // TODO
    }

    private void reconnect() {
        this.logger.log(LogMessage.SOCKET_RECONNECTING);
        try {
            this.socketReader.disconnect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.connectController.connect();
    }

    @Override
    public void onStart() throws Exception {
        this.connectController.connect();

        this.reconnectSchedule.start();
        this.keepAliveSchedule.start();
        this.sanityCheckSchedule.start();
    }

    @Override
    public int doWork() throws Exception {
        this.reconnectSchedule.check();
        this.keepAliveSchedule.check();
        this.sanityCheckSchedule.check();

        long nanosSinceLastRecv = this.socketReader.clock.nanoTime() - this.socketReader.recvTimestamp;
        if (this.socketReader.recvTimestamp > 0 && nanosSinceLastRecv > this.config.maxSilentInterval().toNanos()) {
            this.reconnectSchedule.forceTrigger();
        }

        return 0;
    }

    /**
     * Force a reconnect. This will be called from external threads.
     */
    public void forceReconnect() {
        this.reconnectSchedule.forceTrigger();
    }

    /**
     * Force a keep alive. This will be called from external threads.
     */
    public void forceKeepAlive() {
        this.keepAliveSchedule.forceTrigger();
    }

    @Override
    public void onClose() {
        try {
            this.socketReader.disconnect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
