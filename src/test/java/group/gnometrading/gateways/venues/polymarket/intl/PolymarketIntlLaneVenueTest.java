package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.account.lane.LaneHarness;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Polymarket International's requests as the lane sends them, to a local server, and how its answers are read. */
class PolymarketIntlLaneVenueTest {

    // Hardhat account #0: a well-known test key, never funded on Polygon.
    private static final byte[] PRIVATE_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String TOKEN = "71321045679252212594626385532706912750332728571942532289631379312455583992563";
    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==";
    private static final long SLOT = 4;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final long SIZE = 10 * Statics.SIZE_SCALING_FACTOR;
    private static final Pattern ORDER_ID = Pattern.compile("\"orderID\":\"(0x[0-9a-f]+)\"");
    private static final String ACCEPTED = "{\"success\":true,\"errorMsg\":\"\",\"status\":\"live\"}";

    private LaneHarness<PolymarketIntlMarket> harness;

    @BeforeEach
    void setUp() throws Exception {
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                new NullLogger(),
                new PolymarketIntlOrderSigner(PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, 1L),
                new PolymarketIntlAuthHeaders("api-key", SECRET, "pass", EOA));
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                PolymarketIntlMarket.of(
                        new Listing(
                                1,
                                new Exchange(EXCHANGE_ID, "POLYMARKET", "Polymarket", "global"),
                                new Security(
                                        (int) SECURITY_ID,
                                        "TEST",
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
                                "0xcondition:" + TOKEN,
                                "TEST-YES"),
                        new PolymarketIntlMarketInfo(Statics.PRICE_SCALING_FACTOR / 100, SIZE / 2, false, false, 0)));
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
    void aSubmitCarriesTheSignedOrderUnderL2Auth() throws Exception {
        this.harness.server.script(request -> Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));

        final Request submit = this.harness.requests("POST").get(0);
        assertEquals("/order", submit.path());
        assertTrue(submit.body().contains("\"signature\":\"0x"), submit.body());
        assertTrue(submit.body().contains("\"owner\":\"api-key\""), submit.body());
        assertSigned(submit);
        assertTrue(this.harness.notices.isEmpty());
    }

    @Test
    void aResendCarriesTheSameOrderAndItsDuplicateRefusalIsLookedUp() throws Exception {
        this.harness.server.script(request -> {
            if (request.method().equals("GET")) {
                return Reply.ok("{\"id\":\"x\"}");
            }
            return this.harness.requests("POST").size() == 1
                    ? Reply.status(502)
                    : new Reply(400, "{\"error\":\"order 0xab is invalid. Duplicated.\"}", 0);
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("GET", 1));

        final Request first = this.harness.requests("POST").get(0);
        final Request resend = this.harness.requests("POST").get(1);
        assertEquals(first.body(), resend.body(), "the same signed order, so the venue knows it");
        final String hash = this.harness.handed.get(0).correlationIdLength > 0
                ? new String(
                        this.harness.handed.get(0).correlationIdBytes,
                        0,
                        this.harness.handed.get(0).correlationIdLength,
                        StandardCharsets.US_ASCII)
                : "";
        assertEquals("/data/order/" + hash, this.harness.requests("GET").get(0).path());
        assertSigned(this.harness.requests("GET").get(0));
        assertTrue(this.harness.notices.isEmpty(), "found, so accepted");
    }

    @Test
    void aCancelNamesTheOrdersHash() throws Exception {
        this.harness.server.script(request -> {
            if (request.method().equals("DELETE")) {
                final Matcher id = ORDER_ID.matcher(request.body());
                return Reply.ok("{\"canceled\":[\"" + (id.find() ? id.group(1) : "") + "\"],\"not_canceled\":{}}");
            }
            return Reply.ok(ACCEPTED);
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 1));

        final Request cancel = this.harness.requests("DELETE").get(0);
        assertEquals("/order", cancel.path());
        assertTrue(cancel.body().contains("\"orderID\":\"0x"), cancel.body());
        assertSigned(cancel);
        assertTrue(this.harness.notices.isEmpty(), "accepted: the order ends on the venue's events");
    }

    @Test
    void aCancelTheVenueDoesntListAsCanceledIsRefused() {
        this.harness.server.script(request -> request.method().equals("DELETE")
                ? Reply.ok("{\"canceled\":[],\"not_canceled\":{\"0xab\":\"matched\"}}")
                : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.CANCEL_REJECT, this.harness.notices.get(0).execType);
    }

    @Test
    void anAmendIsRefusedUnsent() {
        this.harness.server.script(request -> Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.amend(1, 450_000_000L, SIZE);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.CANCEL_REJECT, this.harness.notices.get(0).execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, this.harness.notices.get(0).rejectReason);
        assertEquals(1, this.harness.server.requests().size());
    }

    @Test
    void aRefusalIsReadFromTheAnswer() {
        this.harness.server.script(
                request -> Reply.ok("{\"success\":false,\"errorMsg\":\"not enough balance / allowance\"}"));
        submit(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.harness.notices.get(0).execType);
        assertFalse(this.harness.notices.get(0).rejectReason == null);
    }

    private void submit(final long counter) {
        this.harness.submit(counter, command -> command.size = SIZE);
    }

    private static void assertSigned(final Request request) throws Exception {
        assertEquals("api-key", request.header(PolymarketIntlAuthHeaders.API_KEY_HEADER));
        assertEquals("pass", request.header(PolymarketIntlAuthHeaders.PASSPHRASE_HEADER));
        assertEquals(EOA, request.header(PolymarketIntlAuthHeaders.ADDRESS_HEADER));
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(SECRET), "HmacSHA256"));
        final String signed = request.header(PolymarketIntlAuthHeaders.TIMESTAMP_HEADER)
                + request.method()
                + request.path()
                + request.body();
        assertEquals(
                Base64.getUrlEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))),
                request.header(PolymarketIntlAuthHeaders.SIGNATURE_HEADER),
                "signed over " + signed);
    }
}
