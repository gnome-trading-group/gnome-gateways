package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lane against a local HTTP server over real connections: what it sends, in what order, and what it reports. */
class AccountLaneTest {

    private static final long SLOT = 3;
    private static final int CONNECTIONS = LaneHarness.CONNECTIONS;
    private static final long READ_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long RESPONSE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    // The local server stamps a request when its handler thread reads it, which can lag the lane's write by several
    // milliseconds in a busy JVM. Waits checked against it are hundreds of milliseconds, so this still tells waiting
    // from not.
    private static final long ARRIVAL_SLACK_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private final TestLaneVenue venue = new TestLaneVenue();
    private LaneHarness<String> harness;
    private FakeVenueServer server;
    private AsyncHTTPConnections connections;
    private AccountLane<String> lane;
    private List<OrderContext> handed;
    private List<OrderContext> notices;

    @BeforeEach
    void setUp() throws Exception {
        this.harness = new LaneHarness<>(
                this.venue,
                READ_SETTLE_NANOS,
                RESPONSE_TIMEOUT_NANOS,
                SLOT,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
        this.server = this.harness.server;
        this.connections = this.harness.connections;
        this.lane = this.harness.lane;
        this.handed = this.harness.handed;
        this.notices = this.harness.notices;
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    @Test
    void anAcceptedOrderIsHandedToTheReaderAndCancelledByTheVenuesId() {
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        assertEquals(1, this.handed.size());
        assertEquals("s3-1", correlationId(this.handed.get(0)));
        assertTrue(this.notices.isEmpty(), "an acceptance is the reader's to report, from the venue's events");

        cancel(1);
        runUntil(() -> requests("DELETE").size() == 1 && this.connections.inFlight() == 0);
        assertEquals("/orders/v-s3-1", requests("DELETE").get(0).path());
        assertEquals(1, this.lane.workingOrders(), "a cancel's acceptance ends nothing until the venue's events do");

        release(1);
        runUntil(() -> this.lane.workingOrders() == 0);
    }

    @Test
    void ordersGoOutTogetherOnSeveralConnections() {
        this.server.script(request -> Reply.ok("v-" + request.body()).after(300));
        for (int counter = 1; counter <= CONNECTIONS; counter++) {
            submit(counter);
        }
        final long start = System.nanoTime();
        runUntil(() -> requests("POST").size() == CONNECTIONS && this.connections.inFlight() == 0);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(CONNECTIONS, this.server.maxConcurrent(), "every order was on the wire at once");
        assertTrue(
                elapsedMillis < 300L * CONNECTIONS / 2,
                "slow answers didn't queue behind each other: " + elapsedMillis);
        assertTrue(this.notices.isEmpty());
    }

    @Test
    void aLostAnswerIsResentAndADuplicateLookedUp() {
        this.server.script(request -> {
            final int posts = requests("POST").size();
            if (request.method().equals("POST")) {
                // The first send's answer never comes in time; the resend is refused as a duplicate.
                return posts == 1 ? Reply.ok("v-" + request.body()).after(800) : Reply.status(409);
            }
            return Reply.ok("v-s3-1");
        });
        submit(1);
        runUntil(() -> requests("GET").size() == 1 && this.connections.inFlight() == 0, 3_000);
        assertEquals("/orders/s3-1", requests("GET").get(0).path());
        assertTrue(this.notices.isEmpty(), "found, so accepted: nothing for the reader");

        cancel(1);
        runUntil(() -> requests("DELETE").size() == 1);
        assertEquals("/orders/v-s3-1", requests("DELETE").get(0).path(), "the lookup gave the venue's id");
    }

    @Test
    void aRefusedResendIsBelievedMissingOnlyOnceTheVenuesReadsHaveCaughtUp() {
        this.server.script(request -> {
            if (request.method().equals("POST")) {
                return requests("POST").size() == 1 ? Reply.status(503) : Reply.status(400);
            }
            return Reply.status(404);
        });
        submit(1);
        runUntil(() -> !this.notices.isEmpty(), 3_000);

        final List<Request> posts = requests("POST");
        final List<Request> gets = requests("GET");
        assertEquals(2, posts.size(), "the unclear first answer was resent");
        assertEquals(2, gets.size(), "the miss right after the resend was looked at again");
        // The server stamps each request as it arrives, and a body takes a little longer to arrive than none.
        final long gap = gets.get(1).receivedNanos() - posts.get(1).receivedNanos();
        assertTrue(
                gap >= READ_SETTLE_NANOS - ARRIVAL_SLACK_NANOS,
                "the second lookup waited out the venue's read lag: " + gap / 1_000 + " us");
        final OrderContext notice = this.notices.get(0);
        assertEquals(ExecType.REJECT, notice.execType);
        assertEquals(RejectReason.INVALID_PRICE, notice.rejectReason);
        assertEquals(0, this.lane.workingOrders());
    }

    @Test
    void aVenuesRefusalEndsTheOrder() {
        this.server.script(request -> Reply.status(400));
        submit(1);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.notices.get(0).execType);
        assertEquals(OrderStatus.REJECTED, this.notices.get(0).orderStatus);
        assertEquals(RejectReason.INVALID_PRICE, this.notices.get(0).rejectReason);
        assertEquals(1, requests("POST").size());
        assertEquals(0, this.lane.workingOrders());
    }

    @Test
    void aRateLimitedSubmitIsReportedSo() {
        this.server.script(request -> Reply.status(429));
        submit(1);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.RATE_LIMITED, this.notices.get(0).rejectReason);
    }

