package group.gnometrading.gateways.inbound;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.utils.Schedule;
import org.agrona.concurrent.EpochClock;

import java.util.concurrent.TimeUnit;

public class MarketInboundGateway implements GnomeAgent {

    private final SocketReader<?> socketReader;
    private final Schedule reconnectSchedule;
    private final Schedule keepAliveSchedule;
    private final Schedule sanityCheckSchedule;

    public MarketInboundGateway(
            MarketInboundGatewayConfig config,
            SocketReader<?> socketReader,
            EpochClock clock
    ) {
        this.socketReader = socketReader;

        this.reconnectSchedule = new Schedule(clock, TimeUnit.SECONDS.toMillis(config.reconnectIntervalSeconds()), this::reconnect);
        this.keepAliveSchedule = new Schedule(clock, TimeUnit.SECONDS.toMillis(config.keepAliveIntervalSeconds()), this::keepAlive);
        this.sanityCheckSchedule = new Schedule(clock, TimeUnit.SECONDS.toMillis(config.sanityCheckIntervalSeconds()), this::sanityCheck);
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
        try {
            this.socketReader.disconnect();
            this.socketReader.connect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onStart() throws Exception {
        this.socketReader.connect();

        this.reconnectSchedule.start();
        this.keepAliveSchedule.start();
        this.sanityCheckSchedule.start();
    }

    @Override
    public int doWork() throws Exception {
        this.reconnectSchedule.check();
        this.keepAliveSchedule.check();
        this.sanityCheckSchedule.check();

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
