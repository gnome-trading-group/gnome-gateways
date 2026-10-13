package group.gnometrading.gateways.outbound;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import java.io.IOException;

/**
 * Keeps an order writer's connections to its venue ready while no orders are going out, so the next order neither
 * pays for a reconnect nor is written into a connection that died while idle. Venues close idle connections (Kalshi
 * after about a minute), and a connection can also be dropped silently along the way (Cloudflare or a NAT, within a
 * few minutes), which nothing tells this end about; a connection idle longer than {@code maxIdleNanos} is replaced.
 * Use on the thread that owns the client.
 */
public final class IdleConnections {

    private final Logger logger;
    private final String venue;
    private final HTTPClient httpClient;
    private final String host;
    private final long maxIdleNanos;
    private boolean failing;

    public IdleConnections(
            final Logger logger,
            final String venue,
            final HTTPClient httpClient,
            final String host,
            final long maxIdleNanos) {
        this.logger = logger;
        this.venue = venue;
        this.httpClient = httpClient;
        this.host = host;
        this.maxIdleNanos = maxIdleNanos;
    }

    /**
     * Replaces connections that are closed, missing or idle too long. A failure is logged when it starts and when it
     * ends, not every time, and isn't thrown: the next request connects for itself.
     */
    public void keepWarm() {
        try {
            this.httpClient.keepWarm(HTTPProtocol.HTTPS, this.host, this.maxIdleNanos);
            if (this.failing) {
                this.failing = false;
                this.logger.logf(
                        LogMessage.SOCKET_CONNECTED, "%s: idle connections to %s are back", this.venue, this.host);
            }
        } catch (final IOException e) {
            if (!this.failing) {
                this.failing = true;
                this.logger.logf(
                        LogMessage.SOCKET_CONNECT_FAILED,
                        "%s: couldn't replace an idle connection to %s; the next order will connect for itself: %s",
                        this.venue,
                        this.host,
                        e.getMessage());
            }
        }
    }
}
