package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Beat after beat over a real connection, signing and the venue's answers included, allocating nothing. */
@Tag("allocation")
class PolymarketIntlHeartbeatsAllocationTest {

    @Test
    void aBeatAllocatesNothingOnTheKeepersThread() throws Exception {
        try (FakeVenueServer server = new FakeVenueServer();
                AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                        .withPort(server.port())
                        .withConnections(2)
                        .build()) {
            server.script(request -> Reply.ok("{\"heartbeat_id\":\"7f3c2a1e-0b4d-4c55-9e1a-2d6f8b9c0a11\"}"));
            final PolymarketIntlHeartbeats heartbeats = new PolymarketIntlHeartbeats(
                    "alloc",
                    new NullLogger(),
                    connections,
                    new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE),
                    new PolymarketIntlAuthHeaders(
                            "key", "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==", "pass", "0xabc"),
                    () -> true,
                    System::nanoTime,
                    SystemEpochClock.INSTANCE,
                    0,
                    TimeUnit.SECONDS.toNanos(2),
                    TimeUnit.SECONDS.toNanos(6));
            final Runnable beat = () -> {
                final long before = heartbeats.lastAcceptedNanos();
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (heartbeats.lastAcceptedNanos() == before) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("no beat accepted");
                    }
                    try {
                        heartbeats.doWork();
                    } catch (final Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            OrderPathTestKit.assertNoAllocation("heartbeat", 300, 1_000, beat);
            assertTrue(server.received() >= 1_300);
        }
    }
}
