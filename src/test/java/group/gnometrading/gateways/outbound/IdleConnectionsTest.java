package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdleConnectionsTest {

    private final HTTPClient httpClient = mock(HTTPClient.class);
    private final List<LogMessage> logged = new ArrayList<>();
    private final IdleConnections connections = new IdleConnections(
            new NullLogger() {
                @Override
                public void logf(final LogMessage msg, final String format, final Object... args) {
                    logged.add(msg);
                }
            },
            "Venue",
            httpClient,
            "api.venue.com",
            45_000_000_000L);

    @Test
    void replacesTheVenuesIdleConnectionsOverHttps() throws Exception {
        connections.keepWarm();

        verify(httpClient).keepWarm(HTTPProtocol.HTTPS, "api.venue.com", 45_000_000_000L);
        assertEquals(List.of(), logged);
    }

    @Test
    void failureIsLoggedOnceWhileItLastsAndNeverThrown() throws Exception {
        doThrow(new IOException("connect timed out"))
                .when(httpClient)
                .keepWarm(eq(HTTPProtocol.HTTPS), anyString(), anyLong());

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(connections::keepWarm);
        }

        assertEquals(List.of(LogMessage.SOCKET_CONNECT_FAILED), logged);
    }

    @Test
    void recoveryIsLoggedOnce() throws Exception {
        doThrow(new IOException("connect timed out"))
                .doNothing()
                .when(httpClient)
                .keepWarm(eq(HTTPProtocol.HTTPS), anyString(), anyLong());

        connections.keepWarm();
        connections.keepWarm();
        connections.keepWarm();

        assertEquals(List.of(LogMessage.SOCKET_CONNECT_FAILED, LogMessage.SOCKET_CONNECTED), logged);
    }
}
