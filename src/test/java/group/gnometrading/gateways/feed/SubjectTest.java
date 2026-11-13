package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.book.PriceLevels;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubjectTest {

    private static long px(final double dollars) {
        return Math.round(dollars * Statics.PRICE_SCALING_FACTOR);
    }

    /** A stream's ring and everything published on it, decoded. */
    static final class Captured {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final List<Mbp10Schema> records = new ArrayList<>();
        private final group.gnometrading.sequencer.SequencedPoller poller =
                ring.createPoller((seq, templateId, buffer, length) -> {
                    Mbp10Schema copy = new Mbp10Schema();
                    copy.buffer.putBytes(0, buffer, 0, length);
                    copy.wrap(copy.buffer);
                    records.add(copy);
                });

        List<Mbp10Schema> drain() throws Exception {
            while (poller.poll() > 0) {}
            return records;
        }
    }

    private final Captured yes = new Captured();
    private final Captured no = new Captured();
    private final List<String> resyncs = new ArrayList<>();
    private final Subject subject = new Subject(
            "TICKER",
            64,
            new StreamOutput(11, Projection.IDENTITY, yes.ring),
            new StreamOutput(12, Projection.COMPLEMENT, no.ring));

    SubjectTest() {
        subject.attach(new RecordingHost(resyncs));
    }

    private void snapshot() {
        subject.beginSnapshot();
        subject.level(Side.Bid, px(0.40), 10, 1);
        subject.level(Side.Ask, px(0.45), 20, 1);
        subject.endSnapshot(1_000, 2_000, 7);
    }

    @Test
    void aSnapshotMakesItLiveAndGoesOutToEveryStreamAsItSeesIt() throws Exception {
        assertEquals(SubjectState.SUBSCRIBING, subject.state());
        snapshot();

        assertEquals(SubjectState.LIVE, subject.state());
        Mbp10Schema yesBook = yes.drain().get(0);
        assertEquals(11, yesBook.decoder.streamId());
        assertTrue(yesBook.decoder.flags().snapshot());
        assertFalse(yesBook.decoder.flags().badData());
        assertEquals(px(0.40), yesBook.decoder.bidPrice0());
        assertEquals(7, yesBook.decoder.sequence());
        assertEquals(2_000, yesBook.decoder.timestampRecv());
        Mbp10Schema noBook = no.drain().get(0);
        assertEquals(12, noBook.decoder.streamId());
        assertEquals(px(0.55), noBook.decoder.bidPrice0());
        assertEquals(px(0.60), noBook.decoder.askPrice0());
    }

    @Test
    void updatesBeforeTheSnapshotAreIgnored() throws Exception {
        subject.set(Side.Bid, px(0.90), 5, 1);
        subject.endUpdate(1, 1, 1);

        assertTrue(yes.drain().isEmpty());
        assertEquals(0, subject.book().bids().depth());
    }

    @Test
    void updatesAfterTheSnapshotPublishTheBook() throws Exception {
        snapshot();
        subject.add(Side.Bid, px(0.40), 5, 1);
        subject.set(Side.Ask, px(0.44), 3, 1);
        subject.endUpdate(1_500, 2_500, 8);

        Mbp10Schema update = yes.drain().get(1);
        assertFalse(update.decoder.flags().snapshot());
        assertEquals(15, update.decoder.bidSize0());
        assertEquals(px(0.44), update.decoder.askPrice0());
        assertEquals(Action.Modify, update.decoder.action());
    }

    @Test
    void aStaleBookGoesOutOnceFlaggedAndAsksForAResync() throws Exception {
        snapshot();
        subject.stale(GapReason.SEQUENCE, 3_000);
        subject.stale(GapReason.SEQUENCE, 3_001);
        subject.set(Side.Bid, px(0.41), 1, 1);
        subject.endUpdate(1, 3_002, 9);

        assertEquals(SubjectState.STALE, subject.state());
        assertEquals(List.of("TICKER:SEQUENCE"), resyncs);
        List<Mbp10Schema> records = yes.drain();
        assertEquals(2, records.size(), "the snapshot and one flagged book; nothing while stale");
        assertTrue(records.get(1).decoder.flags().badData());
        assertEquals(
                3_000, records.get(1).decoder.timestampEvent(), "flagged when it went stale, not at its last update");

        snapshot();
        assertEquals(SubjectState.LIVE, subject.state());
        assertFalse(yes.drain().get(2).decoder.flags().badData());
    }

    @Test
    void aDisconnectedBookGoesOutFlaggedAtTheDisconnectNotItsLastUpdate() throws Exception {
        snapshot();
        subject.disconnected(90_000);

        Mbp10Schema flagged = yes.drain().get(1);
        assertTrue(flagged.decoder.flags().badData());
        assertEquals(90_000, flagged.decoder.timestampEvent());
        assertEquals(90_000, flagged.decoder.timestampRecv());
        assertEquals(SubjectState.SUBSCRIBING, subject.state());
    }

    @Test
    void aTradeGoesOutInEachStreamsTermsFlaggedWhenTheBookIsntTrusted() throws Exception {
        snapshot();
        subject.trade(px(0.42), 3, Side.Bid, 1_200, 2_200);

        Mbp10Schema yesTrade = yes.drain().get(1);
        assertEquals(Action.Trade, yesTrade.decoder.action());
        assertEquals(px(0.42), yesTrade.decoder.price());
        assertEquals(Side.Bid, yesTrade.decoder.side());
        Mbp10Schema noTrade = no.drain().get(1);
        assertEquals(px(0.58), noTrade.decoder.price());
        assertEquals(Side.Ask, noTrade.decoder.side());

        subject.stale(GapReason.NONCE, 2_300);
        subject.trade(px(0.43), 1, Side.Ask, 1_300, 2_400);
        assertTrue(yes.drain().get(3).decoder.flags().badData());
    }

    @Test
    void aRemovedMarketPublishesNothingAndKeepsItsState() throws Exception {
        snapshot();
        assertTrue(subject.transition(SubjectState.LIVE, SubjectState.REMOVING));
        subject.trade(px(0.42), 3, Side.Bid, 1, 1);
        snapshot();

        assertEquals(SubjectState.REMOVING, subject.state());
        assertEquals(1, yes.drain().size());
    }

    @Test
    void theMeanGapFollowsTheMessageRate() {
        for (int i = 1; i <= 50; i++) {
            subject.endUpdate(i, i * 1_000_000L, i);
        }
        assertEquals(50_000_000L, subject.lastReceiveNanos());
        assertTrue(Math.abs(subject.meanGapNanos() - 1_000_000L) < 1_000, "mean " + subject.meanGapNanos());
    }

    @Test
    void theTopLevelsCopyFollowsTheBook() {
        snapshot();
        long[] prices = new long[TopLevels.LEVELS * 2];
        long[] sizes = new long[TopLevels.LEVELS * 2];
        int[] depths = new int[2];
        subject.top().read(prices, sizes, depths);

        assertEquals(1, depths[0]);
        assertEquals(px(0.40), prices[0]);
        assertEquals(px(0.45), prices[TopLevels.LEVELS]);
        assertEquals(20, sizes[TopLevels.LEVELS]);
    }

    @Test
    void aJoiningStreamGetsTheBookOnceTheReaderRepublishesIt() throws Exception {
        snapshot();
        Captured later = new Captured();
        subject.addOutput(new StreamOutput(13, Projection.IDENTITY, later.ring));
        assertEquals(List.of("republish TICKER"), resyncs);
        assertTrue(later.drain().isEmpty(), "only the reader publishes");

        subject.republish(3_000);
        Mbp10Schema book = later.drain().get(0);
        assertEquals(13, book.decoder.streamId());
        assertTrue(book.decoder.flags().snapshot());
        assertEquals(px(0.40), book.decoder.bidPrice0());

        assertEquals(2, subject.removeOutput(13));
        assertEquals(1, subject.removeOutput(12));
    }

    @Test
    void anUntrustedBookIsNotRepublished() throws Exception {
        subject.republish(3_000);
        assertTrue(yes.drain().isEmpty());
    }

    @Test
    void aBookDeeperThanItStartedWithIsKeptWhole() throws Exception {
        Captured deep = new Captured();
        Subject shallow = new Subject("SHALLOW", 2, new StreamOutput(21, Projection.IDENTITY, deep.ring));
        shallow.attach(new RecordingHost(resyncs));
        shallow.beginSnapshot();
        shallow.level(Side.Bid, px(0.40), 10, 1);
        shallow.level(Side.Bid, px(0.39), 10, 1);
        shallow.level(Side.Bid, px(0.38), 10, 1);
        shallow.endSnapshot(1_000, 2_000, 7);
        shallow.set(Side.Bid, px(0.37), 10, 1);
        shallow.endUpdate(1_001, 2_001, 8);

        assertEquals(SubjectState.LIVE, shallow.state());
        assertEquals(4, shallow.book().bids().depth());
        assertTrue(resyncs.isEmpty());
        assertFalse(deep.drain().stream().anyMatch(r -> r.decoder.flags().badData()));
    }

    @Test
    void aBookDeeperThanTheMostASideHoldsIsFlaggedAndFetchedAgain() throws Exception {
        Captured deep = new Captured();
        Subject huge = new Subject("HUGE", 2, new StreamOutput(21, Projection.IDENTITY, deep.ring));
        huge.attach(new RecordingHost(resyncs));
        huge.beginSnapshot();
        for (int level = 0; level <= PriceLevels.MAX_LEVELS; level++) {
            huge.level(Side.Bid, px(0.90) - level, 10, 1);
        }
        huge.endSnapshot(1_000, 2_000, 7);

        assertEquals(SubjectState.STALE, huge.state());
        assertTrue(deep.drain().get(0).decoder.flags().badData());
        assertEquals(List.of("HUGE:BOOK_OVERFLOW"), resyncs);
    }

    @Test
    void adoptingAStandInWithNoBookFlagsTheBookItHad() throws Exception {
        snapshot();
        yes.drain().clear();
        Subject standIn = new Subject("TICKER", 64);
        standIn.transition(SubjectState.SUBSCRIBING, SubjectState.EMPTY);

        subject.adoptFrom(standIn, 3_000);

        assertEquals(SubjectState.EMPTY, subject.state());
        Mbp10Schema flagged = yes.drain().get(0);
        assertTrue(flagged.decoder.flags().badData());
        assertEquals(px(0.40), flagged.decoder.bidPrice0(), "its last book, not the stand-in's empty one");
    }

    // ----- a roll: the market moves to a stand-in's connection -----

    /** The venue's book at {@code bid} on one connection's market: a snapshot first, then updates. */
    private static void book(final Subject on, final boolean snapshot, final double bid, final long time) {
        if (snapshot) {
            on.beginSnapshot();
            on.level(Side.Bid, px(bid), 10, 1);
            on.level(Side.Ask, px(0.90), 20, 1);
            on.endSnapshot(time, time, 7);
        } else {
            on.set(Side.Bid, px(bid), 10, 1);
            on.endUpdate(time, time, 8);
        }
    }

    private static void trade(final Subject on, final double price, final long time) {
        on.trade(px(price), 5, Side.Bid, time, time);
    }

    /** What a stream read: B for a book with its best bid, T for a trade at its price, each with its venue time. */
    private static List<String> read(final List<Mbp10Schema> records) {
        List<String> read = new ArrayList<>();
        for (Mbp10Schema r : records) {
            boolean isTrade = r.decoder.action() == Action.Trade;
            double price =
                    (isTrade ? r.decoder.price() : r.decoder.bidPrice0()) / (double) Statics.PRICE_SCALING_FACTOR;
            read.add((isTrade ? "T" : "B") + price + "@" + r.decoder.timestampEvent());
        }
        return read;
    }

    @Test
    void aStandInAheadOfTheOldConnectionHasItsLaterRecordsPublishedInOrder() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        for (Subject on : List.of(subject, standIn)) {
            book(on, false, 0.41, 2);
            trade(on, 0.45, 2);
        }
        // The old connection lets go here; the stand-in's connection goes on.
        book(standIn, false, 0.42, 3);
        trade(standIn, 0.46, 3);
        book(standIn, false, 0.43, 4);
        yes.drain().clear();

        int replayed = subject.adoptFrom(standIn, 9);

        assertEquals(3, replayed);
        assertEquals(List.of("B0.42@3", "T0.46@3", "B0.43@4"), read(yes.drain()));
        assertFalse(standIn.replayFellShort());
        assertEquals(SubjectState.LIVE, subject.state());
        book(subject, false, 0.44, 5);
        assertEquals("B0.44@5", read(yes.drain()).get(3), "and it goes on from the stand-in's book");
    }

    @Test
    void recordsSharingAVenueTimeAreToldApartByHowManyCameBefore() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        for (Subject on : List.of(subject, standIn)) {
            book(on, false, 0.41, 5);
            book(on, false, 0.42, 5);
            trade(on, 0.45, 5);
        }
        book(standIn, false, 0.43, 5);
        trade(standIn, 0.46, 5);
        yes.drain().clear();

        subject.adoptFrom(standIn, 9);

        assertEquals(List.of("B0.43@5", "T0.46@5"), read(yes.drain()), "the third book and second trade at 5 only");
    }

    @Test
    void aStandInBehindTheOldConnectionDoesntPublishWhatTheOldOneAlreadyDid() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        // The old connection got these before letting go; the stand-in hadn't yet.
        book(subject, false, 0.41, 2);
        trade(subject, 0.45, 2);
        book(subject, false, 0.42, 3);
        yes.drain().clear();

        assertEquals(0, subject.adoptFrom(standIn, 9));
        // Now on the stand-in's connection, they arrive.
        book(subject, false, 0.41, 2);
        trade(subject, 0.45, 2);
        book(subject, false, 0.42, 3);
        book(subject, false, 0.43, 4);
        trade(subject, 0.46, 4);

        assertEquals(List.of("B0.43@4", "T0.46@4"), read(yes.drain()));
        assertEquals(px(0.43), subject.book().bids().price(0), "the repeats still built the book");
    }

    @Test
    void aStandInThatOverwroteRecordsTheMarketNeededSaysSo() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 2);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        for (int t = 2; t <= 5; t++) {
            book(standIn, false, 0.40 + t / 100.0, t);
        }
        yes.drain().clear();

        assertEquals(2, subject.adoptFrom(standIn, 9));

        assertTrue(standIn.replayFellShort(), "2 and 3 were overwritten");
        assertEquals(List.of("B0.44@4", "B0.45@5"), read(yes.drain()));
    }

    @Test
    void aStandInThatKeptEverythingAfterTheCutDoesntFallShortThoughItWrapped() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 2);
        book(standIn, true, 0.40, 1);
        for (int t = 2; t <= 4; t++) {
            book(standIn, false, 0.40 + t / 100.0, t);
        }
        book(subject, true, 0.40, 1);
        for (int t = 2; t <= 3; t++) {
            book(subject, false, 0.40 + t / 100.0, t);
        }
        yes.drain().clear();

        assertEquals(1, subject.adoptFrom(standIn, 9));

        assertFalse(standIn.replayFellShort(), "it still holds 3, the last the old connection published");
        assertEquals(List.of("B0.44@4"), read(yes.drain()));
    }

    @Test
    void aSecondRollPicksUpWhereTheFirstReplacementLeftOff() throws Exception {
        Subject first = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(first, true, 0.40, 1);
        book(subject, false, 0.41, 5);
        book(first, false, 0.41, 5);
        book(first, false, 0.42, 5);
        subject.adoptFrom(first, 9);
        // On the first replacement's connection, the third book at 5; a second roll's stand-in has seen all three.
        Subject second = Subject.standIn("TICKER", 64, 128);
        book(second, true, 0.40, 1);
        book(second, false, 0.41, 5);
        book(second, false, 0.42, 5);
        book(subject, false, 0.43, 5);
        book(second, false, 0.43, 5);
        book(second, false, 0.44, 6);
        yes.drain().clear();

        subject.adoptFrom(second, 10);

        assertEquals(List.of("B0.44@6"), read(yes.drain()), "the third book at 5 went out once, before the roll");
    }

    @Test
    void aMarketThatNeverTradedTakesEveryTradeTheStandInHad() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        trade(standIn, 0.45, 2);
        yes.drain().clear();

        subject.adoptFrom(standIn, 9);

        assertEquals(List.of("T0.45@2"), read(yes.drain()));
    }

    @Test
    void replayedBooksReachEachStreamAsItsProjectionSeesThem() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(subject, true, 0.40, 1);
        book(standIn, true, 0.40, 1);
        book(standIn, false, 0.42, 2);
        no.drain().clear();

        subject.adoptFrom(standIn, 9);

        Mbp10Schema complement = no.drain().get(0);
        assertEquals(px(0.58), complement.decoder.askPrice0(), "the yes bid is the no ask");
        assertEquals(px(0.10), complement.decoder.bidPrice0());
    }

    @Test
    void aMarketThatWasntTrustedTakesTheStandInsBookAsASnapshot() throws Exception {
        Subject standIn = Subject.standIn("TICKER", 64, 128);
        book(standIn, true, 0.40, 1);
        book(standIn, false, 0.42, 2);

        assertEquals(0, subject.adoptFrom(standIn, 9));

        List<Mbp10Schema> records = yes.drain();
        assertEquals(1, records.size());
        assertTrue(records.get(0).decoder.flags().snapshot());
        assertEquals(px(0.42), records.get(0).decoder.bidPrice0());
    }
}
