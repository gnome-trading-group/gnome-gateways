package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectionGroupTest {

    private static final long HOUR = TimeUnit.HOURS.toNanos(1);

    private final FakeVenue.Opener opener = new FakeVenue.Opener();
    private final List<FakeVenue> venues = opener.venues;
    private final List<FeedConnection<FakeVenue>> opened = opener.opened;
    // Rolls give a replacement two seconds to sync, so a failed one is quick to see but a loaded machine isn't one.
    private final ConnectionGroup<FakeVenue> group = new ConnectionGroup<>(
            "TEST",
            2,
            opener,
            FeedListener.NONE,
            new NullLogger(),
            TimeUnit.SECONDS.toNanos(2),
            ConnectionGroup.HANDOVER_TIMEOUT_NANOS);

    private final List<VenueTestKit.Stream> streams = new ArrayList<>();

    @AfterEach
    void close() {
        group.closeAll();
    }

    private Subject market(final String key) {
        VenueTestKit.Stream stream = new VenueTestKit.Stream();
        streams.add(stream);
        return new Subject(key, 16, new StreamOutput(streams.size(), Projection.IDENTITY, stream.ring));
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    @Test
    void marketsArePackedOntoTheFullestConnectionWithRoom() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        Subject c = market("C");
        group.add(a, 0);
        group.add(b, 0);
        group.add(c, 0);

        assertEquals(2, group.connections().size());
        assertSame(a.host(), b.host());
        assertNotSame(a.host(), c.host());
        await(() -> a.state() == SubjectState.LIVE && c.state() == SubjectState.LIVE);
    }

    @Test
    void aRollMovesEveryMarketToAFreshConnectionWithoutAGap() throws Exception {
        Subject a = market("A");
        Subject b = market("B");
        group.add(a, 0);
        group.add(b, 0);
        await(() -> a.state() == SubjectState.LIVE && b.state() == SubjectState.LIVE);
        FeedConnection<FakeVenue> first = opened.get(0);

        venues.get(0).bid = 41;
        group.tick(13 * HOUR);

        assertEquals(1, group.connections().size());
        FeedConnection<FakeVenue> second = group.connections().get(0);
        assertNotSame(first, second);
        assertSame(second, a.host());
        assertSame(second, b.host());
        assertTrue(venues.get(0).closed, "the old connection is closed");
        assertEquals(SubjectState.LIVE, a.state());

        List<Mbp10Schema> records = streams.get(0).drain();
        Mbp10Schema adopted = records.get(records.size() - 1);
        assertTrue(adopted.decoder.flags().snapshot());
        assertFalse(records.stream().anyMatch(r -> r.decoder.flags().badData()), "no record was ever flagged");

        venues.get(1).frames.add("U A 45");
        await(() -> a.book().bids().depth() > 0 && a.book().bids().price(0) == 45);
    }

    @Test
    void aConnectionLeftEmptyClosesAfterFiveMinutes() throws Exception {
        Subject a = market("A");
        group.add(a, 0);
        group.remove(a, HOUR);

        group.tick(HOUR + ConnectionGroup.EMPTY_CLOSE_NANOS - 1);
        assertEquals(1, group.connections().size());
        group.tick(HOUR + ConnectionGroup.EMPTY_CLOSE_NANOS);
        assertEquals(0, group.connections().size());
    }

    @Test
    void aReplacementThatNeverSyncsLeavesTheOldConnectionUntouched() throws Exception {
        Subject a = market("A");
        group.add(a, 0);
        await(() -> a.state() == SubjectState.LIVE);
        FeedConnection<FakeVenue> first = opened.get(0);

        opener.silent = true;
        group.tick(13 * HOUR);

        assertEquals(List.of(first), group.connections());
        assertSame(first, a.host());
        assertEquals(SubjectState.LIVE, a.state());
        assertTrue(venues.get(1).closed, "the replacement is closed");
        assertFalse(
                streams.get(0).drain().stream().anyMatch(r -> r.decoder.flags().badData()));
        venues.get(0).frames.add("U A 45");
        await(() -> a.book().bids().price(0) == 45);

        group.tick(13 * HOUR + 1);
        assertEquals(2, opener.attempts, "not tried again on the next tick");
        opener.silent = false;
        group.tick(13 * HOUR + ConnectionGroup.ROLL_RETRY_NANOS);
        assertEquals(3, opener.attempts);
        assertNotSame(first, a.host(), "rolled once the venue answers");
    }

    @Test
    void aReplacementThatCantOpenIsRetriedLaterNotEveryTick() throws Exception {
        Subject a = market("A");
        group.add(a, 0);
        await(() -> a.state() == SubjectState.LIVE);

        opener.failNext = true;
        group.tick(13 * HOUR);
        group.tick(13 * HOUR + 1);
        assertEquals(2, opener.attempts);
        assertEquals(1, group.connections().size());
        assertEquals(SubjectState.LIVE, a.state());
    }
}
