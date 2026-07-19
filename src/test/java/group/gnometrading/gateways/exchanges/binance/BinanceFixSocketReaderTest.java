package group.gnometrading.gateways.exchanges.binance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import group.gnometrading.gateways.fix.FixConfig;
import group.gnometrading.gateways.fix.FixMessage;
import group.gnometrading.gateways.fix.FixSocketMessageClient;
import group.gnometrading.gateways.fix.FixTimestampPrecision;
import group.gnometrading.gateways.fix.FixVersion;
import group.gnometrading.gateways.fix.fix50sp2.Fix50Sp2Tags;
import group.gnometrading.gateways.inbound.exchanges.binance.BinanceFixSocketReader;
import group.gnometrading.gateways.inbound.exchanges.binance.BinanceFixTags;
import group.gnometrading.logging.NullLogger;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BinanceFixSocketReaderTest {

    private static final String SENDER_COMP_ID = "TEST";
    private static final String TARGET_COMP_ID = "SPOT";
    private static final long PRICE_SCALE = Statics.PRICE_SCALING_FACTOR;
    private static final long SIZE_SCALE = Statics.SIZE_SCALING_FACTOR;

    private SequencedRingBuffer<Mbp10Schema> sequencedRingBuffer;
    private BinanceFixSocketReader reader;
    private FixSocketMessageClient mockFixClient;
    private List<Mbp10Schema> capturedSchemas;
    private FixConfig builderConfig;
    private ByteBuffer mockWriteBuffer;
    private int seqNum;

    @BeforeEach
    void setUp() throws Exception {
        capturedSchemas = new CopyOnWriteArrayList<>();
        sequencedRingBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        sequencedRingBuffer.handleEventsWith((gSeq, templateId, buffer, length) -> {
            Mbp10Schema captured = new Mbp10Schema();
            captured.buffer.putBytes(0, buffer, 0, length);
            captured.wrap(captured.buffer);
            capturedSchemas.add(captured);
        });
        sequencedRingBuffer.start();

        EpochNanoClock clock = System::nanoTime;

        mockFixClient = mock(FixSocketMessageClient.class);
        mockWriteBuffer = ByteBuffer.allocate(4096);
        when(mockFixClient.getWriteBuffer()).thenReturn(mockWriteBuffer);
        when(mockFixClient.write(any(ByteBuffer.class), anyInt())).thenAnswer(inv -> {
            ByteBuffer buf = inv.getArgument(0);
            int pos = buf.position();
            buf.clear();
            return pos;
        });
        when(mockFixClient.getReadBuffer()).thenReturn(ByteBuffer.allocate(65536));

        FixConfig config = new FixConfig.Builder()
                .withSessionVersion(FixVersion.FIX_4_4)
                .withApplicationVersion(FixVersion.FIX_4_4)
                .withSenderCompID(SENDER_COMP_ID)
                .withTargetCompID(TARGET_COMP_ID)
                .withHeartbeatSeconds(30)
                .withDefaultPrecision(FixTimestampPrecision.MILLISECONDS)
                .build();

        builderConfig = new FixConfig.Builder()
                .withSessionVersion(FixVersion.FIX_4_4)
                .withApplicationVersion(FixVersion.FIX_4_4)
                .withSenderCompID(TARGET_COMP_ID)
                .withTargetCompID(SENDER_COMP_ID)
                .withHeartbeatSeconds(30)
                .withDefaultPrecision(FixTimestampPrecision.MILLISECONDS)
                .build();

        Listing listing = new Listing(
                0,
                new Exchange(1, "binance", "us-east-1", SchemaType.MBP_10),
                new Security(1, "BTCUSDT", 1),
                "1",
                "BTCUSDT");

        reader = new BinanceFixSocketReader(
                new NullLogger(), sequencedRingBuffer, clock, mockFixClient, listing, config, null, "TEST_API_KEY");

        reader.buffer = false;
        reader.pause = false;
        seqNum = 1;
    }

    @AfterEach
    void tearDown() {
        if (sequencedRingBuffer != null) {
            sequencedRingBuffer.shutdown();
        }
    }

    @Test
    void testBidUpdateActionAndSide() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addBookEntry(m, '0', '0', "50000.00", "1.50000"));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(Action.Modify, schema.decoder.action());
        assertEquals(Side.None, schema.decoder.side());
        assertTrue(schema.decoder.flags().marketByPrice());
    }

    @Test
    void testAskUpdateActionAndSide() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addBookEntry(m, '0', '1', "50001.00", "1.00000"));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(Action.Modify, schema.decoder.action());
        assertEquals(Side.None, schema.decoder.side());
    }

    @Test
    void testSequenceNumber() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addBookEntry(m, '0', '0', "50000.00", "1.50000"));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        assertEquals(1005L, capturedSchemas.get(0).decoder.sequence());
    }

    @Test
    void testBidAndAskLevels() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> {
            addBookEntry(m, '0', '0', "50000.00", "1.50000");
            addBookEntry(m, '0', '1', "50001.00", "1.00000");
        });

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(parsePrice("50000.00"), schema.decoder.bidPrice0());
        assertEquals(parseSize("1.50000"), schema.decoder.bidSize0());
        assertEquals(parsePrice("50001.00"), schema.decoder.askPrice0());
        assertEquals(parseSize("1.00000"), schema.decoder.askSize0());
    }

    @Test
    void testDeleteLevelWhenActionIsDelete() throws Exception {
        processMessage(buildXMessage(seqNum++, 1005L, m -> addBookEntry(m, '0', '0', "50000.00", "1.50000")));
        processMessage(buildXMessage(seqNum++, 1010L, m -> addBookEntry(m, '2', '0', "50000.00", "0.00000")));

        assertEquals(2, capturedSchemas.size());
        assertEquals(
                Mbp10Encoder.bidPrice0NullValue(),
                capturedSchemas.get(1).decoder.bidPrice0());
    }

    @Test
    void testTradeAggressorBid() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addTradeEntry(m, "50001.00", "0.10000", 1));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(Action.Trade, schema.decoder.action());
        assertEquals(Side.Bid, schema.decoder.side());
        assertTrue(schema.decoder.flags().marketByPrice());
    }

    @Test
    void testTradeAggressorAsk() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addTradeEntry(m, "50000.50", "0.05000", 2));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        assertEquals(Side.Ask, capturedSchemas.get(0).decoder.side());
    }

    @Test
    void testTradePriceAndSize() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> addTradeEntry(m, "50001.00", "0.10000", 1));

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(parsePrice("50001.00"), schema.decoder.price());
        assertEquals(parseSize("0.10000"), schema.decoder.size());
    }

    @Test
    void testOutsideTop10NotEmitted() throws Exception {
        FixMessage fillMsg = buildXMessage(seqNum++, 1000L, m -> {
            for (int i = 10; i >= 1; i--) {
                addBookEntry(m, '0', '0', "5000" + i + ".00", "1.00000");
            }
        });
        processMessage(fillMsg);
        int afterFill = capturedSchemas.size();

        FixMessage outsideMsg = buildXMessage(seqNum++, 1010L, m -> addBookEntry(m, '0', '0', "49990.00", "5.00000"));
        processMessageNoWait(outsideMsg);
        Thread.sleep(50);

        assertEquals(afterFill, capturedSchemas.size());
    }

    @Test
    void testMultipleBidLevels() throws Exception {
        FixMessage msg = buildXMessage(seqNum++, 1005L, m -> {
            addBookEntry(m, '0', '0', "50000.00", "1.50000");
            addBookEntry(m, '0', '0', "49999.00", "2.00000");
        });

        processMessage(msg);

        assertEquals(1, capturedSchemas.size());
        Mbp10Schema schema = capturedSchemas.get(0);
        assertEquals(parsePrice("50000.00"), schema.decoder.bidPrice0());
        assertEquals(parsePrice("49999.00"), schema.decoder.bidPrice1());
    }

    // ========== Helpers ==========

    private FixMessage buildXMessage(int msgSeqNum, long lastBookUpdateId, Consumer<FixMessage> entryBuilder) {
        FixMessage msg = new FixMessage(builderConfig);
        msg.addTag(Fix50Sp2Tags.MsgType).setChar('X');
        msg.addTag(Fix50Sp2Tags.SenderCompID).setString(TARGET_COMP_ID);
        msg.addTag(Fix50Sp2Tags.TargetCompID).setString(SENDER_COMP_ID);
        msg.addTag(Fix50Sp2Tags.MsgSeqNum).setInt(msgSeqNum);
        msg.addTag(Fix50Sp2Tags.SendingTime)
                .setTimestamp(System.currentTimeMillis(), FixTimestampPrecision.MILLISECONDS);
        msg.addTag(BinanceFixTags.LastBookUpdateID).setInt((int) lastBookUpdateId);
        entryBuilder.accept(msg);

        ByteBuffer buf = ByteBuffer.allocate(4096);
        msg.writeToBuffer(buf);
        buf.flip();

        FixMessage parsed = new FixMessage(builderConfig);
        assertTrue(parsed.parseBuffer(buf), "Failed to parse built FIX message");
        return parsed;
    }

    private void addBookEntry(FixMessage msg, char action, char type, String price, String size) {
        msg.addTag(Fix50Sp2Tags.MDUpdateAction).setChar(action);
        msg.addTag(Fix50Sp2Tags.MDEntryType).setChar(type);
        msg.addTag(Fix50Sp2Tags.MDEntryPx).setString(price);
        msg.addTag(Fix50Sp2Tags.MDEntrySize).setString(size);
    }

    private void addTradeEntry(FixMessage msg, String price, String size, int aggressorSide) {
        msg.addTag(Fix50Sp2Tags.MDUpdateAction).setChar('0');
        msg.addTag(Fix50Sp2Tags.MDEntryType).setChar('2');
        msg.addTag(Fix50Sp2Tags.MDEntryPx).setString(price);
        msg.addTag(Fix50Sp2Tags.MDEntrySize).setString(size);
        msg.addTag(BinanceFixTags.AggressorSide).setInt(aggressorSide);
    }

    private void processMessage(FixMessage fixMessage) throws Exception {
        int before = capturedSchemas.size();
        when(mockFixClient.getMessage()).thenReturn(fixMessage);
        when(mockFixClient.readMessage(any())).thenReturn(1, 0);
        reader.doWork();
        long deadline = System.currentTimeMillis() + 1000;
        while (capturedSchemas.size() == before && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private void processMessageNoWait(FixMessage fixMessage) throws Exception {
        when(mockFixClient.getMessage()).thenReturn(fixMessage);
        when(mockFixClient.readMessage(any())).thenReturn(1, 0);
        reader.doWork();
    }

    private long parsePrice(String price) {
        return (long) (Double.parseDouble(price) * PRICE_SCALE);
    }

    private long parseSize(String size) {
        return (long) (Double.parseDouble(size) * SIZE_SCALE);
    }
}
