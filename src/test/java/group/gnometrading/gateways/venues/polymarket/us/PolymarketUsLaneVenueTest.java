package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.account.lane.LaneHarness;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Polymarket US's requests as the lane sends them, to a local server, and how its answers are read. */
class PolymarketUsLaneVenueTest {

    private static final String SLUG = "test-market";
    private static final long SLOT = 100_041;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final String SEED = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Pattern GOOD_TILL = Pattern.compile("\"goodTillTime\":\"([^\"]+)\"");

    private LaneHarness<PolymarketUsMarket> harness;

    @BeforeEach
    void setUp() throws Exception {
        final PolymarketUsLaneVenue venue = new PolymarketUsLaneVenue(
                new NullLogger(),
                new PolymarketUsAuthSigner("access-key", PolymarketUsAuthSigner.parseSecretKey(SEED)));
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                new PolymarketUsMarket(
                        new Listing(
                                1,
                                new Exchange(EXCHANGE_ID, "POLYMARKET_US", "Polymarket US", "us"),
                                new Security(
                                        (int) SECURITY_ID,
                                        SLUG,
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
                                SLUG + ":long",
                                SLUG),
                        new PolymarketUsMarketInfo(
                                Statics.PRICE_SCALING_FACTOR / 100, Statics.SIZE_SCALING_FACTOR / 100)));
        this.harness = new LaneHarness<>(
                venue,
                TimeUnit.MILLISECONDS.toNanos(100),
                TimeUnit.MILLISECONDS.toNanos(300),
                SLOT,
                EXCHANGE_ID,
                SECURITY_ID);
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    @Test
    void aRestingOrderGoesGoodTillItsStampAndTheReaderLearnsItsId() throws Exception {
        this.harness.server.script(request -> Reply.ok("{\"id\":\"ord-1\"}"));
        this.harness.submit(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());

        final Request submit = this.harness.requests("POST").get(0);
        assertEquals("/v1/orders", submit.path());
        final long stamp = PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(SLOT), 1);
        assertTrue(submit.body().contains("TIME_IN_FORCE_GOOD_TILL_DATE"), submit.body());
        assertEquals(correlationId(), goodTill(submit));
        assertEquals(
                stamp, PolymarketUsStamps.parseMillis(new group.gnometrading.strings.ViewString(goodTill(submit))));
        assertSigned(submit, "/v1/orders");
        assertTrue(this.harness.notices.get(0).venueOrderIdNotice);
        assertEquals("ord-1", venueId(0));
    }

    @Test
    void aLostAnswerIsLookedUpByStampNeverResent() throws Exception {
        this.harness.server.script(request -> {
            if (request.method().equals("GET")) {
                final String stamp = goodTill(this.harness.requests("POST").get(0));
                return Reply.ok("{\"orders\":[{\"id\":\"other\",\"goodTillTime\":\"2031-01-01T00:00:00Z\"},"
                        + "{\"id\":\"ord-1\",\"goodTillTime\":\"" + stamp + "\"}]}");
            }
            return Reply.ok("{\"id\":\"ord-1\"}").after(600);
        });
        this.harness.submit(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty(), 3_000);

        assertEquals(1, this.harness.requests("POST").size(), "the venue doesn't deduplicate");
        final Request lookup = this.harness.requests("GET").get(0);
        assertEquals("/v1/orders/open?slugs=" + SLUG, lookup.path());
        assertSigned(lookup, "/v1/orders/open");
        assertEquals("ord-1", venueId(0), "found by its stamp");
    }

    @Test
    void anIocWithAnUnknownOutcomeBlocksItsTwinUntilItEnds() {
        this.harness.server.script(request -> Reply.ok("{\"id\":\"ioc\"}").after(600));
        this.harness.submit(1, command -> command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED);
        this.harness.runUntil(() -> this.harness.connections.inFlight() == 1);
        this.harness.runFor(400);
        assertTrue(
                this.harness.server.requests().stream()
                        .noneMatch(r -> r.method().equals("GET")),
                "not looked up");

        this.harness.submit(2, command -> command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.harness.notices.get(0).execType);
        assertEquals(2, this.harness.notices.get(0).clientOidCounter);

        this.harness.release(1);
        this.harness.server.script(request -> Reply.ok("{\"id\":\"ioc-3\"}"));
        this.harness.submit(3, command -> command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED);
        this.harness.runUntil(() -> this.harness.notices.size() == 2);
        assertTrue(this.harness.notices.get(1).venueOrderIdNotice, "accepted once the first had ended");
    }

