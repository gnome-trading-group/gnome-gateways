package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import group.gnometrading.gateways.feed.GapReason;
import group.gnometrading.gateways.feed.RecordingHost;
import group.gnometrading.gateways.feed.StreamOutput;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KalshiProtocolTest {

    private static final String TICKER = "TEST-TICKER";
    private static final String OTHER = "OTHER-TICKER";

    private static long price(final String dollars) {
        return new BigDecimal(dollars)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long size(final String contracts) {
        return new BigDecimal(contracts)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }

    /** A stream's ring and what was published on it. */
    private static final class Stream {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final List<Mbp10Schema> records = new ArrayList<>();
        private final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            records.add(copy);
        });

        List<Mbp10Schema> drain() throws Exception {
            while (poller.poll() > 0) {}
            return records;
        }

        Mbp10Schema last() throws Exception {
            List<Mbp10Schema> all = drain();
            return all.get(all.size() - 1);
        }
    }

    private final Stream yes = new Stream();
    private final Stream no = new Stream();
    private final Stream other = new Stream();
    private final SubjectTable table = new SubjectTable();
    private final List<String> resyncs = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private Subject market;
    private Subject otherMarket;
    private KalshiProtocol protocol;
    private WebSocketChannel channel;
    private long receive = 1_000;

    @BeforeEach
    void setUp() throws Exception {
        market = new Subject(
                TICKER,
                1024,
                new StreamOutput(1, Projection.IDENTITY, yes.ring),
                new StreamOutput(2, Projection.COMPLEMENT, no.ring));
        otherMarket = new Subject(OTHER, 1024, new StreamOutput(3, Projection.IDENTITY, other.ring));
        for (Subject subject : List.of(market, otherMarket)) {
            subject.attach(new RecordingHost(resyncs));
            table.put(subject);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        protocol = new KalshiProtocol(
                new NullLogger(), table, "key", generator.generateKeyPair().getPrivate(), () -> 0);
        WebSocketClient client = mock(WebSocketClient.class);
        doAnswer(inv -> {
                    ByteBuffer payload = inv.getArgument(2);
                    sent.add(StandardCharsets.UTF_8.decode(payload.duplicate()).toString());
                    return null;
                })
                .when(client)
                .wrapMessage(any(), eq(Opcode.TEXT), any());
        channel = new WebSocketChannel(client, new WebSocketWriter(client, 1 << 14));
    }

    private void frame(final String json) {
        protocol.onFrame(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), receive++);
    }

    private void subscribed() {
        frame("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"orderbook_delta\",\"sid\":1}}");
        frame("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"trade\",\"sid\":2}}");
    }

    /** YES 0.50 x200, 0.55 x100; NO (YES-priced) 0.50 x75, 0.54 x50. */
    private void snapshot(final int seq) {
        frame("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":" + seq + ",\"msg\":{\"market_ticker\":\"" + TICKER
                + "\",\"yes_dollars_fp\":[[\"0.5000\",\"200.00\"],[\"0.5500\",\"100.00\"]],"
                + "\"no_dollars_fp\":[[\"0.5000\",\"75.00\"],[\"0.5400\",\"50.00\"]]},"
                + "\"sending_ts_ms\":1699999999000}");
    }

    private void delta(final int seq, final String priceDollars, final String delta, final String side) {
        frame("{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + seq + ",\"msg\":{\"market_ticker\":\"" + TICKER
                + "\",\"price_dollars\":\"" + priceDollars + "\",\"delta_fp\":\"" + delta + "\",\"side\":\"" + side
                + "\",\"ts_ms\":1700000000000}}");
    }

    private void trade(final int seq, final String yesPrice, final String count, final String takerSide) {
        frame("{\"type\":\"trade\",\"sid\":2,\"seq\":" + seq + ",\"msg\":{\"market_ticker\":\"" + TICKER
                + "\",\"yes_price_dollars\":\"" + yesPrice + "\",\"count_fp\":\"" + count
                + "\",\"taker_book_side\":\"" + takerSide + "\",\"ts_ms\":1700000000123}}");
    }

    // ----- books -----

    @Test
    void aSnapshotPublishesTheBookYesLevelsAsBidsNoLevelsAsAsks() throws Exception {
        subscribed();
        snapshot(1);

        Mbp10Schema book = yes.last();
        assertTrue(book.decoder.flags().snapshot());
        assertEquals(SubjectState.LIVE, market.state());
        assertEquals(price("0.55"), book.decoder.bidPrice0());
        assertEquals(size("100"), book.decoder.bidSize0());
        assertEquals(price("0.50"), book.decoder.bidPrice1());
        assertEquals(price("0.50"), book.decoder.askPrice0());
        assertEquals(size("75"), book.decoder.askSize0());
        assertEquals(price("0.54"), book.decoder.askPrice1());
        assertEquals(Mbp10Encoder.sequenceNullValue(), book.decoder.sequence());
    }

    @Test
    void aSnapshotCarriesTheTimeKalshiSentIt() throws Exception {
        subscribed();
        snapshot(1);

        assertEquals(1699999999000L * 1_000_000L, yes.last().decoder.timestampEvent());
        assertEquals(1699999999000L * 1_000_000L, no.last().decoder.timestampEvent());
    }

    @Test
    void aSnapshotWithNoSendTimeHasNoEventTime() throws Exception {
        subscribed();
        frame("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"" + TICKER
                + "\",\"yes_dollars_fp\":[[\"0.5000\",\"200.00\"]],\"no_dollars_fp\":[]}}");

        assertTrue(yes.last().decoder.flags().snapshot());
        assertEquals(Mbp10Encoder.timestampEventNullValue(), yes.last().decoder.timestampEvent());
    }

    @Test
    void deltasChangeTheirLevelAndCarryTheVenueTime() throws Exception {
        subscribed();
        snapshot(1);
        delta(2, "0.550", "50.00", "yes");

        Mbp10Schema book = yes.last();
        assertFalse(book.decoder.flags().snapshot());
        assertEquals(size("150"), book.decoder.bidSize0());
        assertEquals(1700000000000L * 1_000_000L, book.decoder.timestampEvent());
        assertEquals(Action.Modify, book.decoder.action());

        delta(3, "0.550", "-150.00", "yes");
        assertEquals(price("0.50"), yes.last().decoder.bidPrice0());
        delta(4, "0.550", "-10.00", "yes");
        assertEquals(price("0.50"), yes.last().decoder.bidPrice0(), "a level can't go below zero");
    }

    @Test
    void subCentTicksAreTheirOwnLevelsAndAPriceOffEveryTickIsDropped() throws Exception {
        subscribed();
        snapshot(1);
        delta(2, "0.5510", "7.00", "yes");
        assertEquals(price("0.551"), yes.last().decoder.bidPrice0());
        assertEquals(size("7"), yes.last().decoder.bidSize0());

        delta(3, "0.55150", "9.00", "yes");
        assertEquals(price("0.551"), yes.last().decoder.bidPrice0(), "0.5515 is on no Kalshi tick");
        assertEquals(size("7"), yes.last().decoder.bidSize0());
    }

    @Test
    void anUnreadableSnapshotLevelIsLeftOutAndTheRestKept() throws Exception {
        subscribed();
        frame("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"" + TICKER
                + "\",\"yes_dollars_fp\":[[\"n/a\",\"200.00\"],[\"0.5500\",\"100.00\"]],\"no_dollars_fp\":[]}}");

        Mbp10Schema book = yes.last();
        assertEquals(price("0.55"), book.decoder.bidPrice0());
        assertEquals(Mbp10Encoder.bidPrice0NullValue(), book.decoder.bidPrice1());
    }

    @Test
    void anUnreadableDeltaMakesThatMarketStale() throws Exception {
        subscribed();
        snapshot(1);
        delta(2, "0.550", "lots", "yes");

        assertEquals(SubjectState.STALE, market.state());
        assertEquals(List.of(TICKER + ":UNREADABLE"), resyncs);
        assertTrue(yes.last().decoder.flags().badData());
    }

    // ----- trades -----

    @Test
    void tradesGoOutWithTheTakersSide() throws Exception {
        subscribed();
        snapshot(1);
        trade(1, "0.5300", "12.00", "bid");

        Mbp10Schema print = yes.last();
        assertEquals(Action.Trade, print.decoder.action());
        assertEquals(price("0.53"), print.decoder.price());
        assertEquals(size("12"), print.decoder.size());
        assertEquals(Side.Bid, print.decoder.side());
        assertEquals(1700000000123L * 1_000_000L, print.decoder.timestampEvent());

        trade(2, "0.5300", "1.00", "ask");
        assertEquals(Side.Ask, yes.last().decoder.side());
    }

    @Test
    void aTradeThatCantBeReadIsNotPublished() throws Exception {
        subscribed();
        snapshot(1);
        int before = yes.drain().size();
        trade(1, "n/a", "12.00", "bid");

        assertEquals(before, yes.drain().size());
    }

    // ----- the NO listing -----

    @Test
    void theNoStreamSeesTheComplementOfTheYesBookAndTrades() throws Exception {
        subscribed();
        snapshot(1);
        Mbp10Schema book = no.last();
        assertEquals(2, book.decoder.streamId());
        assertEquals(price("0.50"), book.decoder.bidPrice0(), "best NO bid = 1 - best YES ask 0.50");
        assertEquals(price("0.45"), book.decoder.askPrice0(), "best NO ask = 1 - best YES bid 0.55");

        trade(1, "0.5300", "12.00", "bid");
        Mbp10Schema print = no.last();
        assertEquals(price("0.47"), print.decoder.price());
        assertEquals(Side.Ask, print.decoder.side(), "a YES buyer sells NO");

        market.stale(GapReason.SEQUENCE, receive);
        assertTrue(no.last().decoder.flags().badData());
        assertEquals(price("0.50"), no.last().decoder.bidPrice0(), "the suspect book is in NO terms too");
    }

    // ----- sequences -----

    @Test
    void aContiguousSequenceIsNoGapRepliesIncluded() throws Exception {
        subscribed();
        snapshot(1);
        frame("{\"id\":2,\"sid\":1,\"seq\":2,\"type\":\"ok\",\"msg\":{\"market_tickers\":[\"" + TICKER + "\"]}}");
        delta(3, "0.550", "1.00", "yes");

        assertTrue(resyncs.isEmpty());
        assertEquals(SubjectState.LIVE, market.state());
    }

    @Test
    void aSkippedBookSequenceMakesEveryMarketOnTheConnectionStale() throws Exception {
        subscribed();
        snapshot(1);
        frame("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":2,\"msg\":{\"market_ticker\":\"" + OTHER
                + "\",\"yes_dollars_fp\":[[\"0.2000\",\"1.00\"]],\"no_dollars_fp\":[]}}");
        delta(4, "0.550", "1.00", "yes");

        assertEquals(SubjectState.STALE, market.state());
        assertEquals(SubjectState.STALE, otherMarket.state());
        assertEquals(2, resyncs.size());
        assertTrue(resyncs.contains(OTHER + ":CONNECTION_SEQUENCE"));
        // The delta that showed the gap lands on a stale book: the last record is the flagged one, without its +1.
        Mbp10Schema last = yes.last();
        assertTrue(last.decoder.flags().badData());
        assertEquals(price("0.550"), last.decoder.bidPrice0());
        assertEquals(100_000_000L, last.decoder.bidSize0());
    }

    @Test
    void aRepeatedSequenceIsAGapToo() throws Exception {
        subscribed();
        snapshot(1);
        delta(2, "0.550", "1.00", "yes");
        delta(2, "0.550", "1.00", "yes");

        assertEquals(SubjectState.STALE, market.state());
    }

    @Test
    void aGapOnTheTradeSubscriptionLeavesTheBooksAlone() throws Exception {
        subscribed();
        snapshot(1);
        trade(1, "0.5300", "1.00", "bid");
        trade(5, "0.5300", "1.00", "bid");

        assertEquals(SubjectState.LIVE, market.state());
        assertTrue(resyncs.isEmpty());
    }

    @Test
    void aNewConnectionStartsItsSequencesAfresh() throws Exception {
        subscribed();
        snapshot(1);
        delta(2, "0.550", "1.00", "yes");
        protocol.onConnected();
        subscribed();
        snapshot(1);

        assertTrue(resyncs.isEmpty());
    }

    @Test
    void framesForMarketsNotOnTheConnectionAreIgnored() throws Exception {
        table.remove(otherMarket);
        subscribed();
        frame("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"" + OTHER
                + "\",\"yes_dollars_fp\":[[\"0.2000\",\"1.00\"]],\"no_dollars_fp\":[]}}");

        assertTrue(other.drain().isEmpty());
    }

    // ----- commands -----

    @Test
    void subscribingAsksForBothChannelsInYesLegPrices() throws Exception {
        protocol.subscribe(channel, List.of(market, otherMarket));

        assertEquals(
                List.of("{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"orderbook_delta\",\"trade\"],"
                        + "\"market_tickers\":[\"TEST-TICKER\",\"OTHER-TICKER\"],\"use_yes_price\":true}}"),
                sent);
    }

    @Test
    void removingAndResyncingNameTheSidsOnceTheyAreKnown() throws Exception {
        protocol.unsubscribe(channel, List.of(market));
        protocol.resync(channel, List.of(market));
        assertTrue(sent.isEmpty(), "before the sids are known there is nothing to name");

        subscribed();
        protocol.unsubscribe(channel, List.of(market));
        protocol.resync(channel, List.of(market));

        assertEquals(
                List.of(
                        "{\"id\":1,\"cmd\":\"update_subscription\",\"params\":{\"sids\":[1],"
                                + "\"market_tickers\":[\"TEST-TICKER\"],\"action\":\"delete_markets\"}}",
                        "{\"id\":2,\"cmd\":\"update_subscription\",\"params\":{\"sids\":[2],"
                                + "\"market_tickers\":[\"TEST-TICKER\"],\"action\":\"delete_markets\"}}",
                        "{\"id\":3,\"cmd\":\"update_subscription\",\"params\":{\"sids\":[1],"
                                + "\"market_tickers\":[\"TEST-TICKER\"],\"action\":\"get_snapshot\"}}"),
                sent);
    }

    @Test
    void theUpgradeIsSigned() throws Exception {
        WebSocketClient client = mock(WebSocketClient.class);
        List<String> headers = new ArrayList<>();
        doAnswer(inv -> {
                    headers.add(inv.getArgument(0));
                    return null;
                })
                .when(client)
                .setHeader(any(), any());

        protocol.authenticate(new WebSocketChannel(client, new WebSocketWriter(client, 1 << 12)));

        assertEquals(List.of("KALSHI-ACCESS-KEY", "KALSHI-ACCESS-TIMESTAMP", "KALSHI-ACCESS-SIGNATURE"), headers);
    }
}
