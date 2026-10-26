package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        snapshot();
        assertEquals(SubjectState.LIVE, subject.state());
        assertFalse(yes.drain().get(2).decoder.flags().badData());
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
    void aBookDeeperThanItsLevelsIsFlaggedAndFetchedAgain() throws Exception {
        Captured deep = new Captured();
        Subject shallow = new Subject("SHALLOW", 2, new StreamOutput(21, Projection.IDENTITY, deep.ring));
        shallow.attach(new RecordingHost(resyncs));
        shallow.beginSnapshot();
        shallow.level(Side.Bid, px(0.40), 10, 1);
        shallow.level(Side.Bid, px(0.39), 10, 1);
        shallow.level(Side.Bid, px(0.38), 10, 1);
        shallow.endSnapshot(1_000, 2_000, 7);

        assertEquals(SubjectState.STALE, shallow.state());
        assertTrue(deep.drain().get(0).decoder.flags().badData());
        assertEquals(List.of("SHALLOW:BOOK_OVERFLOW"), resyncs);
    }

    @Test
    void anUpdateThatOverflowsTheBookMakesItStale() throws Exception {
        Captured deep = new Captured();
        Subject shallow = new Subject("SHALLOW", 2, new StreamOutput(21, Projection.IDENTITY, deep.ring));
        shallow.attach(new RecordingHost(resyncs));
        shallow.beginSnapshot();
        shallow.level(Side.Bid, px(0.40), 10, 1);
        shallow.endSnapshot(1_000, 2_000, 7);
        shallow.set(Side.Bid, px(0.39), 10, 1);
        shallow.set(Side.Bid, px(0.38), 10, 1);
        shallow.endUpdate(1_001, 2_001, 8);

        assertEquals(SubjectState.STALE, shallow.state());
        List<Mbp10Schema> records = deep.drain();
        assertTrue(records.get(records.size() - 1).decoder.flags().badData());
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
}
