package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.admission.FairShareTrace;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How the lane shares an account's budget between sessions, over real connections to a local server: weighted turns,
 * idle share lent, each session's own wait, cancels and lookups ahead of quoting, and cancels the venue failed to
 * answer sent again.
 */
class AccountLaneFairShareTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long LONG_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final TestLaneVenue venue = new TestLaneVenue();
    private LaneHarness<String> harness;

    @AfterEach
    void tearDown() {
        if (this.harness != null) {
            this.harness.close();
        }
    }

    @Test
    void sessionsShareASaturatedBudgetByTheirWeights() throws Exception {
        this.harness = harness(40);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, 0);
        this.harness.session(B, 3, 0);
        for (int i = 1; i <= 24; i++) {
            this.harness.submitAs(A, i);
        }
        for (int i = 1; i <= 36; i++) {
            this.harness.submitAs(B, i);
        }

        this.harness.runUntil(() -> posts().size() >= 32, 5_000);

        final List<String> first =
                posts().subList(0, 32).stream().map(Request::body).toList();
        final long fromA = first.stream().filter(body -> body.startsWith("s1-")).count();
        assertTrue(Math.abs(fromA - 8) <= 1, "a quarter to weight 1 of 4: " + first);
    }

    @Test
    void anIdleSessionsShareIsLentAndItSavesNoneForLater() throws Exception {
        this.harness = harness(40);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, 0);
        this.harness.session(B, 3, 0);
        for (int i = 1; i <= 40; i++) {
            this.harness.submitAs(A, i);
        }

        final long start = System.nanoTime();
        this.harness.runUntil(() -> posts().size() >= 10, 5_000);
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // At 40 a second from an empty bucket: ~75 ms for the first, then one every 25 ms. A quarter share would take
        // a second.
        assertTrue(tookMillis < 600, "alone, it had the whole budget: " + tookMillis + " ms");

        for (int i = 1; i <= 30; i++) {
            this.harness.submitAs(B, i);
        }
        final int before = posts().size();
        this.harness.runUntil(() -> posts().size() >= before + 20, 5_000);
        final long fromB = posts().subList(before, before + 20).stream()
                .filter(request -> request.body().startsWith("s2-"))
                .count();
        assertTrue(Math.abs(fromB - 15) <= 2, "the newcomer gets its weight, no more: " + fromB + " of 20");
    }

    @Test
    void eachSessionsOrdersWaitAsLongAsItAllows() throws Exception {
        this.harness = harness(1);
        this.harness.session(A, 1, TimeUnit.MILLISECONDS.toNanos(50));
        this.harness.session(B, 1, TimeUnit.SECONDS.toNanos(2));
        this.harness.submitAs(A, 1);
        this.harness.submitAs(B, 1);

        this.harness.runFor(300);

        assertEquals(1, this.harness.notices.size(), "only the order with the short wait was refused");
        final OrderContext refused = this.harness.notices.get(0);
        assertEquals(A, refused.sessionSlot);
        assertEquals(RejectReason.RATE_LIMITED, refused.rejectReason);
        assertEquals(1, this.harness.lane.queuedRequests(), "the other still waits");
        assertTrue(posts().isEmpty());
    }

    @Test
    void aCancelGoesAheadOfOtherSessionsQueuedOrders() throws Exception {
        this.harness = harness(20);
        this.harness.server.script(
                request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.ok(""));
        this.harness.submitAs(A, 1);
        this.harness.runUntil(() -> posts().size() == 1 && this.harness.connections.inFlight() == 0, 3_000);
        for (int i = 1; i <= 20; i++) {
            this.harness.submitAs(B, i);
        }
        this.harness.runFor(50);
        this.harness.cancelAs(A, 1);

        this.harness.runUntil(() -> !this.harness.requests("DELETE").isEmpty(), 3_000);

        assertTrue(posts().size() < 21, "sent while new orders still waited: " + posts().size() + " of 21 out");
    }

    @Test
    void oneSessionsAmendsDontCrowdOutAnothersNewOrders() throws Exception {
        this.harness = harness(40);
        this.harness.server.script(
                request -> request.method().equals("POST") && request.path().equals("/orders")
                        ? Reply.ok("v-" + request.body())
                        : Reply.ok(""));
        for (int i = 1; i <= 10; i++) {
            this.harness.submitAs(A, i);
        }
        this.harness.runUntil(() -> posts().size() == 10 && this.harness.connections.inFlight() == 0, 3_000);
        final int before = this.harness.server.requests().size();

        for (int i = 1; i <= 10; i++) {
            this.harness.amendAs(A, i, 410_000_000L);
            this.harness.submitAs(B, i);
        }
        this.harness.runUntil(() -> this.harness.server.requests().size() >= before + 10, 3_000);

        final long amends = this.harness.server.requests().subList(before, before + 10).stream()
                .filter(request -> request.path().endsWith("/amend"))
                .count();
        assertTrue(Math.abs(amends - 5) <= 1, "equal weights, equal turns: " + amends + " amends of 10");
    }

    @Test
    void aSessionBackWithinASecondKeepsItsPlace() throws Exception {
        final List<Request> after = awayThenBack(500);
        // One of B's may already have been on its way.
        assertTrue(
                after.stream().filter(post -> post.body().startsWith("s1-")).count() >= 3,
                "A, owed what B sent while it was away, goes first: " + bodies(after));
    }

    @Test
    void aSessionBackAfterLongerComesBackLevel() throws Exception {
        final List<Request> after = awayThenBack(1_300);
        assertTrue(
                after.stream().anyMatch(post -> post.body().startsWith("s2-")),
                "A forfeited what it left unused: " + bodies(after));
    }

    /**
     * A and B at equal weights, a send every 100 ms: A sends twice and has nothing more, B sends alone for {@code
     * awayMillis}, then A queues again. The first four sends after that.
     */
    private List<Request> awayThenBack(final long awayMillis) throws Exception {
        this.harness = harness(10, 3);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, LONG_WAIT_NANOS);
        this.harness.session(B, 1, LONG_WAIT_NANOS);
        for (int i = 1; i <= 40; i++) {
            this.harness.submitAs(B, i);
        }
        this.harness.submitAs(A, 1);
        this.harness.submitAs(A, 2);
        this.harness.runUntil(
                () -> posts().stream()
                                .filter(post -> post.body().startsWith("s1-"))
                                .count()
                        == 2,
                3_000);
        this.harness.runFor(awayMillis);
        final int before = posts().size();
        for (int i = 3; i <= 12; i++) {
            this.harness.submitAs(A, i);
        }
        this.harness.runUntil(() -> posts().size() >= before + 4, 3_000);
        return posts().subList(before, before + 4);
    }

    private static List<String> bodies(final List<Request> posts) {
        return posts.stream().map(Request::body).toList();
    }

    @Test
    void aTraceRecordsEachSessionStartingAndEachPickAtItsWeight() throws Exception {
        this.harness = harness(40);
        final FairShareTrace trace = this.harness.lane.traceFairShare(1_000);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, 0);
        this.harness.session(B, 3, 0);
        this.harness.submitAs(A, 1);
        this.harness.submitAs(B, 1);

        this.harness.runUntil(() -> posts().size() >= 2, 5_000);

        final Path file = Files.createTempFile("fair-share", ".tsv");
        trace.writeTo(file);
        final List<String> lines = Files.readAllLines(file);
        assertEquals("micros\tkind\tslot\tweight\tserved\tvalue", lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.matches("\\d+\tactivated\t" + B + "\t3\t.*")), "B began");
        assertTrue(
                lines.stream().anyMatch(line -> line.matches("\\d+\tpicked\t" + A + "\t1\t\\d+\t1000")),
                "A charged a whole token: " + lines);
        assertTrue(
                lines.stream().anyMatch(line -> line.matches("\\d+\tpicked\t" + B + "\t3\t\\d+\t333")),
                "B a third of one at weight 3");
    }

    @Test
    void eachAnswersRoundTripIsToldAndEveryConnectionInUseOnce() throws Exception {
        this.harness = harness(10_000);
        this.harness.server.script(request -> Reply.ok("v-" + request.body()).after(100));
        final List<Long> roundTrips = new ArrayList<>();
        final int[] busy = {0};
        this.harness.lane.admissions(new AccountLane.Admissions() {
            @Override
            public void answered(final RequestClass type, final long roundTripNanos, final boolean whole) {
                assertEquals(RequestClass.NEW, type);
                assertTrue(whole);
                roundTrips.add(roundTripNanos);
            }

            @Override
            public void connectionsBusy() {
                busy[0]++;
            }
        });
        this.harness.session(A, 1, 0);
        final int orders = LaneHarness.CONNECTIONS + 1;
        for (int i = 1; i <= orders; i++) {
            this.harness.submitAs(A, i);
        }

        this.harness.runUntil(() -> roundTrips.size() == orders, 5_000);

        assertEquals(1, busy[0], "one waited for a connection, told once");
        assertTrue(
                roundTrips.stream().allMatch(nanos -> nanos >= TimeUnit.MILLISECONDS.toNanos(100)),
                "each at least the venue's 100 ms: " + roundTrips);
    }

    @Test
    void aCancelTheVenueFailedToAnswerIsSentAgain() throws Exception {
        this.harness = harness(1_000);
        final int[] deletes = {0};
        this.harness.server.script(request -> {
            if (request.method().equals("POST")) {
                return Reply.ok("v-" + request.body());
            }
            return ++deletes[0] == 1 ? Reply.status(503) : Reply.ok("");
        });
        acceptedOrder();

        this.harness.cancelAs(A, 1);
        this.harness.runUntil(
                () -> this.harness.requests("DELETE").size() == 2 && this.harness.connections.inFlight() == 0, 3_000);
        this.harness.runFor(50);

        assertTrue(this.harness.notices.isEmpty(), "accepted the second time, so nothing to report");
        final List<Request> sent = this.harness.requests("DELETE");
        final long gapMillis = TimeUnit.NANOSECONDS.toMillis(
                sent.get(1).receivedNanos() - sent.get(0).receivedNanos());
        assertTrue(gapMillis >= 150, "after a backoff: " + gapMillis + " ms");
    }

    @Test
    void aCancelTheVenueKeepsFailingIsRefusedAfterItsRetries() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(
                request -> request.method().equals("POST") ? Reply.ok("v-" + request.body()) : Reply.status(503));
        acceptedOrder();

        this.harness.cancelAs(A, 1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty(), 4_000);
        this.harness.runFor(300);

        assertEquals(
                1 + AccountLane.MAX_CANCEL_RETRIES,
                this.harness.requests("DELETE").size());
        assertEquals(1, this.harness.notices.size(), "one answer to the one cancel");
    }

    @Test
    void anAccountServesAtMostItsSessionsUntilOneEnds() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(request -> Reply.status(400));
        for (int slot = 100; slot < 100 + AccountLane.MAX_SESSIONS; slot++) {
            this.harness.session(slot, 1, 0);
            if (slot % 200 == 0) {
                this.harness.runFor(20);
            }
        }
        this.harness.runFor(50);

        this.harness.submitAs(5_000, 1);
        this.harness.runUntil(() -> !this.harness.notices.isEmpty(), 2_000);
        assertEquals(RejectReason.SESSION_CAP, this.harness.notices.get(0).rejectReason);
        assertTrue(posts().isEmpty());

        this.harness.sessionEnded(100);
        this.harness.submitAs(5_001, 1);
        this.harness.runUntil(() -> posts().size() == 1, 2_000);
    }

    @Test
    void everyDecisionIsTold() throws Exception {
        this.harness = harness(20);
        final List<String> told = new ArrayList<>();
        this.harness.lane.admissions(new AccountLane.Admissions() {
            @Override
            public void decided(
                    final long sessionSlot,
                    final long clientOidCounter,
                    final RequestClass type,
                    final AdmissionAction action,
                    final long queuedNanos,
                    final long tokensLeft) {
                told.add(clientOidCounter + " " + type + " " + action + (queuedNanos > 0 ? " waited" : ""));
            }
        });
        this.harness.server.script(request -> Reply.ok("v-" + request.body()));
        this.harness.submitAs(A, 1);
        this.harness.submitAs(A, 2);
        this.harness.cancelAs(A, 2);

        this.harness.runUntil(() -> posts().size() == 1 && this.harness.connections.inFlight() == 0, 3_000);

        assertEquals(List.of("2 NEW DROPPED_CONFLATED waited", "1 NEW HELD waited", "1 NEW SENT waited"), told);
    }

    // ----- helpers -----

    private void acceptedOrder() {
        this.harness.submitAs(A, 1);
        this.harness.runUntil(() -> posts().size() == 1 && this.harness.connections.inFlight() == 0, 3_000);
    }

    private List<Request> posts() {
        return this.harness.requests("POST").stream()
                .filter(request -> request.path().equals("/orders"))
                .toList();
    }

    /** One bucket at {@code perSecond}, burst 10, two tokens kept for cancels; it starts empty. */
    private LaneHarness<String> harness(final double perSecond) throws Exception {
        return harness(perSecond, 10);
    }

    private LaneHarness<String> harness(final double perSecond, final long burst) throws Exception {
        return new LaneHarness<>(
                this.venue,
                new AccountLane.Timing(
                        TimeUnit.MILLISECONDS.toNanos(300),
                        TimeUnit.MILLISECONDS.toNanos(500),
                        LONG_WAIT_NANOS,
                        AccountLane.Timing.defaults().sweepIntervalNanos(),
                        AccountLane.Timing.defaults().giveUpNanos(),
                        AccountLane.Timing.defaults().giveUpUnlookableNanos()),
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", perSecond, burst, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(
                                        HTTPMethod.POST, "/orders/", "/amend", "all", 1, RequestClass.AMEND),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                3,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
    }
}
