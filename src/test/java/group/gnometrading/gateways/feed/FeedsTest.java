package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeedsTest {

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    private static final long DOLLAR = Statics.PRICE_SCALING_FACTOR;

    private final FakeVenue.Opener opener = new FakeVenue.Opener();
    private final Feeds feeds = new Feeds(new NullLogger(), null);
    private final VenueTestKit.Stream yes = new VenueTestKit.Stream();
    private final VenueTestKit.Stream no = new VenueTestKit.Stream();

    FeedsTest() {
        // "T:yes" and "T:no" are two listings of market T, the NO one seeing its complement.
        feeds.addSource(
                new Feeds.Source(
                        "TEST",
                        id -> new SubjectRef(
                                id.substring(0, id.indexOf(':')),
                                id.endsWith(":no") ? Projection.COMPLEMENT : Projection.IDENTITY),
                        16,
                        null,
                        RateBudget.UNLIMITED),
                10,
                opener);
    }

    @AfterEach
    void close() {
        feeds.closeAll();
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    private static List<Mbp10Schema> records(final VenueTestKit.Stream stream) {
        try {
            return stream.drain();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mbp10Schema lastOf(final VenueTestKit.Stream stream) {
        List<Mbp10Schema> records = records(stream);
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    @Test
    void aMarketsListingsShareOneSubscriptionEachSeeingItsOwnSide() throws Exception {
        feeds.addStream(1, "TEST", "T:yes", yes.ring, 0);
        feeds.addStream(2, "TEST", "T:no", no.ring, 0);
        Subject market = feeds.subject(1);
        assertSame(market, feeds.subject(2));
        await(() -> market.state() == SubjectState.LIVE);

        opener.venues.get(0).frames.add("U T " + DOLLAR * 40 / 100);
        await(() -> market.book().bids().price(0) == DOLLAR * 40 / 100);
        await(() -> lastOf(no) != null && lastOf(no).decoder.askPrice0() == DOLLAR * 60 / 100);
        assertEquals(List.of("T"), opener.venues.get(0).subscribed);
    }

    @Test
    void aStreamJoiningALiveMarketGetsItsBook() throws Exception {
        feeds.addStream(1, "TEST", "T:yes", yes.ring, 0);
        Subject market = feeds.subject(1);
        await(() -> market.state() == SubjectState.LIVE);

        feeds.addStream(2, "TEST", "T:no", no.ring, 0);
        await(() -> !records(no).isEmpty());
        assertTrue(records(no).get(0).decoder.flags().snapshot());
    }

    @Test
    void theMarketIsDroppedOnlyWithItsLastStream() throws Exception {
        feeds.addStream(1, "TEST", "T:yes", yes.ring, 0);
        feeds.addStream(2, "TEST", "T:no", no.ring, 0);
        ConnectionGroup<?> group = feeds.groups().get(0);

        feeds.removeStream(1, MINUTE);
        assertEquals(1, group.size());
        feeds.removeStream(2, MINUTE);
        assertEquals(0, group.size());
        assertNull(feeds.subject(2));
    }

    @Test
    void aQuarantinedMarketIsRetriedAfterFiveMinutes() throws Exception {
        feeds.addStream(1, "TEST", "T:yes", yes.ring, 0);
        Subject market = feeds.subject(1);
        await(() -> market.state() == SubjectState.LIVE);
        for (int i = 0; i < 3; i++) {
            market.host().requestResync(market, GapReason.SEQUENCE, i);
        }
        await(() -> market.state() == SubjectState.QUARANTINED);

        feeds.tick(MINUTE);
        assertEquals(0, feeds.groups().get(0).size());
        feeds.tick(MINUTE + Feeds.RETRY_DELAYS_NANOS[0] - 1);
        assertEquals(SubjectState.QUARANTINED, market.state());
        feeds.tick(MINUTE + Feeds.RETRY_DELAYS_NANOS[0]);
        await(() -> market.state() == SubjectState.LIVE);
    }

    @Test
    void anUnknownSourceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> feeds.addStream(1, "NOPE", "T:yes", yes.ring, 0));
    }
}