    @Test
    void anIocTwinFromAnotherSessionIsRefusedWhileTheFirstIsInFlight() {
        this.harness.server.script(request -> Reply.ok("{\"id\":\"ioc\"}").after(150));
        this.harness.submit(1, command -> command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED);
        this.harness.runUntil(() -> this.harness.connections.inFlight() == 1);
        this.harness.submit(1, command -> {
            command.sessionSlot = SLOT + 1;
            command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED;
        });
        this.harness.submit(2, command -> {
            command.sessionSlot = SLOT + 1;
            command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED;
            command.price = 450_000_000L;
        });
        this.harness.runUntil(() -> this.harness.notices.stream().anyMatch(n -> n.execType == ExecType.REJECT));
        final OrderContext refused = this.harness.notices.stream()
                .filter(n -> n.execType == ExecType.REJECT)
                .findFirst()
                .orElseThrow();
        assertEquals(SLOT + 1, refused.sessionSlot);
        assertEquals(1, refused.clientOidCounter, "the twin, from the other session, while the first was on the wire");
        this.harness.runUntil(() -> this.harness.answered("POST", 2));
        assertEquals(2, this.harness.requests("POST").size(), "the first, and the IOC at another price");
    }

    @Test
    void aCounterPastTheStampsRangeEndsTheSession() {
        this.harness.submit(10_000_000L);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(RejectReason.SESSION_CAP, this.harness.notices.get(0).rejectReason);
        assertTrue(this.harness.server.requests().isEmpty());
    }

    @Test
    void aFillOrKillIsRefused() {
        this.harness.submit(1, command -> command.timeInForce = TimeInForce.FILL_OR_KILL);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(RejectReason.GATEWAY_REJECTED, this.harness.notices.get(0).rejectReason);
        assertTrue(this.harness.server.requests().isEmpty());
    }

