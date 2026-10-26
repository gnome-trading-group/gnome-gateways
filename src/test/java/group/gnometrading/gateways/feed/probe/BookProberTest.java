package group.gnometrading.gateways.feed.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.gateways.feed.RecordingHost;
import group.gnometrading.gateways.feed.StreamOutput;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookProberTest {

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    private long now = TimeUnit.DAYS.toNanos(1);
    private final List<String> host = new ArrayList<>();
    private final SequencedRingBuffer<Mbp10Schema> ring =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
    private final Subject subject = new Subject("M", 64, new StreamOutput(1, Projection.IDENTITY, ring));
    private final BookProber prober =
            new BookProber(new NullLogger(), () -> now, new BookProber.Settings(15 * MINUTE, MINUTE, 20));
    // What the venue's REST API answers: the market's book, unless told otherwise.
    private long venueBid = 40;
    private long venueAsk = 10;
    private int fetches;

    @BeforeEach
    void setUp() {
        ring.createPoller((seq, templateId, buffer, length) -> {});
        subject.attach(new RecordingHost(host));
        subject.beginSnapshot();
        subject.level(Side.Bid, 40, 10, 1);
        subject.level(Side.Ask, 50, 10, 1);
        subject.endSnapshot(1, now, 1);
    }

    /** A venue answering with {@code levels}, counting each fetch. */
    private BookProbeClient venue(final java.util.function.Consumer<Book> levels) {
        return new BookProbeClient() {
            @Override
            public Request prepare(final Subject s) {
                return new Request("/book/" + s.key(), null);
            }

            @Override
            public boolean fetch(final Request request, final Book into) {
                fetches++;
                levels.accept(into);
                return true;
            }
        };
    }

    private final BookProbeClient client = venue(into -> {
        into.add(Side.Bid, venueBid, 10, 1);
        into.add(Side.Ask, 50, venueAsk, 1);
    });

    private void addDueNow() {
        prober.add(subject, client, RateBudget.UNLIMITED);
        now += 15 * MINUTE;
    }

    @Test
    void aMatchingBookIsVerified() {
        addDueNow();
        prober.doWork();

        assertEquals(1, fetches);
        assertEquals(now, subject.lastVerifiedNanos());
        assertTrue(host.isEmpty());
    }

    /** A fetch that disagrees, then the settle that decides whether it counts. */
    private void fetchAndSettle() {
        prober.doWork();
        now += BookProber.SETTLE_NANOS;
        prober.doWork();
    }

    @Test
    void aMismatchIsFetchedAgainAndASecondOneMakesTheMarketStale() {
        venueBid = 41;
        addDueNow();
        fetchAndSettle();
        assertTrue(host.isEmpty(), "one mismatch may be the book moving between reads");

        now += BookProber.REFETCH_DELAY_NANOS;
        fetchAndSettle();
        assertEquals(List.of("stale M:PROBE_MISMATCH"), host);
        assertEquals(1, prober.mismatches());
    }

    @Test
    void aMismatchThatGoesAwayIsNotAGap() {
        venueBid = 41;
        addDueNow();
        fetchAndSettle();
        venueBid = 40;
        now += BookProber.REFETCH_DELAY_NANOS;
        prober.doWork();

        assertTrue(host.isEmpty());
        assertEquals(now, subject.lastVerifiedNanos());
    }

    @Test
    void aBookThatMovedAroundTheFetchIsNotJudged() {
        venueBid = 41;
        addDueNow();
        for (int round = 0; round < 5; round++) {
            prober.doWork();
            // The market moves while the fetch settles: the snapshot is from another moment.
            subject.set(Side.Ask, 50, 10 + round, 1);
            subject.endUpdate(1, now, 2);
            now += BookProber.SETTLE_NANOS;
            prober.doWork();
            now += BookProber.REFETCH_DELAY_NANOS;
        }

        assertTrue(host.isEmpty(), "a busy book is never judged against a snapshot from another moment");
        assertEquals(0, prober.mismatches());
    }

    @Test
    void aQuietMarketFoundRightIsLeftToItsRegularProbe() {
        // Suspect after a minute's quiet whatever its usual gap, so the test needn't build a history of gaps.
        BookProber prober =
                new BookProber(new NullLogger(), () -> now, new BookProber.Settings(15 * MINUTE, MINUTE, 0));
        prober.add(subject, client, RateBudget.UNLIMITED);
        now += 15 * MINUTE;
        prober.doWork();
        assertEquals(1, fetches, "quiet since it was snapshotted: suspect, probed at once");

        now += 10 * MINUTE;
        prober.doWork();
        assertEquals(1, fetches, "found right and silent since: no news about it");

        // Heard from again, then quiet longer than a minute: suspect once more.
        subject.set(Side.Ask, 50, 11, 1);
        subject.endUpdate(1, now, 2);
        venueAsk = 11;
        now += 2 * MINUTE;
        prober.doWork();
        assertEquals(2, fetches);
        assertEquals(SubjectState.LIVE, subject.state(), "the probe found it right");
    }

    @Test
    void probesWaitForTheSourcesBudget() {
        prober.add(subject, client, RateBudget.named("TEST-BUDGET-ZERO-" + System.nanoTime(), 1));
        now += 15 * MINUTE;
        prober.doWork();
        prober.doWork();
        assertTrue(fetches <= 1);
    }

    @Test
    void aMarketNotTrustedIsNotProbed() {
        addDueNow();
        subject.transition(SubjectState.LIVE, SubjectState.STALE);
        prober.doWork();
        assertEquals(0, fetches);
    }

    @Test
    void theComparisonUsesTheTopFiveLevelsOnly() {
        Book deep = new Book(16);
        BookProbeClient deepClient = venue(into -> {
            into.add(Side.Bid, 40, 10, 1);
            for (int i = 1; i <= 7; i++) {
                into.add(Side.Bid, 40 - i, 1, 1);
            }
            into.add(Side.Ask, 50, 10, 1);
        });
        prober.add(subject, deepClient, RateBudget.UNLIMITED);
        now += 15 * MINUTE;
        fetchAndSettle();
        now += BookProber.REFETCH_DELAY_NANOS;
        fetchAndSettle();

        assertEquals(List.of("stale M:PROBE_MISMATCH"), host, "the venue has levels the book doesn't");
        assertEquals(0, deep.bids().depth());
    }
}
