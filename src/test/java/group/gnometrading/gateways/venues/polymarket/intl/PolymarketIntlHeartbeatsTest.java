package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The heartbeat keeper against a local venue over real connections, at shortened timings. */
class PolymarketIntlHeartbeatsTest {

    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==";
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long TIMEOUT = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long AT_RISK = TimeUnit.MILLISECONDS.toNanos(500);

    private FakeVenueServer server;
    private AsyncHTTPConnections connections;
    private PolymarketIntlHeartbeats heartbeats;
    private volatile boolean laneAlive = true;
    private final AtomicInteger issued = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        this.server = new FakeVenueServer();
        // By default the venue accepts each beat and names the next id.
        this.server.script(request -> accept());
        this.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                .withPort(this.server.port())
                .withConnections(2)
                .build();
        this.heartbeats = new PolymarketIntlHeartbeats(
                "test",
                new NullLogger(),
                this.connections,
                new HTTPConnector(List.of(this.connections), HTTPConnector.Listener.NONE),
                new PolymarketIntlAuthHeaders("key", SECRET, "pass", "0xabc"),
                () -> this.laneAlive,
                System::nanoTime,
                SystemEpochClock.INSTANCE,
                INTERVAL,
                TIMEOUT,
                AT_RISK);
    }

    @AfterEach
    void tearDown() {
        this.connections.close();
        this.server.close();
    }

    @Test
    void eachBeatCarriesTheIdTheLastOneWasGiven() throws Exception {
        runUntil(() -> this.server.requests().size() >= 3);
        final List<Request> beats = this.server.requests();
        assertEquals("/v1/heartbeats", beats.get(0).path());
        assertEquals("{\"heartbeat_id\":\"\"}", beats.get(0).body(), "the first starts the chain");
        assertEquals("{\"heartbeat_id\":\"h1\"}", beats.get(1).body());
        assertEquals("{\"heartbeat_id\":\"h2\"}", beats.get(2).body());
        assertTrue(beats.get(2).receivedNanos() - beats.get(1).receivedNanos() >= INTERVAL - ms(10), "paced");
        assertTrue(this.heartbeats.armed());
        assertTrue(this.heartbeats.accepted() >= 2);
        assertSigned(beats.get(1));
    }

    @Test
    void aStalledBeatIsSentAgainAtOnceOnAnotherConnection() {
        // Only the second beat stalls, as one request lost on the network would.
        this.server.script(request -> this.server.requests().size() == 2
                ? Reply.ok("{\"heartbeat_id\":\"late\"}").after(2_000)
                : accept());
        runUntil(() -> this.server.requests().stream()
                                .filter(r -> r.body().contains("\"h1\""))
                                .count()
                        == 2
                && this.issued.get() == 2);
        final List<Request> beats = this.server.requests();
        final Request stalled = beats.get(1);
        final Request retry = beats.get(2);
        assertEquals(stalled.body(), retry.body(), "the same id: the stalled one may yet have been accepted");
        final long gap = retry.receivedNanos() - stalled.receivedNanos();
        assertTrue(gap >= TIMEOUT - ms(10) && gap < TIMEOUT + ms(60), "retried as soon as it timed out: " + gap);
        assertFalse(this.heartbeats.atRisk());
    }

    @Test
    void aMismatchedIdIsAnsweredAtOnceWithTheVenuesOwn() {
        this.server.script(request -> request.body().contains("\"h1\"")
                ? new Reply(400, "{\"error_msg\":\"Invalid Heartbeat ID\",\"heartbeat_id\":\"expected\"}", 0)
                : accept());
        runUntil(() -> this.server.requests().stream().anyMatch(r -> r.body().contains("\"expected\"")));
        final List<Request> beats = this.server.requests();
        final Request refused = beats.get(1);
        final Request answered = beats.get(2);
        assertEquals("{\"heartbeat_id\":\"expected\"}", answered.body());
        assertEquals(1, this.heartbeats.mismatches());
        assertTrue(answered.receivedNanos() - refused.receivedNanos() < INTERVAL, "not left to the next interval");
    }

    @Test
    void noBeatGoesWhileTheLaneIsDownAndTheKeyIsThenAtRisk() {
        runUntil(this.heartbeats::armed);
        this.laneAlive = false;
        final int sent = this.server.requests().size();
        runUntil(this.heartbeats::atRisk);
        assertEquals(sent, this.server.requests().size(), "nothing keeps orders alive behind a dead lane");
        this.laneAlive = true;
        runUntil(() -> !this.heartbeats.atRisk());
    }

    @Test
    void aRefusalThatNamesNoIdIsRetriedAfterAShortPause() {
        this.server.script(request -> this.server.requests().size() == 2 ? Reply.status(500) : accept());
        runUntil(() -> this.server.requests().size() >= 3);
        final List<Request> beats = this.server.requests();
        final long gap = beats.get(2).receivedNanos() - beats.get(1).receivedNanos();
        assertTrue(gap >= ms(240) && gap < ms(400), "paused, then retried: " + gap);
        assertEquals(beats.get(1).body(), beats.get(2).body());
    }

    private Reply accept() {
        return Reply.ok("{\"heartbeat_id\":\"h" + this.issued.incrementAndGet() + "\"}");
    }

    private void runUntil(final BooleanSupplier done) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out; beats " + this.server.requests());
            }
            try {
                this.heartbeats.doWork();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static long ms(final long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static void assertSigned(final Request request) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(SECRET), "HmacSHA256"));
        final String signed =
                request.header(PolymarketIntlAuthHeaders.TIMESTAMP_HEADER) + "POST" + request.path() + request.body();
        assertEquals(
                Base64.getUrlEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))),
                request.header(PolymarketIntlAuthHeaders.SIGNATURE_HEADER));
        assertEquals("key", request.header(PolymarketIntlAuthHeaders.API_KEY_HEADER));
    }
}