    @Test
    void aRestingOrderIsAmendedAndCancelledByItsId() throws Exception {
        this.harness.server.script(
                request -> request.path().equals("/v1/orders") ? Reply.ok("{\"id\":\"ord-1\"}") : Reply.ok("{}"));
        this.harness.submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.amend(1, 450_000_000L, 2_000_000L);
        this.harness.runUntil(() -> this.harness.notices.size() == 2);
        final Request amend = this.harness.requests("POST").get(1);
        assertEquals("/v1/order/ord-1/modify", amend.path());
        assertEquals(goodTill(this.harness.requests("POST").get(0)), goodTill(amend), "the stamp is kept");
        assertSigned(amend, "/v1/order/ord-1/modify");
        assertTrue(this.harness.notices.get(1).amendAccepted);

        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 3));
        final Request cancel = this.harness.requests("POST").get(2);
        assertEquals("/v1/order/ord-1/cancel", cancel.path());
        assertTrue(cancel.body().contains(SLUG), cancel.body());
    }

    @Test
    void anIocCantBeAmended() {
        this.harness.server.script(request -> Reply.ok("{\"id\":\"ioc\"}"));
        this.harness.submit(1, command -> command.timeInForce = TimeInForce.IMMEDIATE_OR_CANCELED);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.amend(1, 450_000_000L, 2_000_000L);
        this.harness.runUntil(() -> this.harness.notices.size() == 2);
        assertEquals(ExecType.CANCEL_REJECT, this.harness.notices.get(1).execType);
        assertEquals(1, this.harness.server.requests().size());
    }

    @Test
    void createsQueuedTogetherGoInOneBatchAndEachLearnsItsId() throws Exception {
        this.harness.server.script(request -> Reply.ok("{\"createdOrderIds\":[\"ord-1\",\"ord-2\",\"ord-3\"]}"));
        this.harness.submit(1);
        this.harness.submit(2);
        this.harness.submit(3);
        this.harness.runUntil(() -> this.harness.notices.size() == 3);

        assertEquals(1, this.harness.requests("POST").size());
        final Request batch = this.harness.requests("POST").get(0);
        assertEquals("/v1/orders/batched", batch.path());
        assertSigned(batch, "/v1/orders/batched");
        assertTrue(batch.body().startsWith("{\"orders\":[{"), batch.body());
        assertTrue(batch.body().endsWith("}]}"), batch.body());
        assertEquals(3, GOOD_TILL.matcher(batch.body()).results().count(), "each entry a whole single create");
        assertEquals(List.of("ord-1", "ord-2", "ord-3"), List.of(venueId(0), venueId(1), venueId(2)));
    }

    @Test
    void aBatchRefusedForOneEntryRejectsOnlyThatOrder() throws Exception {
        this.harness.server.script(request -> this.harness.requests("POST").size() == 1
                ? new Reply(400, "{\"code\":3,\"message\":\"order[1]: Market not found: x\"}", 0)
                : Reply.ok("{\"createdOrderIds\":[\"ord-1\",\"ord-3\"]}"));
        this.harness.submit(1);
        this.harness.submit(2);
        this.harness.submit(3);
        this.harness.runUntil(() -> this.harness.notices.size() == 3 && this.harness.answered("POST", 2));

        final OrderContext rejected = this.harness.notices.stream()
                .filter(notice -> notice.rejectReason != null)
                .findFirst()
                .orElseThrow();
        assertEquals(2, rejected.clientOidCounter);
        assertEquals(
                2,
                GOOD_TILL
                        .matcher(this.harness.requests("POST").get(1).body())
                        .results()
                        .count());
    }

    @Test
    void cancelsQueuedTogetherGoInOneBatch() throws Exception {
        this.harness.server.script(request -> request.path().equals("/v1/orders/batched")
                ? Reply.ok("{\"createdOrderIds\":[\"ord-1\",\"ord-2\"]}")
                : Reply.ok("{\"canceledOrderIds\":[\"ord-1\",\"ord-2\"]}"));
        this.harness.submit(1);
        this.harness.submit(2);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.cancel(2);
        this.harness.runUntil(() -> this.harness.answered("POST", 2));

        final Request cancels = this.harness.requests("POST").get(1);
        assertEquals("/v1/orders/batched/cancel", cancels.path());
        assertSigned(cancels, "/v1/orders/batched/cancel");
        assertEquals(
                "{\"orders\":[{\"orderId\":\"ord-1\",\"marketSlug\":\"" + SLUG + "\"},"
                        + "{\"orderId\":\"ord-2\",\"marketSlug\":\"" + SLUG + "\"}]}",
                cancels.body());
    }

    @Test
    void theEntryARefusedBatchNamesIsRead() {
        assertEquals(1, failedEntry("{\"code\":3,\"message\":\"order[1]: Market not found: x\"}"));
        assertEquals(17, failedEntry("order[17]: bad"));
        assertEquals(-1, failedEntry("{\"message\":\"exceeds max batch size\"}"));
        assertEquals(-1, failedEntry("order[x]"));
    }

    private static int failedEntry(final String body) {
        return PolymarketUsOrderCodec.failedEntry(ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII)));
    }

    private String correlationId() {
        return new String(
                this.harness.handed.get(0).correlationIdBytes,
                0,
                this.harness.handed.get(0).correlationIdLength,
                StandardCharsets.US_ASCII);
    }

    private String venueId(final int notice) {
        return new String(
                this.harness.notices.get(notice).exchangeOrderIdBytes,
                0,
                this.harness.notices.get(notice).exchangeOrderIdLength,
                StandardCharsets.US_ASCII);
    }

    private static String goodTill(final Request request) {
        final Matcher matcher = GOOD_TILL.matcher(request.body());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void assertSigned(final Request request, final String signedPath) throws Exception {
        assertEquals("access-key", request.header(PolymarketUsAuthSigner.ACCESS_KEY_HEADER));
        final String timestamp = request.header(PolymarketUsAuthSigner.TIMESTAMP_HEADER);
        final PolymarketUsAuthSigner expected =
                new PolymarketUsAuthSigner("access-key", PolymarketUsAuthSigner.parseSecretKey(SEED));
        // Ed25519 is deterministic: the same key over the same message gives the same signature.
        expected.sign(Long.parseLong(timestamp), request.method(), signedPath);
        assertEquals(expected.signature(), request.header(PolymarketUsAuthSigner.SIGNATURE_HEADER));
    }
}
