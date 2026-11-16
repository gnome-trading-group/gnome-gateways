package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Each session request's timing as the lane tells it, over real connections to a local server. */
class AccountLaneTimingTest {

    private static final long A = 1;
    private static final long RESPONSE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /** A copy of one {@link RequestTiming}, which the lane reuses. */
    private record Timed(
            long slot,
            long counter,
            RequestClass requestClass,
            int outcome,
            long ingress,
            long taken,
            long decided,
            long wire,
            long answered,
            long slack,
            int venueCode,
            int venueMicros) {}

    private final TestLaneVenue venue = new TestLaneVenue();
    private final List<Timed> timings = new CopyOnWriteArrayList<>();
    private LaneHarness<String> harness;

    @AfterEach
    void tearDown() {
        if (this.harness != null) {
            this.harness.close();
        }
    }

    @Test
    void anAnsweredOrderAndItsCancelAreEachTimedInOrder() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(request -> request.method().equals("POST")
                ? Reply.ok("v-" + request.body()).after(20)
                : Reply.ok(""));
        this.harness.session(A, 1, 0);
        this.harness.submitStamped(A, 1, System.nanoTime());
        this.harness.runUntil(() -> this.timings.size() == 1, 3_000);
        this.harness.command(LaneCommand.CANCEL, 1, command -> {
            command.sessionSlot = A;
            command.enqueuedNanos = System.nanoTime();
        });

        this.harness.runUntil(() -> this.timings.size() == 2, 3_000);

        final Timed submit = this.timings.get(0);
        assertEquals(RequestClass.NEW, submit.requestClass());
        assertEquals(RequestTiming.ANSWERED, submit.outcome());
        assertEquals(200, submit.venueCode());
        assertEquals(RequestTiming.NO_VENUE_PROCESSING, submit.venueMicros());
        assertSteps(submit);
        assertTrue(
                submit.answered() - submit.wire() >= TimeUnit.MILLISECONDS.toNanos(20), "the venue's 20 ms: " + submit);

        final Timed cancel = this.timings.get(1);
        assertEquals(A, cancel.slot());
        assertEquals(1, cancel.counter());
        assertEquals(RequestClass.CANCEL, cancel.requestClass());
        assertEquals(RequestTiming.ANSWERED, cancel.outcome());
        assertSteps(cancel);
        assertTrue(cancel.ingress() > submit.answered(), "the cancel's own stamps, not the submit's: " + cancel);
    }

    @Test
    void aVenueRefusalIsTimedWithItsCode() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(request -> Reply.status(400));
        this.harness.session(A, 1, 0);
        this.harness.submitStamped(A, 1, System.nanoTime());

        this.harness.runUntil(() -> !this.timings.isEmpty(), 3_000);

        final Timed submit = this.timings.get(0);
        assertEquals(RequestTiming.REJECTED, submit.outcome());
        assertEquals(400, submit.venueCode());
        assertSteps(submit);
    }

    @Test
    void anOrderHeldPastItsWaitIsTimedNotSent() throws Exception {
        this.harness = harness(1);
        this.harness.session(A, 1, TimeUnit.MILLISECONDS.toNanos(50));
        this.harness.submitStamped(A, 1, System.nanoTime());

        this.harness.runUntil(() -> !this.timings.isEmpty(), 3_000);

        final Timed refused = this.timings.get(0);
        assertEquals(RequestClass.NEW, refused.requestClass());
        assertEquals(RequestTiming.NOT_SENT, refused.outcome());
        assertTrue(refused.decided() - refused.taken() >= TimeUnit.MILLISECONDS.toNanos(50), "held its wait first");
        assertEquals(0, refused.wire());
        assertEquals(0, refused.answered());
        assertEquals(0, refused.venueCode());
    }

    @Test
    void aRequestPastItsDeadlineIsTimedWithNoAnswer() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(request -> Reply.ok("v-" + request.body()).after(1_500));
        this.harness.session(A, 1, 0);
        this.harness.submitStamped(A, 1, System.nanoTime());

        this.harness.runUntil(() -> !this.timings.isEmpty(), 3_000);

        final Timed submit = this.timings.get(0);
        assertEquals(RequestTiming.NO_ANSWER, submit.outcome());
        assertTrue(submit.wire() > 0);
        assertEquals(0, submit.answered(), "no answer, no answer time");
        assertEquals(0, submit.slack());
        assertEquals(0, submit.venueCode());
    }

    @Test
    void anAnswerToARequestWrittenLongAfterTheLaneLastLookedIsNoLaterThanItsWait() throws Exception {
        this.harness = harness(1_000);
        this.harness.server.script(request -> Reply.ok("v-" + request.body()));
        // A slow pass: the lane looked for answers, then took long deciding and writing the order, and the answer was
        // back before it looked again.
        this.harness.lane.admissions(new AccountLane.Admissions() {
            @Override
            public void decided(
                    final long sessionSlot,
                    final long clientOidCounter,
                    final RequestClass type,
                    final AdmissionAction action,
                    final long queuedNanos,
                    final long tokensLeft) {
                pause();
            }

            @Override
            public void sent(final RequestClass type, final long writeNanos) {
                pause();
            }

            @Override
            public void timed(final RequestTiming timing) {
                record(timing);
            }
        });
        this.harness.session(A, 1, 0);
        this.harness.submitStamped(A, 1, System.nanoTime());
        this.harness.runUntil(() -> this.timings.size() == 1, 3_000);

        final Timed submit = this.timings.get(0);
        assertEquals(RequestTiming.ANSWERED, submit.outcome());
        assertSteps(submit);
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertSteps(final Timed timed) {
        assertTrue(timed.ingress() > 0, "stamped by ingress: " + timed);
        assertTrue(timed.ingress() <= timed.taken(), "taken after ingress: " + timed);
        assertTrue(timed.taken() <= timed.decided(), "decided after taken: " + timed);
        assertTrue(timed.decided() <= timed.wire(), "written after decided: " + timed);
        assertTrue(timed.wire() < timed.answered(), "answered after written: " + timed);
        assertTrue(
                timed.slack() >= 0 && timed.slack() <= timed.answered() - timed.wire(),
                "the slack is within the wait for the answer: " + timed);
    }

    private LaneHarness<String> harness(final double perSecond) throws Exception {
        final LaneHarness<String> made = new LaneHarness<>(
                this.venue,
                new AccountLane.Timing(
                        TimeUnit.MILLISECONDS.toNanos(300),
                        RESPONSE_TIMEOUT_NANOS,
                        TimeUnit.SECONDS.toNanos(10),
                        AccountLane.Timing.defaults().sweepIntervalNanos(),
                        AccountLane.Timing.defaults().giveUpNanos(),
                        AccountLane.Timing.defaults().giveUpUnlookableNanos()),
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", perSecond, 10, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                3,
                TestLaneVenue.EXCHANGE_ID,
                TestLaneVenue.SECURITY_ID);
        made.lane.admissions(new AccountLane.Admissions() {
            @Override
            public void timed(final RequestTiming timing) {
                record(timing);
            }
        });
        return made;
    }

    private void record(final RequestTiming timing) {
        this.timings.add(new Timed(
                timing.sessionSlot,
                timing.clientOidCounter,
                timing.requestClass,
                timing.outcome,
                timing.ingressNanos,
                timing.takenNanos,
                timing.decidedNanos,
                timing.wireSentNanos,
                timing.answeredNanos,
                timing.answerSlackNanos,
                timing.venueCode,
                timing.venueProcessingMicros));
    }
}
