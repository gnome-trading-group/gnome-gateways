package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.account.lane.LaneHarness;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Kalshi's requests as the lane sends them, to a local server: paths, signatures, and how its answers are read. */
class KalshiLaneVenueTest {

    private static final long SLOT = 4;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final String ORDERS = "/trade-api/v2/portfolio/events/orders";
    private static final String ACCEPTED = "{\"order\":{\"order_id\":\"0a1b2c\",\"client_order_id\":\"smoke-1\"}}";

    private KeyPair keys;
    private LaneHarness<KalshiMarket> harness;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
        final KalshiLaneVenue venue =
                new KalshiLaneVenue(new NullLogger(), new KalshiAuthSigner("key-id", this.keys.getPrivate()), "maker");
        final KalshiMarket market = KalshiMarket.of(new Listing(
                1,
                new Exchange(EXCHANGE_ID, "KALSHI", "Kalshi", "global"),
                new Security(
                        (int) SECURITY_ID, "KXT", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "KXT-1:yes",
                "KXT-1"));
        market.exchangeIndex(1);
        venue.addMarket(EXCHANGE_ID, SECURITY_ID, market);
        venue.addSession(SLOT, "smoke");
        this.harness = new LaneHarness<>(
                venue,
                TimeUnit.MILLISECONDS.toNanos(100),
                TimeUnit.MILLISECONDS.toNanos(500),
                SLOT,
                EXCHANGE_ID,
                SECURITY_ID);
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    @Test
    void aSubmitIsSignedAndCarriesTheSessionsClientOrderId() throws Exception {
        this.harness.server.script(request -> new Reply(201, ACCEPTED, 0));
        this.harness.submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));

        final Request submit = this.harness.requests("POST").get(0);
        assertEquals(ORDERS, submit.path());
        assertTrue(submit.body().contains("\"client_order_id\":\"smoke-1\""), submit.body());
        assertSigned(submit, "POST" + ORDERS);
        assertTrue(this.harness.notices.isEmpty());
    }

    @Test
    void aCancelIsSignedWithoutItsRoutingQuery() throws Exception {
        this.harness.server.script(
                request -> request.method().equals("POST") ? new Reply(201, ACCEPTED, 0) : Reply.ok("{}"));
        this.harness.submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 1));

        final Request cancel = this.harness.requests("DELETE").get(0);
        assertTrue(cancel.path().startsWith(ORDERS + "/0a1b2c?"), cancel.path());
        assertSigned(cancel, "DELETE" + ORDERS + "/0a1b2c");
    }

    @Test
    void aDuplicateResendIsLookedUpInTheMarketsRecentOrders() throws Exception {
        this.harness.server.script(request -> {
            if (request.method().equals("GET")) {
                return Reply.ok(
                        "{\"orders\":[{\"order_id\":\"0a1b2c\",\"client_order_id\":\"smoke-1\"}],\"cursor\":\"\"}");
            }
            return this.harness.requests("POST").size() == 1 ? Reply.status(502) : Reply.status(409);
        });
        this.harness.submit(1);
        this.harness.runUntil(() -> this.harness.answered("GET", 1));

        final Request lookup = this.harness.requests("GET").get(0);
        assertTrue(lookup.path().startsWith("/trade-api/v2/portfolio/orders?ticker=KXT-1&min_ts="), lookup.path());
        assertSigned(lookup, "GET/trade-api/v2/portfolio/orders");
        assertTrue(this.harness.notices.isEmpty(), "found, so accepted");

        this.harness.server.script(request -> Reply.ok("{}"));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 1));
        assertTrue(this.harness.requests("DELETE").get(0).path().startsWith(ORDERS + "/0a1b2c?"));
    }

    @Test
    void aShardThatIsntTradingClosesTheMarket() {
        this.harness.server.script(request -> new Reply(503, "{\"error\":\"exchange closed\"}", 0));
        this.harness.submit(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.harness.notices.get(0).execType);
        assertEquals(RejectReason.MARKET_CLOSED, this.harness.notices.get(0).rejectReason);
    }

    @Test
    void anAcceptedAmendReportsTheFillsTheVenueCounts() {
        this.harness.server.script(request -> request.path().endsWith("/amend")
                ? Reply.ok("{\"order\":{\"remaining_count\":\"2.00\"}}")
                : new Reply(201, ACCEPTED, 0));
        this.harness.submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.amend(1, 400_000_000L, 4_000_000L);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());

        assertEquals(
                ORDERS + "/0a1b2c/amend", this.harness.requests("POST").get(1).path());
        assertTrue(this.harness.notices.get(0).amendAccepted);
        assertEquals(2_000_000L, this.harness.notices.get(0).leavesQty);
        assertEquals(2_000_000L, this.harness.notices.get(0).cumulativeFilledQty, "4 asked for, 2 rest: 2 filled");
    }

    @Test
    void anOrderFromAnUnregisteredSessionIsRefusedUnsent() throws Exception {
        final KalshiLaneVenue venue =
                new KalshiLaneVenue(new NullLogger(), new KalshiAuthSigner("key-id", this.keys.getPrivate()), "maker");
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                KalshiMarket.of(new Listing(
                        1,
                        new Exchange(EXCHANGE_ID, "KALSHI", "Kalshi", "global"),
                        new Security(
                                (int) SECURITY_ID,
                                "KXT",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                false,
                                0L,
                                0L,
                                true,
                                0),
                        "KXT-1:yes",
                        "KXT-1")));
        try (LaneHarness<KalshiMarket> unregistered =
                new LaneHarness<>(venue, 0, TimeUnit.MILLISECONDS.toNanos(500), SLOT, EXCHANGE_ID, SECURITY_ID)) {
            unregistered.submit(1);
            unregistered.runUntil(() -> !unregistered.notices.isEmpty());
            assertEquals(RejectReason.SESSION_NOT_REGISTERED, unregistered.notices.get(0).rejectReason);
            assertTrue(unregistered.server.requests().isEmpty());
        }
    }

    @Test
    void aMarketOrderIsRefusedUnsent() {
        this.harness.submit(1, command -> command.orderType = OrderType.MARKET);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(RejectReason.GATEWAY_REJECTED, this.harness.notices.get(0).rejectReason);
        assertTrue(this.harness.server.requests().isEmpty());
    }

    private void assertSigned(final Request request, final String methodAndPath) throws Exception {
        assertEquals("key-id", request.header(KalshiOrderCodec.HEADER_KEY));
        final String timestamp = request.header(KalshiOrderCodec.HEADER_TIMESTAMP);
        final Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(this.keys.getPublic());
        verifier.update((timestamp + methodAndPath).getBytes(StandardCharsets.US_ASCII));
        assertTrue(
                verifier.verify(Base64.getDecoder().decode(request.header(KalshiOrderCodec.HEADER_SIGNATURE))),
                "signed over " + methodAndPath);
    }
}
