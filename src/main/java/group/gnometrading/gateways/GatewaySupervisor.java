package group.gnometrading.gateways;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.Schedule;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;

public abstract class GatewaySupervisor implements GnomeAgent {

    private final Logger logger;
    private final GatewayConfig config;
    private final EpochNanoClock nanoClock;
    private final SocketConnectController connectController;
    private final Schedule reconnectSchedule;
    private final Schedule keepAliveSchedule;
    private final Schedule sanityCheckSchedule;

    protected GatewaySupervisor(
            Logger logger, Connectable connectable, GatewayConfig config, EpochClock clock, EpochNanoClock nanoClock) {
        this.logger = logger;
        this.config = config;
        this.nanoClock = nanoClock;
        this.reconnectSchedule = new Schedule(clock, config.reconnectInterval().toMillis(), this::reconnect);
        this.keepAliveSchedule = new Schedule(clock, config.keepAliveInterval().toMillis(), this::keepAlive);
        this.sanityCheckSchedule =
                new Schedule(clock, config.sanityCheckInterval().toMillis(), this::sanityCheck);
        this.connectController = new SocketConnectController(
                logger, connectable, config.connectTimeout(), config.maxReconnectAttempts(), config.initialBackoff());
    }

    protected abstract void doDisconnect() throws Exception;

    protected abstract void doKeepAlive() throws Exception;

    protected abstract long recvTimestamp();

    protected void doSanityCheck() {}

    @Override
    public final void onStart() throws Exception {
        this.connectController.connect();
        this.reconnectSchedule.start();
        this.keepAliveSchedule.start();
        this.sanityCheckSchedule.start();
    }

    @Override
    public final int doWork() throws Exception {
        this.reconnectSchedule.check();
        this.keepAliveSchedule.check();
        this.sanityCheckSchedule.check();

        final long recvTs = recvTimestamp();
        if (recvTs > 0) {
            final long nanosSinceLastRecv = this.nanoClock.nanoTime() - recvTs;
            if (nanosSinceLastRecv > this.config.maxSilentInterval().toNanos()) {
                this.logger.log(LogMessage.SOCKET_SILENCE_TIMED_OUT);
                this.reconnectSchedule.forceTrigger();
            }
        }
        return 0;
    }

    @Override
    public final void onClose() {
        try {
            doDisconnect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public final void forceReconnect() {
        this.reconnectSchedule.forceTrigger();
    }

    public final void forceKeepAlive() {
        this.keepAliveSchedule.forceTrigger();
    }

    private void reconnect() {
        this.logger.log(LogMessage.SOCKET_RECONNECTING);
        try {
            doDisconnect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.connectController.connect();
    }

    private void keepAlive() {
        try {
            doKeepAlive();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sanityCheck() {
        doSanityCheck();
    }
}