    @Test
    void aCancelBeforeTheSubmitIsSentDropsItUnsent() {
        this.server.script(request -> Reply.ok("v"));
        submit(1);
        cancel(1);
        runUntil(() -> !this.notices.isEmpty());
        runFor(100);
        assertEquals(ExecType.CANCEL, this.notices.get(0).execType);
        assertEquals(OrderStatus.CANCELED, this.notices.get(0).orderStatus);
        assertTrue(this.server.requests().isEmpty(), "nothing reached the venue");
        assertEquals(0, this.lane.workingOrders());
    }

    @Test
    void aCancelWhileTheSubmitIsOnTheWireWaitsForTheVenuesId() {
        this.server.script(request -> request.method().equals("POST")
                ? Reply.ok("v-" + request.body()).after(300)
                : Reply.ok(""));
        submit(1);
        runUntil(() -> this.connections.inFlight() == 1);
        cancel(1);
        runUntil(() -> requests("DELETE").size() == 1);
        final List<Request> sent = this.server.requests();
        assertEquals("POST", sent.get(0).method());
        assertEquals("/orders/v-s3-1", sent.get(1).path());
    }

    @Test
    void cancellingASessionCancelsEachOfItsOrdersAndNoOtherSessions() {
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        submit(2);
        this.harness.submit(1, command -> command.sessionSlot = SLOT + 1);
        runUntil(() -> requests("POST").size() == 3 && this.connections.inFlight() == 0);

        this.harness.command(LaneCommand.CANCEL_SESSION, 0, command -> {});
        runUntil(() -> requests("DELETE").size() == 2 && this.connections.inFlight() == 0);
        runFor(100);
        final List<String> cancelled =
                requests("DELETE").stream().map(Request::path).sorted().toList();
        assertEquals(List.of("/orders/v-s3-1", "/orders/v-s3-2"), cancelled);
        assertEquals(3, this.lane.workingOrders(), "each ends once the venue's events say so");
        assertTrue(this.notices.isEmpty());
    }

    @Test
    void aKillOfOneListingCancelsOnlyTheSessionsOrdersThere() {
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        this.harness.submit(2, command -> command.securityId = TestLaneVenue.OTHER_SECURITY_ID);
        this.harness.submit(3, command -> command.sessionSlot = SLOT + 1);
        runUntil(() -> requests("POST").size() == 3 && this.connections.inFlight() == 0);

        this.harness.command(LaneCommand.CANCEL_ORDERS, 0, command -> {
            command.exchangeId = TestLaneVenue.EXCHANGE_ID;
            command.securityId = TestLaneVenue.SECURITY_ID;
        });
        runUntil(() -> requests("DELETE").size() == 1 && this.connections.inFlight() == 0);
        runFor(100);

        assertEquals(
                List.of("/orders/v-s3-1"),
                requests("DELETE").stream().map(Request::path).toList());
    }

    @Test
    void aKillsCancelLeavesTheSessionFreeToSendOnceTheGatewayLetsItsOrdersThrough() {
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);

