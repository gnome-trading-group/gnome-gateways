package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.schemas.RejectReason;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The lane on a venue that takes submits and cancels several to a request, over real connections to a local server:
 * what goes together, an entry the venue refuses the batch for, a batch with no answer, and fair shares in batches.
 */
class AccountLaneBatchTest {

    private static final long A = 1;
    private static final long B = 2;

    private final TestLaneVenue venue = new TestLaneVenue();
    private LaneHarness<String> harness;

    @AfterEach
    void tearDown() {
        if (this.harness != null) {
            this.harness.close();
        }
    }

    @Test
    void queuedCreatesGoTwentyToARequest() throws Exception {
        this.harness = harness(20, 10);
        acceptEverything();
        for (int i = 1; i <= 40; i++) {
            this.harness.submitAs(A, i);
        }

        this.harness.runUntil(() -> answered(batches(), 2), 3_000);

        assertEquals(List.of(20, 20), batches().stream().map(r -> ids(r).size()).toList());
        assertTrue(this.harness.notices.isEmpty(), "all accepted");
        assertEquals(40, this.harness.lane.workingOrders());
    }

    @Test
    void aBatchRefusedForOneEntryRejectsOnlyThatOrderAndSendsTheRestAgain() throws Exception {
        this.harness = harness(20, 1_000);
        final int[] batchCount = {0};
        this.harness.server.script(request -> {
            if (request.path().equals("/batch") && ++batchCount[0] == 1) {
                return new Reply(400, "order[2]", 0);
            }
            return accept(request);
        });
        for (int i = 1; i <= 5; i++) {
            this.harness.submitAs(A, i);
        }

        this.harness.runUntil(() -> answered(batches(), 2), 3_000);

        assertEquals(1, this.harness.notices.size());
        assertEquals(3, this.harness.notices.get(0).clientOidCounter, "the third entry was the one refused");
        assertEquals(RejectReason.INVALID_PRICE, this.harness.notices.get(0).rejectReason);
        assertEquals(List.of("s1-1", "s1-2", "s1-4", "s1-5"), ids(batches().get(1)), "the rest, in their order");
        assertEquals(4, this.harness.lane.workingOrders());
    }

    @Test
    void aBatchWithNoAnswerIsSettledByLookingUpEachOrder() throws Exception {
        this.venue.resendsAreSafe = false;
        this.harness = harness(20, 1_000);
        this.harness.server.script(request -> request.path().equals("/batch")
                ? accept(request).after(800)
                : Reply.ok("v-" + request.path().substring("/orders/".length())));
        for (int i = 1; i <= 5; i++) {
            this.harness.submitAs(A, i);
        }

        this.harness.runUntil(
                () -> this.harness.requests("GET").size() == 5 && this.harness.connections.inFlight() == 0, 4_000);
        this.harness.runFor(100);

        assertEquals(1, batches().size(), "never sent again: the venue doesn't take a resend safely");
        assertTrue(this.harness.notices.isEmpty(), "each found, so each carries on");
        assertEquals(5, this.harness.lane.workingOrders());
    }

    @Test
    void cancelsGoTogether() throws Exception {
        this.harness = harness(20, 1_000);
        acceptEverything();
        for (int i = 1; i <= 5; i++) {
            this.harness.submitAs(A, i);
        }
        this.harness.runUntil(() -> answered(batches(), 1), 3_000);

        for (int i = 1; i <= 5; i++) {
            this.harness.cancelAs(A, i);
        }
        this.harness.runUntil(() -> answered(cancelBatches(), 1), 3_000);
        this.harness.runFor(100);

        assertEquals(1, cancelBatches().size());
        assertEquals(
                List.of("v-s1-1", "v-s1-2", "v-s1-3", "v-s1-4", "v-s1-5"),
                ids(cancelBatches().get(0)));
        assertTrue(this.harness.notices.isEmpty());
    }

    @Test
    void aBatchOfCancelsTheVenueFailedToAnswerIsSentAgain() throws Exception {
        this.harness = harness(20, 1_000);
        final int[] cancelCount = {0};
        this.harness.server.script(request ->
                request.path().equals("/batch/cancel") && ++cancelCount[0] == 1 ? Reply.status(503) : accept(request));
        for (int i = 1; i <= 3; i++) {
            this.harness.submitAs(A, i);
        }
        this.harness.runUntil(() -> answered(batches(), 1), 3_000);

        for (int i = 1; i <= 3; i++) {
            this.harness.cancelAs(A, i);
        }
        this.harness.runUntil(() -> answered(cancelBatches(), 2), 3_000);
        this.harness.runFor(100);

        assertEquals(2, cancelBatches().size());
        assertEquals(ids(cancelBatches().get(0)), ids(cancelBatches().get(1)));
        assertTrue(this.harness.notices.isEmpty(), "accepted the second time");
    }

    @Test
    void sessionsShareBatchesByTheirWeights() throws Exception {
        this.harness = harness(4, 20);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, 0);
        this.harness.session(B, 3, 0);
        for (int i = 1; i <= 16; i++) {
            this.harness.submitAs(A, i);
        }
        for (int i = 1; i <= 48; i++) {
            this.harness.submitAs(B, i);
        }

        this.harness.runUntil(() -> batches().size() >= 8, 5_000);

        final long fromA = batches().subList(0, 8).stream()
                .flatMap(request -> ids(request).stream())
                .filter(id -> id.startsWith("s1-"))
                .count();
        assertTrue(Math.abs(fromA - 8) <= 4, "a quarter of 32 to weight 1 of 4: " + fromA);
    }

    // ----- helpers -----

    private void acceptEverything() {
        this.harness.server.script(AccountLaneBatchTest::accept);
    }

    /** A batch create answered with an id for each entry; anything else with a plain 200. */
    private static Reply accept(final Request request) {
        if (!request.path().equals("/batch")) {
            return Reply.ok("");
        }
        return Reply.ok(
                Arrays.stream(request.body().split(",")).map(id -> "v-" + id).collect(Collectors.joining(",")));
    }

    private List<Request> batches() {
        return this.harness.server.requests().stream()
                .filter(request -> request.path().equals("/batch"))
                .toList();
    }

    private List<Request> cancelBatches() {
        return this.harness.server.requests().stream()
                .filter(request -> request.path().equals("/batch/cancel"))
                .toList();
    }

    private boolean answered(final List<Request> requests, final int count) {
        return requests.size() >= count && this.harness.connections.inFlight() == 0;
    }

    private static List<String> ids(final Request request) {
        return List.of(request.body().split(","));
    }

    /** Batches of at most {@code maxBatch}; one bucket at {@code perSecond}, burst 10, two kept for cancels. */
    private LaneHarness<String> harness(final int maxBatch, final double perSecond) throws Exception {
        this.venue.maxBatch = maxBatch;
        return new LaneHarness<>(
                this.venue,
                new AccountLane.Timing(
                        TimeUnit.MILLISECONDS.toNanos(300),
                        TimeUnit.MILLISECONDS.toNanos(500),
                        TimeUnit.SECONDS.toNanos(10),
                        AccountLane.Timing.defaults().sweepIntervalNanos(),
                        AccountLane.Timing.defaults().giveUpNanos(),
                        AccountLane.Timing.defaults().giveUpUnlookableNanos()),
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", perSecond, 10, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(
                                        HTTPMethod.POST, "/batch/cancel", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                3,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
    }
}
