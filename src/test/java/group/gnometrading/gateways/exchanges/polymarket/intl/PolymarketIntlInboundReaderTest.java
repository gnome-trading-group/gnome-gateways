package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.inbound.exchanges.polymarket.intl.PolymarketIntlInboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketIntlInboundReaderTest {

    private static final String TOKEN_ID = "token-yes";

    private SequencedRingBuffer<Mbp10Schema> ringBuffer;
    private SequencedPoller poller;
    private PolymarketIntlInboundReader reader;
    private WebSocketClient client;
    private WebSocketResponse response;
    private List<Mbp10Schema> captured;

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        ringBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        poller = ringBuffer.createPoller((globalSequence, templateId, buffer, length) -> {
            Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        Listing listing = new Listing(
                7,
                new Exchange(2, "POLYMARKET", "Polymarket", "global"),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "condition-1:" + TOKEN_ID,
                "TEST-YES");
        reader = new PolymarketIntlInboundReader(
                new NullLogger(), ringBuffer, () -> 9_000_000_000L, null, listing, 77, client, new JsonDecoder());
        reader.buffer = false;
        reader.pauseControl.release();
    }

    @Test
    void snapshotSortsAndPublishesTopLevels() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"1782753357257",\
                "bids":[{"price":"0.07","size":"4"},{"price":"0.08","size":"3"}],\
                "asks":[{"price":"0.11","size":"7"},{"price":"0.10","size":"6"}],\
                "event_type":"book","last_trade_price":"0.09"}]
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.08"), schema.decoder.bidPrice0());
        assertEquals(size("3"), schema.decoder.bidSize0());
        assertEquals(price("0.10"), schema.decoder.askPrice0());
        assertEquals(size("6"), schema.decoder.askSize0());
        assertEquals(1_782_753_357_257_000_000L, schema.decoder.timestampEvent());
        assertEquals(Mbp10Encoder.sequenceNullValue(), schema.decoder.sequence());
        assertEquals(Action.Modify, schema.decoder.action());
    }

    /** Levels past the old 32-bit size limit (~4,295 shares) were recorded wrapped; a live book had 11,971. */
    @Test
    void largeBookLevelsAreRecordedExactly() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"1782753357257",\
                "bids":[{"price":"0.74","size":"11971.08"}],\
                "asks":[{"price":"0.76","size":"250000"}],\
                "event_type":"book","last_trade_price":"0.75"}]
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(size("11971.08"), schema.decoder.bidSize0());
        assertEquals(size("250000"), schema.decoder.askSize0());
    }

    @Test
    void priceChangeUpdatesRemovesAndFiltersLevels() throws Exception {
        process(
                """
                {"event_type":"book","timestamp":"1782753357257",\
                "bids":[{"price":"0.08","size":"3"},{"price":"0.07","size":"4"}],\
                "asks":[{"price":"0.10","size":"6"},{"price":"0.11","size":"7"}]}
                """);
        process(
                """
                {"market":"condition-1","price_changes":[\
                {"asset_id":"token-yes","price":"0.08","size":"0","side":"BUY"},\
                {"asset_id":"token-yes","price":"0.09","size":"5","side":"BUY"},\
                {"asset_id":"other-token","price":"0.12","size":"999","side":"SELL"}],\
                "timestamp":"1782753358257","event_type":"price_change"}
                """);

        Mbp10Schema schema = captured.get(1);
        assertEquals(price("0.09"), schema.decoder.bidPrice0());
        assertEquals(size("5"), schema.decoder.bidSize0());
        assertEquals(price("0.07"), schema.decoder.bidPrice1());
        assertEquals(price("0.10"), schema.decoder.askPrice0());
    }

    @Test
    void tradePublishesPriceSizeSideAndCurrentBook() throws Exception {
        process(
                """
                {"event_type":"book","timestamp":"1782753357257",\
                "bids":[{"price":"0.08","size":"3"}],\
                "asks":[{"price":"0.10","size":"6"}]}
                """);
        process(
                """
                {"market":"condition-1","asset_id":"token-yes",\
                "price":"0.09","size":"2.5","side":"SELL","timestamp":"1782753359257",\
                "event_type":"last_trade_price"}
                """);

        Mbp10Schema schema = captured.get(1);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Ask, schema.decoder.side());
        assertEquals(price("0.09"), schema.decoder.price());
        assertEquals(size("2.5"), schema.decoder.size());
        assertEquals(price("0.08"), schema.decoder.bidPrice0());
        assertEquals(price("0.10"), schema.decoder.askPrice0());
    }

    @Test
    void removingBestPricePromotesLevelBeyondPublishedDepth() throws Exception {
        process(
                """
                {"event_type":"book","timestamp":"1782753357257",\
                "bids":[{"price":"0.20","size":"1"},{"price":"0.19","size":"1"},\
                {"price":"0.18","size":"1"},{"price":"0.17","size":"1"},\
                {"price":"0.16","size":"1"},{"price":"0.15","size":"1"},\
                {"price":"0.14","size":"1"},{"price":"0.13","size":"1"},\
                {"price":"0.12","size":"1"},{"price":"0.11","size":"1"},\
                {"price":"0.10","size":"1"}],\
                "asks":[{"price":"0.21","size":"1"}]}
                """);
        process(
                """
                {"price_changes":[{"asset_id":"token-yes","price":"0.20","size":"0","side":"BUY"}],\
                "timestamp":"1782753358257","event_type":"price_change"}
                """);

        Mbp10Schema schema = captured.get(1);
        assertEquals(price("0.19"), schema.decoder.bidPrice0());
        assertEquals(price("0.10"), schema.decoder.bidPrice9());
    }

    @Test
    void pongIsConsumedWithoutJsonDecoding() throws Exception {
        process("PONG");
        assertEquals(0, captured.size());
    }

    /** A market that has never traded sends an empty last_trade_price; it used to throw and reconnect forever. */
    @Test
    void bookWithNoLastTradePriceIsStillPublished() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"1782753357257",\
                "bids":[{"price":"0.08","size":"3"}],\
                "asks":[{"price":"0.10","size":"6"}],\
                "event_type":"book","last_trade_price":""}]
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.08"), schema.decoder.bidPrice0());
        assertEquals(price("0.10"), schema.decoder.askPrice0());
        assertEquals(Mbp10Encoder.priceNullValue(), schema.decoder.price(), "no trade yet");
    }

    @Test
    void unreadableLevelIsSkippedAndTheRestOfTheBookKept() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"1782753357257",\
                "bids":[{"price":"n/a","size":"4"},{"price":"0.08","size":"3"},{"price":"0.07","size":""}],\
                "asks":[{"price":"0.10","size":"6"}],\
                "event_type":"book","last_trade_price":"0.09"}]
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.08"), schema.decoder.bidPrice0());
        assertEquals(size("3"), schema.decoder.bidSize0());
        assertEquals(Mbp10Encoder.bidPrice1NullValue(), schema.decoder.bidPrice1());
    }

    @Test
    void pricesInExponentFormAreRead() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"1782753357257",\
                "bids":[{"price":"8e-02","size":"3E+1"}],\
                "asks":[{"price":"0.10","size":"6"}],\
                "event_type":"book","last_trade_price":"9.0e-2"}]
                """);

        Mbp10Schema schema = captured.get(0);
        assertEquals(price("0.08"), schema.decoder.bidPrice0());
        assertEquals(size("30"), schema.decoder.bidSize0());
    }

    @Test
    void tradeWithNoReadablePriceIsNotPublished() throws Exception {
        process(
                """
                {"market":"condition-1","asset_id":"token-yes","price":"","size":"5","side":"BUY",\
                "timestamp":"1782753357300","event_type":"last_trade_price"}
                """);
        assertEquals(0, captured.size());

        process(
                """
                {"market":"condition-1","asset_id":"token-yes","price":"0.4","size":"5","side":"BUY",\
                "timestamp":"1782753357301","event_type":"last_trade_price"}
                """);
        assertEquals(1, captured.size(), "the feed carries on");
        assertEquals(price("0.4"), captured.get(0).decoder.price());
    }

    @Test
    void eventWithAnUnreadableTimestampIsNotPublished() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes",\
                "timestamp":"soon",\
                "bids":[{"price":"0.08","size":"3"}],\
                "asks":[{"price":"0.10","size":"6"}],\
                "event_type":"book","last_trade_price":"0.09"}]
                """);

        assertEquals(0, captured.size());
    }

    /** Skipped, a change that can't be read would leave its level wrong; the reconnect brings a fresh snapshot. */
    @Test
    void unreadablePriceChangeFailsTheConnectionForAFreshSnapshot() throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody())
                .thenReturn(
                        ByteBuffer.wrap(("{\"market\":\"condition-1\",\"price_changes\":[{\"asset_id\":\"token-yes\","
                                        + "\"price\":\"0.08\",\"size\":\"\",\"side\":\"BUY\"}],\"timestamp\":\"1782753357300\","
                                        + "\"event_type\":\"price_change\"}")
                                .getBytes(StandardCharsets.UTF_8)));

        assertThrows(NumberFormatException.class, () -> reader.doWork());
    }

    // ========== Gaps ==========

    private static final String GAP_BOOK =
            """
            [{"market":"condition-1","asset_id":"token-yes","timestamp":"1782753357257",\
            "bids":[{"price":"0.08","size":"3"},{"price":"0.07","size":"4"}],\
            "asks":[{"price":"0.10","size":"6"}],"event_type":"book","last_trade_price":"0.09"}]
            """;

    private static String change(
            final String timestamp,
            final String side,
            final String price,
            final String size,
            final String bestBid,
            final String bestAsk) {
        return "{\"market\":\"condition-1\",\"price_changes\":[{\"asset_id\":\"token-yes\",\"price\":\"" + price
                + "\",\"size\":\"" + size + "\",\"side\":\"" + side + "\",\"hash\":\"h\",\"best_bid\":\"" + bestBid
                + "\",\"best_ask\":\"" + bestAsk + "\"}],\"timestamp\":\"" + timestamp
                + "\",\"event_type\":\"price_change\"}";
    }

    @Test
    void bestPricesThatAgreeAreNotAGap() throws Exception {
        process(GAP_BOOK);
        process(change("1782753357300", "BUY", "0.07", "5", "0.08", "0.10"));
        process(change("1782753357301", "SELL", "0.11", "2", "0.08", "0.10"));
        process(change("1782753357302", "BUY", "0.08", "0", "0.07", "0.10"));

        assertFalse(captured.get(captured.size() - 1).decoder.flags().badData());
    }

    /** One match's changes can come as several messages with one timestamp, each carrying the post-match prices. */
    @Test
    void disagreementWithinOneMatchIsNotAGap() throws Exception {
        process(GAP_BOOK);
        // The match removes 0.08 then 0.07; the venue's best bid (0.06) is right only once both have arrived.
        process(change("1782753357300", "BUY", "0.08", "0", "0.06", "0.10"));
        process(change("1782753357300", "BUY", "0.07", "0", "0.06", "0.10"));
        process(change("1782753357300", "BUY", "0.06", "9", "0.06", "0.10"));
        process(change("1782753357301", "SELL", "0.11", "2", "0.06", "0.10"));

        assertFalse(captured.get(captured.size() - 1).decoder.flags().badData());
    }

    @Test
    void missedChangePublishesTheBookAsSuspectAndDropsTheConnection() throws Exception {
        process(GAP_BOOK);
        // The venue's best bid is 0.09: a bid we were never told about.
        process(change("1782753357300", "BUY", "0.07", "5", "0.09", "0.10"));
        final int before = captured.size();

        when(client.read()).thenReturn(response);
        when(response.getBody())
                .thenReturn(ByteBuffer.wrap(change("1782753357301", "SELL", "0.11", "2", "0.09", "0.10")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(SocketClosedException.class, () -> reader.doWork());

        final long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline) {
            poller.poll();
        }
        assertEquals(before + 1, captured.size(), "one suspect book, then nothing");
        assertTrue(captured.get(before).decoder.flags().badData());
    }

    @Test
    void emptySideIsTheVenuesZeroOrOne() throws Exception {
        process(
                """
                [{"market":"condition-1","asset_id":"token-yes","timestamp":"1782753357257",\
                "bids":[{"price":"0.08","size":"3"}],"asks":[],"event_type":"book","last_trade_price":"0.09"}]
                """);
        process(change("1782753357300", "BUY", "0.08", "0", "0", "1"));
        process(change("1782753357301", "BUY", "0.05", "1", "0.05", "1"));

        assertFalse(captured.get(captured.size() - 1).decoder.flags().badData());
    }

    @Test
    void wholeBookClearsAPendingCheck() throws Exception {
        process(GAP_BOOK);
        process(change("1782753357300", "BUY", "0.07", "5", "0.09", "0.10"));
        // The venue sends its whole book again before the next change: it replaces ours, and the stale check.
        process(GAP_BOOK.replace("1782753357257", "1782753357301"));
        process(change("1782753357302", "SELL", "0.11", "2", "0.08", "0.10"));

        assertFalse(captured.get(captured.size() - 1).decoder.flags().badData());
    }

    private void process(String message) throws Exception {
        int before = captured.size();
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();

        long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline && !"PONG".equals(message)) {
            poller.poll();
        }
    }

    private long price(String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private long size(String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
