package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayConfigTest {

    @Test
    void defaultsKeepTheKeepAliveWellInsideTheSilenceWindow() {
        final GatewayConfig config = new GatewayConfig.Builder().build();
        assertTrue(config.keepAliveInterval().multipliedBy(2).compareTo(config.maxSilentInterval()) <= 0);
    }

    @Test
    void keepAliveAsLongAsTheSilenceWindowIsRejected() {
        final GatewayConfig.Builder builder = new GatewayConfig.Builder()
                .withKeepAliveInterval(Duration.ofSeconds(30))
                .withMaxSilentInterval(Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void keepAliveOfHalfTheSilenceWindowIsAllowed() {
        final GatewayConfig config = new GatewayConfig.Builder()
                .withKeepAliveInterval(Duration.ofSeconds(30))
                .withMaxSilentInterval(Duration.ofSeconds(60))
                .build();
        assertEquals(Duration.ofSeconds(30), config.keepAliveInterval());
    }

    @Test
    void readTimeoutLongerThanHalfTheSilenceWindowIsRejected() {
        final GatewayConfig.Builder builder = new GatewayConfig.Builder()
                .withMaxSilentInterval(Duration.ofSeconds(30))
                .withReadTimeout(Duration.ofSeconds(20));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void socketTimeoutsMustFitInsideTheConnectTimeout() {
        final GatewayConfig.Builder builder = new GatewayConfig.Builder()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withSocketConnectTimeout(Duration.ofSeconds(3))
                .withHandshakeTimeout(Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void tcpKeepAliveMustBePositive() {
        final GatewayConfig.Builder builder =
                new GatewayConfig.Builder().withTcpKeepAlive(Duration.ZERO, Duration.ofSeconds(5), 3);
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
