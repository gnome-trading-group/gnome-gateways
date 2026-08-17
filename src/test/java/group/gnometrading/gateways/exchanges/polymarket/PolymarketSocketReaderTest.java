package group.gnometrading.gateways.exchanges.polymarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.inbound.exchanges.polymarket.PolymarketSocketReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Encoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketSocketReaderTest {

    private static final String TOKEN_ID = "token-yes";

    private SequencedRingBuffer<Mbp10Schema> ringBuffer;
    private PolymarketSocketReader reader;
    private WebSocketClient client;
    private WebSocketResponse response;
    private List<Mbp10Schema> captured;

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        ringBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        ringBuffer.handleEventsWith((globalSequence, templateId, buffer, length) -> {
            Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        ringBuffer.start();

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        Listing listing = new Listing(
                7,
                new Exchange(2, "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                "condition-1:" + TOKEN_ID,
                "TEST-YES");
        reader = new PolymarketSocketReader(
                new NullLogger(), ringBuffer, () -> 9_000_000_000L, null, listing, client, new JsonDecoder());
        reader.buffer = false;
        reader.pause = false;
    }

    @AfterEach
    void tearDown() {
        ringBuffer.shutdown();
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

    private void process(String message) throws Exception {
        int before = captured.size();
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();

        long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline && !"PONG".equals(message)) {
            Thread.yield();
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
