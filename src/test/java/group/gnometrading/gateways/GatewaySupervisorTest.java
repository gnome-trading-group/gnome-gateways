package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewaySupervisorTest {

    private ControllableClock clock;
    private ControllableNanoClock nanoClock;
    private Logger logger;
    private CallTrackingConnectable connectable;
    private GatewayConfig config;
    private TestGatewaySupervisor supervisor;

    @BeforeEach
    void setUp() throws IOException {
        clock = new ControllableClock();
        nanoClock = new ControllableNanoClock();
        logger = mock(Logger.class);
        connectable = new CallTrackingConnectable();
        config = new GatewayConfig.Builder()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withInitialBackoff(Duration.ofMillis(10))
                .withMaxReconnectAttempts(0)
                .withReconnectInterval(Duration.ofMillis(1000))
                .withKeepAliveInterval(Duration.ofMillis(500))
                .withSanityCheckInterval(Duration.ofMillis(2000))
                .withMaxSilentInterval(Duration.ofMillis(100))
                .build();
        supervisor = new TestGatewaySupervisor(logger, connectable, config, clock, nanoClock);
    }

    // ========== onStart ==========

    @Test
    void onStart_CallsConnect() throws Exception {
        supervisor.onStart();
        assertEquals(1, connectable.connectCallCount);
    }

    @Test
    void onStart_StartsSchedules_DoWorkAtZeroDoesNotFireThem() throws Exception {
        supervisor.onStart();
        supervisor.doWork();

        assertEquals(0, supervisor.disconnectCallCount);
        assertEquals(0, supervisor.keepAliveCallCount);
        assertEquals(0, supervisor.sanityCheckCallCount);
    }

    // ========== doWork — schedule firing ==========

    @Test
    void doWork_PastReconnectInterval_FiresReconnect() throws Exception {
        supervisor.onStart();

        clock.advance(1001);
        supervisor.doWork();

        assertEquals(1, supervisor.disconnectCallCount);
        assertEquals(2, connectable.connectCallCount); // 1 from onStart + 1 from reconnect
    }

    @Test
    void doWork_PastKeepAliveInterval_FiresKeepAlive() throws Exception {
        supervisor.onStart();

        clock.advance(501);
        supervisor.doWork();

        assertEquals(1, supervisor.keepAliveCallCount);
    }

    @Test
    void doWork_PastSanityCheckInterval_FiresSanityCheck() throws Exception {
        supervisor.onStart();

        clock.advance(2001);
        supervisor.doWork();

        assertEquals(1, supervisor.sanityCheckCallCount);
    }

    @Test
    void doWork_ScheduleDoesNotFireBeforeInterval() throws Exception {
        supervisor.onStart();

        clock.advance(999);
        supervisor.doWork();

        // keepAlive interval is 500ms, so it fires at 999ms
        assertEquals(1, supervisor.keepAliveCallCount);
        // reconnect interval is 1000ms, so it has NOT fired yet
        assertEquals(0, supervisor.disconnectCallCount);
    }

    // ========== doWork — silence detection ==========

    @Test
    void doWork_RecvTimestampZero_NoSilenceDetection() throws Exception {
        supervisor.onStart();
        supervisor.testRecvTimestamp = 0; // no message ever received
        nanoClock.advance(Duration.ofSeconds(10).toNanos());

        supervisor.doWork();
        supervisor.doWork();

        assertEquals(0, supervisor.disconnectCallCount); // no reconnect from silence
    }

    @Test
    void doWork_WithinSilentInterval_NoForcedReconnect() throws Exception {
        supervisor.onStart();
        nanoClock.advance(1); // ensure recvTimestamp > 0 so silence detection runs
        supervisor.testRecvTimestamp = nanoClock.nanoTime();
        nanoClock.advance(config.maxSilentInterval().toNanos() - 1);

        supervisor.doWork();
        supervisor.doWork();

        verify(logger, never()).log(LogMessage.SOCKET_SILENCE_TIMED_OUT);
        assertEquals(0, supervisor.disconnectCallCount);
    }

    @Test
    void doWork_ExceedsSilentInterval_ForcesReconnect() throws Exception {
        supervisor.onStart();
        nanoClock.advance(1); // ensure recvTimestamp > 0 so silence detection runs
        supervisor.testRecvTimestamp = nanoClock.nanoTime();
        nanoClock.advance(config.maxSilentInterval().toNanos() + 1);

        // First doWork: silence detected → forceTrigger
        supervisor.doWork();
        verify(logger).log(LogMessage.SOCKET_SILENCE_TIMED_OUT);

        // Second doWork: reconnect schedule fires
        supervisor.doWork();
        assertEquals(1, supervisor.disconnectCallCount);
    }

    // ========== forceReconnect / forceKeepAlive ==========

    @Test
    void forceReconnect_FiresOnNextDoWork() throws Exception {
        supervisor.onStart();
        supervisor.forceReconnect();
        supervisor.doWork();

        assertEquals(1, supervisor.disconnectCallCount);
    }

    @Test
    void forceKeepAlive_FiresOnNextDoWork() throws Exception {
        supervisor.onStart();
        supervisor.forceKeepAlive();
        supervisor.doWork();

        assertEquals(1, supervisor.keepAliveCallCount);
    }

    // ========== onClose ==========

    @Test
    void onClose_CallsDoDisconnect() throws Exception {
        supervisor.onStart();
        supervisor.onClose();

        assertEquals(1, supervisor.disconnectCallCount);
    }

    @Test
    void onClose_DisconnectThrows_WrapsInRuntimeException() throws Exception {
        supervisor.onStart();
        supervisor.disconnectException = new IOException("test error");

        assertThrows(RuntimeException.class, () -> supervisor.onClose());
    }

    // ========== reconnect flow ==========

    @Test
    void reconnect_CallsDisconnectBeforeConnect() throws Exception {
        supervisor.onStart();
        supervisor.forceReconnect();
        supervisor.doWork();

        assertTrue(
                connectable.disconnectCalledBeforeConnect, "doDisconnect must be called before connect on reconnect");
    }

    @Test
    void reconnect_LogsReconnecting() throws Exception {
        supervisor.onStart();
        supervisor.forceReconnect();
        supervisor.doWork();

        verify(logger).log(LogMessage.SOCKET_RECONNECTING);
    }

    // ========== Test helpers ==========

    static class ControllableClock implements EpochClock {
        long currentTimeMs = 0;

        @Override
        public long time() {
            return currentTimeMs;
        }

        void advance(final long millis) {
            currentTimeMs += millis;
        }
    }

    static class ControllableNanoClock implements EpochNanoClock {
        long currentTimeNs = 0;

        @Override
        public long nanoTime() {
            return currentTimeNs;
        }

        void advance(final long nanos) {
            currentTimeNs += nanos;
        }
    }

    static class CallTrackingConnectable implements Connectable {
        int connectCallCount = 0;
        boolean disconnectCalledBeforeConnect = false;
        private boolean lastDisconnectCalled = false;

        void onDisconnectCalled() {
            lastDisconnectCalled = true;
        }

        @Override
        public void connect() throws IOException {
            if (lastDisconnectCalled) {
                disconnectCalledBeforeConnect = true;
            }
            connectCallCount++;
        }
    }

    static class TestGatewaySupervisor extends GatewaySupervisor {
        int disconnectCallCount = 0;
        int keepAliveCallCount = 0;
        int sanityCheckCallCount = 0;
        volatile long testRecvTimestamp = 0;
        Exception disconnectException = null;
        private final CallTrackingConnectable connectable;

        TestGatewaySupervisor(
                Logger logger,
                CallTrackingConnectable connectable,
                GatewayConfig config,
                EpochClock clock,
                EpochNanoClock nanoClock) {
            super(logger, connectable, config, clock, nanoClock);
            this.connectable = connectable;
        }

        @Override
        protected void doDisconnect() throws Exception {
            disconnectCallCount++;
            connectable.onDisconnectCalled();
            if (disconnectException != null) {
                throw disconnectException;
            }
        }

        @Override
        protected void doKeepAlive() {
            keepAliveCallCount++;
        }

        @Override
        protected long recvTimestamp() {
            return testRecvTimestamp;
        }

        @Override
        protected void doSanityCheck() {
            sanityCheckCallCount++;
        }
    }
}
