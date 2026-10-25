package group.gnometrading.gateways.exchanges.hyperliquid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.hyperliquid.HyperliquidInboundReader;
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
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HyperliquidInboundReaderTest {

    private static final String BOOK =
            "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":1700000000000," + "\"levels\":[[%s],[%s]]}}";

    private SequencedPoller poller;
    private HyperliquidInboundReader reader;
    private WebSocketClient client;
    private WebSocketResponse response;
    private List<Mbp10Schema> captured;

    @BeforeEach
    void setUp() throws Exception {
        captured = new CopyOnWriteArrayList<>();
        final SequencedRingBuffer<Mbp10Schema> ringBuffer =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        poller = ringBuffer.createPoller((globalSequence, templateId, buffer, length) -> {
            final Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        final Listing listing = new Listing(
                1,
                new Exchange(1, "HYPERLIQUID", "Hyperliquid", "global"),
                new Security(3, "ETH", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "ETH",
                "ETH");
        reader = new HyperliquidInboundReader(
                new NullLogger(),
                ringBuffer,
                () -> 1_700_000_000_123_000_000L,
                new InboundJsonWebSocketWriter(client, new JsonEncoder()),
                listing,
                77,
                client,
                new JsonDecoder());
        reader.buffer = false;
        reader.pauseControl.release();
    }

    @Test
    void bookMessagePublishesTheBook() throws Exception {
        process(book(level("3000.5", "1.25"), level("3001", "2")));

        final Mbp10Schema schema = captured.get(0);
        assertEquals(price("3000.5"), schema.decoder.bidPrice0());
        assertEquals(size("1.25"), schema.decoder.bidSize0());
        assertEquals(price("3001"), schema.decoder.askPrice0());
        assertEquals(1_700_000_000_000_000_000L, schema.decoder.timestampEvent());
        assertEquals(Action.Modify, schema.decoder.action());
    }

    /** Every message is the whole book, so one that can't be read is dropped and the next replaces it. */
    @Test
    void bookWithAnUnreadableLevelIsNotPublishedAndTheNextOneIs() throws Exception {
        processNoEmit(book(level("3000.5", "1.25") + "," + level("n/a", "1"), level("3001", "2")));
        assertEquals(0, captured.size(), "a book with a hole isn't published");

        process(book(level("3000.4", "1"), level("3001", "2")));
        assertEquals(1, captured.size(), "the feed carries on");
        assertEquals(price("3000.4"), captured.get(0).decoder.bidPrice0());
        assertEquals(Mbp10Encoder.bidPrice1NullValue(), captured.get(0).decoder.bidPrice1());
    }

    @Test
    void tradesAfterTheFirstBatchArePublished() throws Exception {
        processNoEmit(trades(trade("B", "3000", "0.5")));
        process(trades(trade("A", "3000.1", "0.25")));

        final Mbp10Schema schema = captured.get(0);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Ask, schema.decoder.side());
        assertEquals(price("3000.1"), schema.decoder.price());
        assertEquals(size("0.25"), schema.decoder.size());
    }

    @Test
    void tradeWithAnUnreadablePriceIsNeitherPublishedNorKept() throws Exception {
        processNoEmit(trades(trade("B", "3000", "0.5")));
        processNoEmit(trades(trade("A", "", "0.25")));
        assertEquals(0, captured.size());

        process(book(level("3000.5", "1.25"), level("3001", "2")));
        assertEquals(price("3000"), captured.get(0).decoder.price(), "the last good trade is still the last trade");
    }

    private static String book(final String bids, final String asks) {
        return String.format(BOOK, bids, asks);
    }

    private static String level(final String px, final String sz) {
        return "{\"px\":\"" + px + "\",\"sz\":\"" + sz + "\",\"n\":1}";
    }

    private static String trades(final String trade) {
        return "{\"channel\":\"trades\",\"data\":[" + trade + "]}";
    }

    private static String trade(final String side, final String px, final String sz) {
        return "{\"coin\":\"ETH\",\"side\":\"" + side + "\",\"px\":\"" + px + "\",\"sz\":\"" + sz
                + "\",\"time\":1700000000001,\"tid\":1}";
    }

    private void processNoEmit(final String message) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
        Thread.sleep(50);
        poller.poll();
    }

    private void process(final String message) throws Exception {
        final int before = captured.size();
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
        final long deadline = System.currentTimeMillis() + 1_000;
        while (captured.size() == before && System.currentTimeMillis() < deadline) {
            poller.poll();
        }
    }

    private static long price(final String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long size(final String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