        this.harness.command(LaneCommand.CANCEL_ORDERS, 0, command -> {});
        submit(2);

        runUntil(() -> requests("DELETE").size() == 1 && requests("POST").size() == 2);
        assertTrue(this.notices.isEmpty(), "nothing refused: a kill's refusals are the gateway's");
    }

    @Test
    void aSessionPastItsOpenOrderCapIsRefusedUntilOneEnds() {
        this.lane.caps(new AccountLane.Caps(2, 0, 0));
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        submit(2);
        submit(3);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.SESSION_CAP, this.notices.get(0).rejectReason);
        assertEquals(3, this.notices.get(0).clientOidCounter);

        runUntil(() -> requests("POST").size() == 2 && this.connections.inFlight() == 0);
        release(1);
        runUntil(() -> this.lane.workingOrders() == 1);
        submit(4);
        runUntil(() -> requests("POST").size() == 3);
    }

    @Test
    void aSessionPastItsNewOrderRateIsRefusedUntilItsBucketRefills() {
        this.lane.caps(new AccountLane.Caps(0, 2, 0));
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        submit(2);
        submit(3);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.SESSION_CAP, this.notices.get(0).rejectReason);
        assertEquals(3, this.notices.get(0).clientOidCounter);

        // Two a second: one more in half a second.
        runFor(600);
        submit(4);
        runUntil(() -> requests("POST").size() == 3);
        assertEquals(1, this.notices.size());
    }

    @Test
    void theAccountsOpenOrderCapCountsEverySession() {
        this.lane.caps(new AccountLane.Caps(0, 0, 2));
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        this.harness.submit(1, command -> command.sessionSlot = SLOT + 1);
        runUntil(() -> requests("POST").size() == 2 && this.connections.inFlight() == 0);

        this.harness.submit(2, command -> command.sessionSlot = SLOT + 2);
        runUntil(() -> !this.notices.isEmpty());

        assertEquals(RejectReason.SESSION_CAP, this.notices.get(0).rejectReason);
    }

    @Test
    void theGatewayCanGrantASessionLessThanTheDefault() {
        this.lane.caps(new AccountLane.Caps(10, 0, 0));
        this.harness.command(LaneCommand.SESSION, 0, command -> command.maxOpenOrders = 1);
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        submit(2);

        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.SESSION_CAP, this.notices.get(0).rejectReason);
        assertEquals(2, this.notices.get(0).clientOidCounter);
    }

    @Test
    void aSessionAskingForMoreThanTheDefaultGetsTheDefault() {
        this.lane.caps(new AccountLane.Caps(1, 0, 0));
        this.harness.command(LaneCommand.SESSION, 0, command -> command.maxOpenOrders = 5);
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        submit(2);

        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.SESSION_CAP, this.notices.get(0).rejectReason);
        assertEquals(2, this.notices.get(0).clientOidCounter);
    }

    @Test
    void aSessionAskingFasterThanItsRateRefillsStillGetsItsOrdersAsTheyRefill() {
        this.lane.caps(new AccountLane.Caps(0, 20, 0));
        this.server.script(request -> Reply.ok("v-" + request.body()));
        // Twenty a second: the bucket's 20 go at once, then one each 50 ms, however often it asks in between.
        for (int counter = 1; counter <= 20; counter++) {
            submit(counter);
        }
        runUntil(() -> requests("POST").size() == 20 && this.connections.inFlight() == 0);
        long counter = 21;
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < until) {
            submit(counter++);
            runFor(1);
        }
        runFor(100);

        final int sent = requests("POST").size() - 20;
        assertTrue(sent >= 7 && sent <= 12, "about 10 in half a second at 20 a second, not 0: " + sent);
    }

    @Test
    void anOrdersListingPriceIsRecordedFromItsFirstSend() {
        final List<Long> prices = new ArrayList<>();
        this.lane.ownership(new AccountLane.Ownership() {
            @Override
            public void added(final OrderContext ctx) {
                prices.add(ctx.limitPrice);
            }
        });
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        runUntil(() -> requests("POST").size() == 1);

        assertEquals(List.of(400_000_000L), prices, "the price the session sent, which a restart restores");
    }

    @Test
    void cancelsGoWhateverTheCaps() {
        this.lane.caps(new AccountLane.Caps(1, 1, 1));
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);

        cancel(1);

        runUntil(() -> requests("DELETE").size() == 1);
    }

    @Test
    void cancellingASessionDropsItsOrdersNotYetSent() {
        this.server.script(request -> Reply.ok("v-" + request.body()).after(300));
        // Every connection busy, so the next order waits unsent.
        for (int i = 1; i <= CONNECTIONS + 1; i++) {
            submit(i);
        }
        runUntil(() -> this.connections.inFlight() == CONNECTIONS);
        this.harness.command(LaneCommand.CANCEL_SESSION, 0, command -> {});
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(ExecType.CANCEL, this.notices.get(0).execType);
        assertEquals(CONNECTIONS + 1, this.notices.get(0).clientOidCounter);
        runUntil(() -> requests("POST").size() == CONNECTIONS && this.connections.inFlight() == 0);
        runFor(100);
        assertEquals(CONNECTIONS, requests("POST").size(), "the order held back never went");
    }

    @Test
    void aNewOrderBehindItsSessionsCancelIsRefusedUntilTheSessionIsIntroducedAgain() {
        this.server.script(request -> Reply.ok("v-" + request.body()));
        // On the ring behind the cancel, as one ingress let through just before the session was cut.
        this.harness.command(LaneCommand.CANCEL_SESSION, 0, command -> {});
        submit(1);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.notices.get(0).execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, this.notices.get(0).rejectReason);

        this.harness.session(SLOT, 1, 0);
        submit(2);
        runUntil(() -> requests("POST").size() == 1);
        runFor(100);
        assertEquals(1, requests("POST").size(), "only the order after its hello went");
    }

    @Test
    void aCancelOfAnOrderTheLaneDoesntHoldIsUnknown() {
        cancel(42);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(ExecType.CANCEL_REJECT, this.notices.get(0).execType);
        assertEquals(RejectReason.UNKNOWN_ORDER, this.notices.get(0).rejectReason);
        assertEquals(42, this.notices.get(0).clientOidCounter);
    }

    @Test
    void anOrderForAnUnloadedMarketIsRefusedUnsent() {
        this.harness.submit(1, command -> command.securityId = 999);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.GATEWAY_REJECTED, this.notices.get(0).rejectReason);
        assertTrue(this.handed.isEmpty());
    }

    @Test
    void anAcceptedAmendIsToldToTheReader() {
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        amend(1, 500_000_000L, 4_000_000L);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals("/orders/v-s3-1/amend", requests("POST").get(1).path());
        assertTrue(this.notices.get(0).amendAccepted);
        assertEquals(4_000_000L, this.notices.get(0).originalQty);
    }

    @Test
    void anAcceptedAmendIsRecordedAtItsNewTerms() {
        final List<long[]> amended = new ArrayList<>();
        this.lane.ownership(new AccountLane.Ownership() {
            @Override
            public void amended(final OrderContext ctx) {
                amended.add(new long[] {ctx.limitPrice, ctx.originalQty});
            }
        });
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        amend(1, 500_000_000L, 4_000_000L);
        runUntil(() -> !this.notices.isEmpty());

        assertEquals(1, amended.size());
        assertEquals(500_000_000L, amended.get(0)[0]);
        assertEquals(4_000_000L, amended.get(0)[1]);
    }

    @Test
    void anOrderEndedWhileARequestIsOutIsFreedWhenItsAnswerComes() {
        this.server.script(request -> request.method().equals("POST")
                ? Reply.ok("v-" + request.body())
                : Reply.ok("").after(300));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        cancel(1);
        runUntil(() -> this.connections.inFlight() == 1);
        release(1);
        runFor(100);
        assertEquals(1, this.lane.workingOrders(), "held while its cancel is on the wire");
        runUntil(() -> this.lane.workingOrders() == 0);
        assertTrue(this.notices.isEmpty());
    }

    @Test
    void anOrderCounterIsUsedOnce() {
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        submit(1);
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(RejectReason.GATEWAY_REJECTED, this.notices.get(0).rejectReason);
        assertEquals(1, requests("POST").size());
        assertFalse(this.handed.size() > 1);
    }

    @Test
    void aCancelWaitingOnASubmitTheVenueCantLookUpIsAnswered() {
        this.venue.resendsAreSafe = false;
        this.venue.lookupsPossible = false;
        this.server.script(request -> Reply.ok("v-" + request.body()).after(800));
        submit(1);
        runUntil(() -> this.connections.inFlight() == 1);
        cancel(1);
        runUntil(() -> !this.notices.isEmpty(), 3_000);
        assertEquals(ExecType.CANCEL_REJECT, this.notices.get(0).execType);
        assertEquals(RejectReason.UNKNOWN, this.notices.get(0).rejectReason, "the venue's id never came");
        assertEquals(1, this.lane.workingOrders(), "the order is left for the venue's events");
    }

    @Test
    void anOrderNamedOnlyByAnEventCanBeCancelled() {
        this.venue.resendsAreSafe = false;
        this.server.script(request -> switch (request.method()) {
            case "POST" -> Reply.ok("v-" + request.body()).after(800);
            case "GET" -> Reply.status(404);
            default -> Reply.ok("");
        });
        submit(1);
        runUntil(() -> requests("GET").size() == 1 && this.connections.inFlight() == 0, 3_000);

        this.harness.venueIdLearned(1, "v-s3-1");
        cancel(1);
        runUntil(() -> requests("DELETE").size() == 1);
        assertEquals("/orders/v-s3-1", requests("DELETE").get(0).path());
        assertTrue(this.notices.isEmpty());
    }

    @Test
    void theOneLookupAVenueThatCantTakeAResendGetsWaitsForItsReadsToCatchUp() throws Exception {
        final long settleNanos = TimeUnit.MILLISECONDS.toNanos(1_500);
        this.venue.resendsAreSafe = false;
        try (LaneHarness<String> lagging = new LaneHarness<>(
                this.venue,
                settleNanos,
                RESPONSE_TIMEOUT_NANOS,
                SLOT,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID)) {
            lagging.server.script(request -> request.method().equals("POST")
                    ? Reply.ok("v-" + request.body()).after(800)
                    : Reply.status(404));
            lagging.submit(1);
            lagging.runUntil(() -> lagging.requests("GET").size() == 1, 4_000);
            assertTrue(
                    lagging.requests("GET").get(0).receivedNanos()
                                    - lagging.requests("POST").get(0).receivedNanos()
                            >= settleNanos - TimeUnit.MILLISECONDS.toNanos(100),
                    "looked up only once the venue's reads had caught up with the send (the server stamps a request a"
                            + " little after the lane sends it)");
        }
    }

    @Test
    void newOrdersWaitWhileTheReaderIsStalledButCancelsStillGoOut() {
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        runUntil(() -> this.harness.answered("POST", 1));
        this.harness.readerStalled = true;
        for (int counter = 2; counter <= 230; counter++) {
            submit(counter);
        }
        runFor(200);
        cancel(1);
        runUntil(() -> requests("DELETE").size() == 1);
        assertEquals("/orders/v-s3-1", requests("DELETE").get(0).path(), "the cancel went out with the reader stalled");

        submit(999);
        cancel(999);
        runFor(50);
        this.harness.readerStalled = false;
        runFor(300);
        assertTrue(
                this.notices.stream().anyMatch(n -> n.clientOidCounter == 999 && n.execType == ExecType.CANCEL),
                "a new order cancelled while held here is reported cancelled");
        assertTrue(requests("POST").stream().noneMatch(r -> r.body().equals("s3-999")), "and was never sent");
    }

    @Test
    void aNewOrderThatCantGoOutInTimeIsRefused() throws Exception {
        try (LaneHarness<String> slow = new LaneHarness<>(
                this.venue,
                READ_SETTLE_NANOS,
                TimeUnit.SECONDS.toNanos(3),
                TimeUnit.MILLISECONDS.toNanos(200),
                SLOT,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID)) {
            slow.server.script(request -> Reply.ok("v-" + request.body()).after(1_000));
            for (int counter = 1; counter <= CONNECTIONS + 1; counter++) {
                slow.submit(counter);
            }
            slow.runUntil(() -> !slow.notices.isEmpty(), 2_000);
            assertEquals(CONNECTIONS + 1, slow.notices.get(0).clientOidCounter);
            assertEquals(ExecType.REJECT, slow.notices.get(0).execType);
            assertEquals(RejectReason.GATEWAY_DEGRADED, slow.notices.get(0).rejectReason, "no connection was free");
            slow.runUntil(() -> slow.connections.inFlight() == 0, 3_000);
            assertEquals(CONNECTIONS, slow.requests("POST").size(), "the refused order was never sent");
        }
    }

    @Test
    void twoOrdersWithOneCorrelationIdAreNotBothSent() {
        this.venue.fixedCorrelation = "same";
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        submit(2);
        runUntil(() -> !this.notices.isEmpty() && this.harness.answered("POST", 1));
        assertEquals(2, this.notices.get(0).clientOidCounter);
        assertEquals(RejectReason.GATEWAY_REJECTED, this.notices.get(0).rejectReason);
        runFor(50);
        assertEquals(1, requests("POST").size());
    }

    @Test
    void anUnknownCommandIsRefusedNotFatal() {
        this.harness.command(99, 1, command -> {});
        runUntil(() -> !this.notices.isEmpty());
        assertEquals(ExecType.REJECT, this.notices.get(0).execType);
        assertEquals(RejectReason.GATEWAY_REJECTED, this.notices.get(0).rejectReason);
    }

    @Test
    void aCancelOfAnUnsentOrderAnswersTheAmendWaitingOnItFirst() {
        this.server.script(request -> Reply.ok("v-" + request.body()));
        submit(1);
        amend(1, 450_000_000L, 2_000_000L);
        cancel(1);
        runUntil(() -> this.notices.size() == 2);
        assertEquals(ExecType.CANCEL_REJECT, this.notices.get(0).execType, "the amend's answer");
        assertEquals(ExecType.CANCEL, this.notices.get(1).execType, "the order's");
        runFor(50);
        assertTrue(this.server.requests().isEmpty());
    }

    @Test
    void aQuietAccountAlwaysHasAFreshConnectionReadyForTheNextOrder() throws Exception {
        final long maxIdleNanos = TimeUnit.SECONDS.toNanos(2);
        try (LaneHarness<String> quiet = new LaneHarness<>(
                this.venue,
                AccountLane.Timing.defaults(),
                SLOT,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID,
                maxIdleNanos)) {
            quiet.runUntil(() -> quiet.connections.acquire() != AsyncHTTPConnections.NONE);
            // Unused for longer than any connection may be trusted: without replacing ahead of time, all would be
            // stale together and the next order would wait for a new connect.
            final long until = System.nanoTime() + 2 * maxIdleNanos;
            int samples = 0;
            while (System.nanoTime() < until) {
                quiet.runFor(20);
                assertNotEquals(
                        AsyncHTTPConnections.NONE,
                        quiet.connections.acquire(),
                        "a connection fit to use, at every moment");
                samples++;
            }
            assertTrue(samples > 100);
        }
    }

    private LaneHarness<String> sweeping() throws Exception {
        return new LaneHarness<>(
                this.venue,
                new AccountLane.Timing(
                        TimeUnit.MILLISECONDS.toNanos(50),
                        TimeUnit.MILLISECONDS.toNanos(200),
                        TimeUnit.SECONDS.toNanos(5),
                        TimeUnit.MILLISECONDS.toNanos(100),
                        TimeUnit.MILLISECONDS.toNanos(600),
                        TimeUnit.MILLISECONDS.toNanos(300)),
                SLOT,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
    }

    @Test
    void aRecoveredOrderWithTheVenuesIdIsNeverResentAndCanBeCancelled() {
        this.server.script(request -> Reply.ok(""));
        assertTrue(this.lane.recover(recorded(7, "v-old")));
        assertEquals(1, this.lane.workingOrders());

        cancel(7);
        runUntil(() -> requests("DELETE").size() == 1 && this.connections.inFlight() == 0);
        assertEquals("/orders/v-old", requests("DELETE").get(0).path());
        assertTrue(requests("POST").isEmpty(), "a recovered order is never sent again");
        assertTrue(this.handed.isEmpty(), "the reader is handed it apart, by the restart");
    }

    @Test
    void aRecoveredOrderWithoutTheVenuesIdIsLookedForOnceReadsSettleAndRejectedIfTheVenueNeverHadIt() {
        this.server.script(request -> Reply.status(404));
        final long start = System.nanoTime();
        assertTrue(this.lane.recover(recorded(7, null)));

        runUntil(() -> !this.notices.isEmpty());
        assertTrue(
                System.nanoTime() - start >= READ_SETTLE_NANOS, "a send just before the restart has had time to show");
        assertEquals(1, requests("GET").size());
        assertTrue(requests("POST").isEmpty());
        assertEquals(ExecType.REJECT, this.notices.get(0).execType);
        assertTrue(this.notices.get(0).endsRegistered);
        assertEquals(0, this.lane.workingOrders());
    }

    @Test
    void aRecoveredOrderFoundCarriesOnAsAnyOther() {
        this.server.script(request -> request.method().equals("GET") ? Reply.ok("v-found") : Reply.ok(""));
        assertTrue(this.lane.recover(recorded(7, null)));
        runUntil(() -> requests("GET").size() == 1 && this.connections.inFlight() == 0);

        cancel(7);
        runUntil(() -> requests("DELETE").size() == 1);
        assertEquals("/orders/v-found", requests("DELETE").get(0).path());
        assertTrue(this.notices.isEmpty());
    }

    @Test
    void anOrderIsRecoveredOnlyIfItCanBeHeld() {
        assertTrue(this.lane.recover(recorded(7, "v-old")));
        assertFalse(this.lane.recover(recorded(7, "v-old")), "held already");
        final OrderContext elsewhere = recorded(8, "v-other");
        elsewhere.securityId = TestLaneVenue.SECURITY_ID + 1;
        assertFalse(this.lane.recover(elsewhere), "its market isn't loaded");
        assertEquals(1, this.lane.workingOrders());
    }

    @Test
    void ownershipHearsOfAnOrderBeforeItIsSentThenItsVenueIdThenItsEnd() {
        final List<String> heard = new ArrayList<>();
        this.lane.ownership(new AccountLane.Ownership() {
            @Override
            public void added(final OrderContext ctx) {
                heard.add("added " + correlationId(ctx) + " after "
                        + requests("POST").size() + " sends");
            }

            @Override
            public void venueIdLearned(final OrderContext ctx) {
                heard.add("venue id "
                        + new String(
                                ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII));
            }

            @Override
            public void ended(final OrderContext ctx) {
                heard.add("ended " + correlationId(ctx));
            }
        });
        this.server.script(request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        submit(1);
        runUntil(() -> requests("POST").size() == 1 && this.connections.inFlight() == 0);
        release(1);
        runUntil(() -> this.lane.workingOrders() == 0);

        assertEquals(List.of("added s3-1 after 0 sends", "venue id v-s3-1", "ended s3-1"), heard);
    }

    @Test
    void everyHeldOrderIsVisitedForRewritingTheRecord() {
        assertTrue(this.lane.recover(recorded(7, "v-a")));
        assertTrue(this.lane.recover(recorded(8, "v-b")));
        final List<Long> visited = new ArrayList<>();
        this.lane.forEachHeld(ctx -> visited.add(ctx.clientOidCounter));
        visited.sort(Long::compare);
        assertEquals(List.of(7L, 8L), visited);
    }

    /** An order as a restart's record has it: the test venue's correlation id, and the venue's id if known. */
    private static OrderContext recorded(final long counter, final String venueId) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = SLOT;
        ctx.clientOidCounter = counter;
        ctx.clientOidStrategyId = 1;
        ctx.exchangeId = TestLaneVenue.EXCHANGE_ID;
        ctx.securityId = TestLaneVenue.SECURITY_ID;
        ctx.originalQty = 1;
        ctx.leavesQty = 1;
        final byte[] id = ("s" + SLOT + "-" + counter).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
        ctx.correlationIdLength = id.length;
        if (venueId != null) {
            final byte[] venue = venueId.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(venue, 0, ctx.exchangeOrderIdBytes, 0, venue.length);
            ctx.exchangeOrderIdLength = venue.length;
        }
        return ctx;
    }

    @Test
    void anUnknownOrderFoundOnALaterLookCarriesOnAndCanBeCancelled() throws Exception {
        this.venue.resendsAreSafe = false;
        try (LaneHarness<String> lane = sweeping()) {
            lane.server.script(request -> switch (request.method()) {
                case "POST" -> Reply.ok("v-" + request.body()).after(400);
                    // The first look finds nothing it can be sure of; the sweep's finds it.
                case "GET" -> lane.requests("GET").size() == 1 ? Reply.status(503) : Reply.ok("v-s3-1");
                default -> Reply.ok("");
            });
            lane.submit(1);
            lane.runUntil(() -> lane.requests("GET").size() == 2 && lane.connections.inFlight() == 0, 3_000);
            lane.cancel(1);
            lane.runUntil(() -> lane.requests("DELETE").size() == 1);
            assertEquals("/orders/v-s3-1", lane.requests("DELETE").get(0).path());
            assertTrue(lane.notices.isEmpty());
        }
    }

    @Test
    void anUnknownOrderTheVenueNeverHadIsRejected() throws Exception {
        this.venue.resendsAreSafe = false;
        try (LaneHarness<String> lane = sweeping()) {
            lane.server.script(request -> switch (request.method()) {
                case "POST" -> Reply.ok("v-" + request.body()).after(400);
                case "GET" -> lane.requests("GET").size() == 1 ? Reply.status(503) : Reply.status(404);
                default -> Reply.ok("");
            });
            lane.submit(1);
            lane.runUntil(() -> !lane.notices.isEmpty(), 3_000);
            assertEquals(ExecType.REJECT, lane.notices.get(0).execType);
            assertTrue(lane.notices.get(0).endsRegistered, "dropped by the reader if the venue's events ended it");
            assertEquals(0, lane.lane.workingOrders());
        }
    }

    @Test
    void anUnknownOrderTheVenueCantLookUpIsGivenUpOn() throws Exception {
        this.venue.resendsAreSafe = false;
        this.venue.lookupsPossible = false;
        try (LaneHarness<String> lane = sweeping()) {
            lane.server.script(request -> Reply.ok("v-" + request.body()).after(400));
            lane.submit(1);
            final long start = System.nanoTime();
            lane.runUntil(() -> !lane.notices.isEmpty(), 3_000);
            assertEquals(ExecType.CANCEL, lane.notices.get(0).execType, "ended with the fills the reader has seen");
            assertTrue(lane.notices.get(0).endsRegistered);
            final long waited = System.nanoTime() - start;
            assertTrue(
                    waited >= TimeUnit.MILLISECONDS.toNanos(300) && waited < TimeUnit.MILLISECONDS.toNanos(550),
                    "given up at its own, shorter limit: " + TimeUnit.NANOSECONDS.toMillis(waited) + " ms");
            assertEquals(0, lane.lane.workingOrders());
            assertTrue(lane.requests("GET").isEmpty(), "never looked up");
        }
    }

    @Test
    void anUnknownOrderNeverFoundEitherWayIsGivenUpOnInTheEnd() throws Exception {
        this.venue.resendsAreSafe = false;
        try (LaneHarness<String> lane = sweeping()) {
            lane.server.script(request -> request.method().equals("POST")
                    ? Reply.ok("v-" + request.body()).after(400)
                    : Reply.status(503));
            lane.submit(1);
            lane.runUntil(() -> !lane.notices.isEmpty(), 3_000);
            assertEquals(ExecType.CANCEL, lane.notices.get(0).execType);
            assertTrue(lane.requests("GET").size() >= 3, "looked for again while it waited");
            assertEquals(0, lane.lane.workingOrders());
        }
    }

    private void submit(final long counter) {
        this.harness.submit(counter);
    }

    private void cancel(final long counter) {
        this.harness.cancel(counter);
    }

    private void amend(final long counter, final long price, final long size) {
        this.harness.amend(counter, price, size);
    }

    private void release(final long counter) {
        this.harness.release(counter);
    }

    private List<Request> requests(final String method) {
        return this.harness.requests(method);
    }

    private void runUntil(final BooleanSupplier done) {
        this.harness.runUntil(done);
    }

    private void runUntil(final BooleanSupplier done, final long timeoutMillis) {
        this.harness.runUntil(done, timeoutMillis);
    }

    private void runFor(final long millis) {
        this.harness.runFor(millis);
    }

    private static String correlationId(final OrderContext ctx) {
        return new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
    }
}
