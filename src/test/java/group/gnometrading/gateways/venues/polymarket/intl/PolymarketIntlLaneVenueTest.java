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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private static final String DELAYED = "{\"success\":true,\"errorMsg\":\"\",\"status\":\"delayed\"}";
    private static final String NOT_FOUND =
            "{\"canceled\":[],\"not_canceled\":{\"0xab\":\"order can't be found - already canceled or matched\"}}";

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

    @Test
    void aLiveAnswerTellsTheReaderTheOrderRests() {
        this.harness.server.script(request -> Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> !this.harness.resting.isEmpty());

        assertEquals(1, this.harness.resting.size());
        assertEquals(1L, this.harness.resting.get(0).clientOidCounter);
        assertTrue(this.harness.notices.isEmpty(), "the answer acknowledges nothing itself; the reader does");
    }

    @ParameterizedTest
    @ValueSource(strings = {"matched", "delayed", "unmatched"})
    void anAnswerThatDoesntSayRestingTellsTheReaderNothing(final String status) {
        this.harness.server.script(
                request -> Reply.ok("{\"success\":true,\"errorMsg\":\"\",\"status\":\"" + status + "\"}"));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.runUntil(() -> true);
        assertTrue(this.harness.resting.isEmpty(), status);
    }

    @Test
    void aRefusedSubmitTellsTheReaderNothingRests() {
        this.harness.server.script(request -> Reply.ok("{\"success\":false,\"errorMsg\":\"x\",\"status\":\"live\"}"));
        submit(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.harness.notices.get(0).execType);
        assertTrue(this.harness.resting.isEmpty());
    }

    @Test
    void aCancelGoesWhileTheSubmitIsUnanswered() {
        this.harness.server.script(request -> request.method().equals("DELETE")
                ? Reply.ok(canceled(request))
                : Reply.ok(ACCEPTED).after(400));
        submit(1);
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1);
        this.harness.cancel(1);
        this.harness.runUntil(() -> !this.harness.requests("DELETE").isEmpty());

        final long sinceSubmit = this.harness.requests("DELETE").get(0).receivedNanos()
                - this.harness.requests("POST").get(0).receivedNanos();
        assertTrue(sinceSubmit < TimeUnit.MILLISECONDS.toNanos(300), "the cancel didn't wait out the order's answer");
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        assertTrue(this.harness.notices.isEmpty(), "cancelled: the order ends on the venue's events");
    }

    @Test
    void everyCancelGoesTwiceAtOnce() {
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(canceled(request)) : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.runFor(50);

        assertEquals(2, this.harness.requests("DELETE").size());
        assertEquals(
                this.harness.requests("DELETE").get(0).body(),
                this.harness.requests("DELETE").get(1).body());
        assertTrue(this.harness.notices.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void theNumberOfCopiesCanBeSet(final int copies) {
        this.harness.lane.cancelCopies(copies);
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(canceled(request)) : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", copies));
        this.harness.runFor(50);

        assertEquals(copies, this.harness.requests("DELETE").size());
    }

    @Test
    void aCopyTheVenueDoesntFindIsIgnoredWhenTheOtherCancels() {
        final AtomicInteger deletes = new AtomicInteger();
        this.harness.server.script(request -> {
            if (!request.method().equals("DELETE")) {
                return Reply.ok(ACCEPTED);
            }
            return deletes.getAndIncrement() == 0
                    ? Reply.ok(canceled(request))
                    : Reply.ok(NOT_FOUND).after(30);
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.runFor(50);

        assertTrue(this.harness.notices.isEmpty(), "no refusal for the copy that lost");
    }

    @Test
    void anOrderBothCopiesDontFindHasEndedAndIsLeftToItsEvents() {
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(NOT_FOUND) : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.runFor(100);

        assertTrue(this.harness.notices.isEmpty(), "matched or cancelled already: the events say which");
        assertEquals(2, this.harness.requests("DELETE").size(), "not sent again");
    }

    @Test
    void everyCancelsFirstCopyGoesBeforeAnySecondCopy() {
        this.harness.server.script(request ->
                request.method().equals("DELETE") ? Reply.ok(canceled(request)).after(300) : Reply.ok(ACCEPTED));
        final int orders = 6;
        for (int counter = 1; counter <= orders; counter++) {
            submit(counter);
        }
        this.harness.runUntil(() -> this.harness.answered("POST", orders));
        for (int counter = 1; counter <= orders; counter++) {
            this.harness.cancel(counter);
        }
        this.harness.runUntil(() -> this.harness.requests("DELETE").size() == LaneHarness.CONNECTIONS);

        final long named = this.harness.requests("DELETE").stream()
                .map(Request::body)
                .distinct()
                .count();
        assertEquals(orders, named, "a cancel's second copy took a connection another cancel's first copy needed");
        this.harness.runUntil(() -> this.harness.answered("DELETE", LaneHarness.CONNECTIONS));
    }

    @Test
    void aDelayedOrdersCancelThatFindsNothingIsTriedAgain() {
        final AtomicInteger deletes = new AtomicInteger();
        this.harness.server.script(request -> {
            if (!request.method().equals("DELETE")) {
                return Reply.ok(DELAYED);
            }
            // The order sits out its matching delay through the first round, then rests.
            return deletes.getAndIncrement() < 2 ? Reply.ok(NOT_FOUND) : Reply.ok(canceled(request));
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.requests("DELETE").size() > 2, 2_000);
        this.harness.runUntil(() -> this.harness.connections.inFlight() == 0);
        this.harness.runFor(100);

        assertTrue(this.harness.notices.isEmpty(), "cancelled on the second try: the order ends on its events");
    }

    @Test
    void aDelayedOrderNeverFoundIsToldAsARefusedCancel() {
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(NOT_FOUND) : Reply.ok(DELAYED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.skip(4_900);
        assertTrue(this.harness.notices.isEmpty(), "a matching delay is waited out first");

        this.harness.skip(600);
        assertEquals(1, this.harness.notices.size());
        assertEquals(ExecType.CANCEL_REJECT, this.harness.notices.get(0).execType, "the OMS must not think it's gone");
    }

    @Test
    void aDelayedOrderFoundOnlyOnceASecondsLongDelayEndsIsCancelled() {
        final long[] firstDelete = {0};
        this.harness.server.script(request -> {
            if (!request.method().equals("DELETE")) {
                return Reply.ok(DELAYED);
            }
            synchronized (firstDelete) {
                if (firstDelete[0] == 0) {
                    firstDelete[0] = System.nanoTime();
                }
            }
            return System.nanoTime() - firstDelete[0] < TimeUnit.MILLISECONDS.toNanos(2_500)
                    ? Reply.ok(NOT_FOUND)
                    : Reply.ok(canceled(request));
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runFor(3_500);

        assertTrue(this.harness.notices.isEmpty(), "found and cancelled once its delay ended: " + this.harness.notices);
    }

    @Test
    void anOrderRefusedWhileItsCancelWasOutIsOnlyRejected() {
        this.harness.server.script(request -> request.method().equals("DELETE")
                ? Reply.ok(NOT_FOUND)
                : new Reply(400, "{\"error\":\"invalid post-only order: order crosses book\"}", 150));
        submit(1);
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1);
        this.harness.cancel(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());
        this.harness.runFor(100);

        assertEquals(
                List.of(ExecType.REJECT),
                this.harness.notices.stream().map(n -> n.execType).toList(),
                "the REJECT answers the cancel too; a refused cancel would drop the OMS's queued replacement");
    }

    @Test
    void anOrderStillHeldAfterItsCancelFoundNothingIsCancelledAgainTwiceAndThenToldRefused() {
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(NOT_FOUND) : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.runFor(400);
        assertTrue(this.harness.notices.isEmpty(), "left to the order's events at first");
        assertEquals(2, this.harness.requests("DELETE").size());

        // Not yet on the book when the first cancel came, perhaps: tried again soon, silently.
        this.harness.runUntil(() -> this.harness.answered("DELETE", 4), 1_000);
        this.harness.runFor(500);
        assertTrue(this.harness.notices.isEmpty());

        // Its events never end it.
        this.harness.runUntil(() -> !this.harness.notices.isEmpty(), 5_000);
        assertEquals(6, this.harness.requests("DELETE").size());
        assertEquals(ExecType.CANCEL_REJECT, this.harness.notices.get(0).execType);
    }

    @Test
    void anOrderNotYetOnTheBookWhenPulledIsCancelledWithinASecond() {
        final AtomicInteger deletes = new AtomicInteger();
        this.harness.server.script(request -> {
            if (!request.method().equals("DELETE")) {
                return Reply.ok(ACCEPTED);
            }
            // Answered `live`, but booked only after the first round looked for it; nor did the venue cancel it then.
            return deletes.getAndIncrement() < 2 ? Reply.ok(NOT_FOUND) : Reply.ok(canceled(request));
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        final long start = System.nanoTime();
        this.harness.runUntil(() -> this.harness.answered("DELETE", 3), 2_000);

        final long tookNanos = this.harness.requests("DELETE").get(2).receivedNanos() - start;
        assertTrue(tookNanos < TimeUnit.SECONDS.toNanos(1), "cancelled again in " + tookNanos / 1_000_000 + " ms");
        this.harness.runFor(100);
        assertTrue(this.harness.notices.isEmpty());
    }

    @Test
    void anOrderEndedByItsEventsIsNotCancelledAgain() {
        this.harness.server.script(
                request -> request.method().equals("DELETE") ? Reply.ok(NOT_FOUND) : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.release(1);
        this.harness.skip(3_500);

        assertEquals(2, this.harness.requests("DELETE").size());
        assertTrue(this.harness.notices.isEmpty());
    }

    @Test
    void aCancelRefusedAfterItsOrderEndedTellsNothing() {
        this.harness.server.script(request -> request.method().equals("DELETE")
                ? new Reply(400, "{\"error\":\"market is not active\"}", 200)
                : Reply.ok(ACCEPTED));
        submit(1);
        this.harness.runUntil(() -> this.harness.answered("POST", 1));
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.requests("DELETE").size() == 2);
        this.harness.release(1);
        this.harness.runUntil(() -> this.harness.answered("DELETE", 2));
        this.harness.runFor(100);

        assertTrue(this.harness.notices.isEmpty(), "the order's last report came already: " + this.harness.notices);
    }

    @Test
    void anOrderRefusedBecauseItsCancelArrivedFirstIsReportedCancelled() {
        this.harness.server.script(request -> request.method().equals("DELETE")
                ? Reply.ok(NOT_FOUND)
                : new Reply(400, "{\"error\":\"order 0xab is invalid. Already canceled.\"}", 150));
        submit(1);
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1);
        this.harness.cancel(1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty());

        assertEquals(1, this.harness.notices.size());
        assertEquals(ExecType.CANCEL, this.harness.notices.get(0).execType, "cancelled, not a refusal");
        assertTrue(this.harness.notices.get(0).canceledBeforePlaced);
        assertTrue(this.harness.resting.isEmpty());
    }

    @Test
    void aCancelThatOvertookTheOrderGoesAgainOnceTheOrderRests() {
        final AtomicInteger deletes = new AtomicInteger();
        this.harness.server.script(request -> {
            if (!request.method().equals("DELETE")) {
                return Reply.ok(ACCEPTED).after(150);
            }
            // The first round reaches the venue before the order; the next finds it resting.
            return deletes.getAndIncrement() < 2 ? Reply.ok(NOT_FOUND) : Reply.ok(canceled(request));
        });
        submit(1);
        this.harness.runUntil(() -> this.harness.requests("POST").size() == 1);
        this.harness.cancel(1);
        this.harness.runUntil(() -> this.harness.requests("DELETE").size() == 2);
        this.harness.runFor(60);
        assertEquals(2, this.harness.requests("DELETE").size(), "nothing more until the order's answer");

        this.harness.runUntil(() -> this.harness.answered("DELETE", 3));
        this.harness.runFor(50);
        final long sinceSubmit = this.harness.requests("DELETE").get(2).receivedNanos()
                - this.harness.requests("POST").get(0).receivedNanos();
        assertTrue(sinceSubmit >= TimeUnit.MILLISECONDS.toNanos(150), "sent again only after the order's answer");
        assertTrue(this.harness.notices.isEmpty(), "cancelled: the order ends on the venue's events");
    }

    /**
     * A cancel racing its order, with every request's timing random, against a venue that keeps Polymarket's rules: a
     * cancel before the order finds nothing and the order is then refused as already cancelled, except now and then
     * placed anyway, as the venue was seen to. Whatever the order, no order is left resting, and the OMS is told only
     * what happened: cancelled when refused, nothing more otherwise (the order's own events end it).
     */
    @RepeatedTest(150)
    void aCancelRacingItsOrderNeverLeavesItResting(final RepetitionInfo repetition) {
        final Random random = new Random(repetition.getCurrentRepetition() * 7919L);
        final RacingVenue venue = new RacingVenue(random);
        this.harness.server.script(venue::answer);
        submit(1);
        final long cancelAfterNanos = TimeUnit.MICROSECONDS.toNanos(random.nextInt(20_000));
        final long start = System.nanoTime();
        this.harness.runUntil(() -> System.nanoTime() - start >= cancelAfterNanos);
        this.harness.cancel(1);
        this.harness.runUntil(() -> venue.settled() && this.harness.connections.inFlight() == 0, 5_000);
        this.harness.runFor(60);

        final String seen = venue.log + " notices "
                + this.harness.notices.stream()
                        .map(n -> String.valueOf(n.execType))
                        .toList();
        RACE_PATHS.merge(venue.path(), 1, Integer::sum);
        if (venue.placedAnyway) {
            // The lane can't see it: the venue said refused. Its notice marks it so the reader flags the order's
            // events, which are the only sign it rests.
            assertTrue(this.harness.notices.get(0).canceledBeforePlaced, seen);
        } else {
            assertFalse(venue.resting(), "left resting: " + seen);
        }
        assertTrue(
                this.harness.notices.stream().noneMatch(n -> n.execType == ExecType.CANCEL_REJECT),
                "a refused cancel told: " + seen);
        if (venue.refused) {
            assertEquals(1, this.harness.notices.size(), seen);
            assertEquals(ExecType.CANCEL, this.harness.notices.get(0).execType, seen);
        } else {
            assertTrue(this.harness.notices.isEmpty(), seen);
        }
        if (venue.bookedLate()) {
            // The venue's events end the order, and the reader releases it: the gateway's later checks on a cancel
            // that found nothing (600 ms, 3 s) must then tell the session nothing more.
            this.harness.release(1);
            this.harness.skip(3_200);
            assertTrue(
                    this.harness.notices.stream().noneMatch(n -> n.execType == ExecType.CANCEL_REJECT),
                    "a refused cancel told after the order ended: " + seen);
        }
    }

    private static final Map<String, Integer> RACE_PATHS = new ConcurrentHashMap<>();

    /** The race test is only worth its runs if they reached each way the race can end. */
    @AfterAll
    static void everyWayTheRaceEndsWasReached() {
        if (RACE_PATHS.isEmpty()) {
            return;
        }
        for (final String path : List.of("placed", "refused", "placed anyway", "booked late", "booked late, left")) {
            assertTrue(RACE_PATHS.getOrDefault(path, 0) >= 3, "too few races ended " + path + ": " + RACE_PATHS);
        }
    }

    /** Polymarket's handling of one order and the cancels naming it, decided in the order requests arrive. */
    private static final class RacingVenue {
        private final Random random;
        private boolean tombstoned;
        private boolean placed;
        private boolean canceled;
        private boolean refused;
        private boolean placedAnyway;
        private boolean canceledByVenue;
        private boolean leftByVenue;
        private boolean leavesUnbooked;
        private long bookedAtNanos;
        private boolean submitSeen;
        private final StringBuilder log = new StringBuilder();

        RacingVenue(final Random random) {
            this.random = random;
        }

        /**
         * Each request is decided after its own processing time, as the venue's are, so a cancel sent after its order
         * can still be decided first; its answer then takes a moment more to come back.
         */
        Reply answer(final Request request) {
            final boolean submit = request.method().equals("POST");
            final long processing;
            final long back;
            synchronized (this) {
                processing = this.random.nextInt(submit ? 60 : 30);
                back = this.random.nextInt(10);
            }
            try {
                Thread.sleep(processing);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return decide(request, submit).after(back);
        }

        private synchronized Reply decide(final Request request, final boolean submit) {
            if (submit) {
                this.submitSeen = true;
                if (this.tombstoned) {
                    // Refused as already cancelled; now and then the venue placed the order all the same.
                    this.refused = true;
                    this.placedAnyway = this.random.nextInt(7) == 0;
                    this.placed = this.placedAnyway;
                    this.log.append(this.placedAnyway ? "refused but placed anyway;" : "refused;");
                    return new Reply(400, "{\"error\":\"order 0xab is invalid. Already canceled.\"}", 0);
                }
                this.placed = true;
                // Now and then answered `live` before the order is on the book, as the venue was seen to.
                if (this.random.nextBoolean()) {
                    this.bookedAtNanos =
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20 + this.random.nextInt(60));
                    this.leavesUnbooked = this.random.nextBoolean();
                    this.log.append("placed, booked late;");
                } else {
                    this.log.append("placed;");
                }
                return Reply.ok(ACCEPTED);
            }
            if (this.placed && System.nanoTime() < this.bookedAtNanos && !this.canceled) {
                // Not on the book yet: the cancel finds nothing. The venue was seen to cancel such an order itself as
                // it books it; not assumed here half the time, when only a later cancel can take it off.
                if (this.leavesUnbooked) {
                    this.leftByVenue = true;
                    this.log.append("cancel not found, order not yet booked, left;");
                    return Reply.ok(NOT_FOUND);
                }
                this.tombstoned = true;
                this.canceled = true;
                this.canceledByVenue = true;
                this.log.append("cancel not found, order not yet booked;");
                return Reply.ok(NOT_FOUND);
            }
            if (this.placed && !this.canceled && !this.refused) {
                this.canceled = true;
                this.log.append("cancel ok;");
                return Reply.ok(canceled(request));
            }
            if (!this.placed) {
                this.tombstoned = true;
            }
            this.log.append("cancel not found;");
            return Reply.ok(NOT_FOUND);
        }

        /** The order has arrived and is gone: refused, or placed and cancelled. */
        synchronized boolean settled() {
            return this.submitSeen && (this.refused || this.canceled);
        }

        synchronized boolean resting() {
            return this.placed && !this.canceled;
        }

        synchronized boolean bookedLate() {
            return this.canceledByVenue || this.leftByVenue;
        }

        synchronized String path() {
            if (this.placedAnyway) {
                return "placed anyway";
            }
            if (this.canceledByVenue) {
                return "booked late";
            }
            if (this.leftByVenue) {
                return "booked late, left";
            }
            return this.refused ? "refused" : "placed";
        }
    }

    private void submit(final long counter) {
        this.harness.submit(counter, command -> command.size = SIZE);
    }

    /** A cancel's answer listing the order it names as cancelled. */
    private static String canceled(final Request request) {
        final Matcher id = ORDER_ID.matcher(request.body());
        return "{\"canceled\":[\"" + (id.find() ? id.group(1) : "") + "\"],\"not_canceled\":{}}";
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
