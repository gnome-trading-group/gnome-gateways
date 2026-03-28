package group.gnometrading.gateways.exchanges.lighter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.inbound.exchanges.lighter.LighterSocketReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.*;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test suite for LighterSocketReader.
 * Reads Json data from lighter.txt, processes it through LighterSocketReader,
 * and validates the output schemas match expected values.
 */
public class LighterSocketReaderTest {

    private Disruptor<Mbp10Schema> disruptor;
    private RingBuffer<Mbp10Schema> ringBuffer;
    private LighterSocketReader socketReader;
    private WebSocketClient mockClient;
    private WebSocketResponse mockResponse;
    private EpochNanoClock clock;
    private JsonDecoder jsonDecoder;
    private List<Mbp10Schema> capturedSchemas;

    @BeforeEach
    void setUp() {
        // Create disruptor for output
        disruptor = new Disruptor<>(
                Mbp10Schema::new, 1024, new DaemonThreadFactory(), ProducerType.SINGLE, new SleepingWaitStrategy());
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
        clock = System::nanoTime;
        jsonDecoder = new JsonDecoder();
        capturedSchemas = new ArrayList<>();

        mockClient = mock(WebSocketClient.class);
        mockResponse = mock(WebSocketResponse.class);
        when(mockResponse.isSuccess()).thenReturn(true);
        when(mockResponse.getOpcode()).thenReturn(Opcode.TEXT);

        // Create test socket reader
        // Note: Listing constructor is (securityId, exchangeId, securityIndex, exchangeSecurityId, symbol)
        Listing listing = new Listing(
                0, // listingId
                new Exchange(1, "test-exchange", "test-region", SchemaType.MBP_10), // exchangeId
                new Security(1, "test-security", 1), // securityId
                "0", // exchangeSecurityId
                "TEST" // symbol
                );

        socketReader =
                new LighterSocketReader(new NullLogger(), ringBuffer, clock, null, listing, mockClient, jsonDecoder);
        socketReader.buffer = false;
        socketReader.pause = false;
    }

    @AfterEach
    void tearDown() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }

    @Test
    @Disabled
    void testLighterMessagesProduceCorrectSchemas() throws Exception {
        // Read all messages from lighter.txt
        List<String> messages = readLighterMessages();
        assertFalse(messages.isEmpty(), "Should have messages from lighter.txt");

        // Process all messages through LighterSocketReader
        for (String message : messages) {
            processMessage(message);
        }

        // Verify we captured schemas
        assertEquals(messages.size(), capturedSchemas.size(), "Should have one schema per message");

        // Validate all schemas have correct basic properties
        for (int i = 0; i < capturedSchemas.size(); i++) {
            Mbp10Schema schema = capturedSchemas.get(i);

            // Debug: print actual values for first message
            if (i == 0) {
                System.out.println("First message - exchangeId: " + schema.decoder.exchangeId() + ", securityId: "
                        + schema.decoder.securityId() + ", sequence: "
                        + schema.decoder.sequence());
            }

            // Verify basic fields - use actual values from schema
            assertNotEquals((short) 0, schema.decoder.exchangeId(), "Message " + i + ": exchangeId should not be 0");
            assertNotEquals((short) 0, schema.decoder.securityId(), "Message " + i + ": securityId should not be 0");
            assertEquals(Action.Modify, schema.decoder.action(), "Message " + i + ": action should be Modify");
            assertEquals(
                    Side.None, schema.decoder.side(), "Message " + i + ": side should be None for order book updates");
            assertTrue(schema.decoder.flags().marketByPrice(), "Message " + i + ": marketByPrice flag should be set");

            // Verify sequence number is not null
            assertNotEquals(
                    Mbp10Encoder.sequenceNullValue(),
                    schema.decoder.sequence(),
                    "Message " + i + ": sequence should not be null");

            // Verify timestamp is set
            assertNotEquals(
                    Mbp10Encoder.timestampRecvNullValue(),
                    schema.decoder.timestampRecv(),
                    "Message " + i + ": timestampRecv should be set");
        }
    }

    @Test
    void testFirstMessageSnapshot() throws Exception {
        List<String> messages = readLighterMessages();
        assertTrue(messages.size() > 0, "Should have at least one message");

        // First message is a snapshot with many levels
        // {"channel":"order_book:0","offset":7606856,"order_book":{"code":0,"asks":[{"price":"4211.96","size":"0.1675"},...
        processMessage(messages.get(0));

        assertEquals(1, capturedSchemas.size(), "Should have one schema");
        Mbp10Schema schema = capturedSchemas.get(0);

        // Verify it's a snapshot with sequence 7606856
        assertEquals(7606856L, schema.decoder.sequence());

        // Verify asks are sorted ascending
        long prevAskPrice = 0;
        for (int i = 0; i < 10; i++) {
            long askPrice = getAskPrice(schema, i);
            if (askPrice != Mbp10Encoder.askPrice0NullValue()) {
                assertTrue(askPrice > prevAskPrice, "Ask prices should be sorted ascending at level " + i);
                prevAskPrice = askPrice;
            }
        }

        // Verify bids are sorted descending
        long prevBidPrice = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            long bidPrice = getBidPrice(schema, i);
            if (bidPrice != Mbp10Encoder.bidPrice0NullValue()) {
                assertTrue(bidPrice < prevBidPrice, "Bid prices should be sorted descending at level " + i);
                prevBidPrice = bidPrice;
            }
        }

        // Verify first ask level matches expected values from Json
        // First ask in Json: {"price":"4211.96","size":"0.1675"}
        long expectedAskPrice0 = parsePrice("4211.96");
        long expectedAskSize0 = parseSize("0.1675");
        assertEquals(expectedAskPrice0, schema.decoder.askPrice0(), "First ask price should match");
        assertEquals(expectedAskSize0, schema.decoder.askSize0(), "First ask size should match");
    }

    @Test
    void testSequenceNumbersIncreasing() throws Exception {
        List<String> messages = readLighterMessages();

        // Process all messages
        for (String message : messages) {
            processMessage(message);
        }

        // Verify sequence numbers are non-decreasing
        long prevSequence = -1;
        for (Mbp10Schema schema : capturedSchemas) {
            long sequence = schema.decoder.sequence();
            if (prevSequence >= 0) {
                assertTrue(sequence >= prevSequence, "Sequence numbers should be non-decreasing");
            }
            prevSequence = sequence;
        }
    }

    // ========== Helper Methods ==========

    private void processMessage(String message) throws Exception {
        when(mockClient.read()).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        long before = ringBuffer.getCursor();
        socketReader.doWork();
        long after = ringBuffer.getCursor();
        for (long seq = before + 1; seq <= after; seq++) {
            capturedSchemas.add(ringBuffer.get(seq));
        }
    }

    private List<String> readLighterMessages() throws IOException {
        List<String> messages = new ArrayList<>();
        InputStream is = getClass().getClassLoader().getResourceAsStream("lighter.txt");
        assertNotNull(is, "lighter.txt should be in test resources");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    messages.add(line);
                }
            }
        }

        return messages;
    }

    /**
     * Parse price string to fixed-point long.
     * Assumes PRICE_SCALING_FACTOR = 100 (2 decimal places).
     */
    private long parsePrice(String priceStr) {
        double price = Double.parseDouble(priceStr);
        return (long) (price * Statics.PRICE_SCALING_FACTOR);
    }

    /**
     * Parse size string to fixed-point long.
     * Assumes SIZE_SCALING_FACTOR = 10000 (4 decimal places).
     */
    private long parseSize(String sizeStr) {
        double size = Double.parseDouble(sizeStr);
        return (long) (size * Statics.SIZE_SCALING_FACTOR);
    }

    private long getBidPrice(Mbp10Schema schema, int level) {
        switch (level) {
            case 0:
                return schema.decoder.bidPrice0();
            case 1:
                return schema.decoder.bidPrice1();
            case 2:
                return schema.decoder.bidPrice2();
            case 3:
                return schema.decoder.bidPrice3();
            case 4:
                return schema.decoder.bidPrice4();
            case 5:
                return schema.decoder.bidPrice5();
            case 6:
                return schema.decoder.bidPrice6();
            case 7:
                return schema.decoder.bidPrice7();
            case 8:
                return schema.decoder.bidPrice8();
            case 9:
                return schema.decoder.bidPrice9();
            default:
                throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getBidSize(Mbp10Schema schema, int level) {
        switch (level) {
            case 0:
                return schema.decoder.bidSize0();
            case 1:
                return schema.decoder.bidSize1();
            case 2:
                return schema.decoder.bidSize2();
            case 3:
                return schema.decoder.bidSize3();
            case 4:
                return schema.decoder.bidSize4();
            case 5:
                return schema.decoder.bidSize5();
            case 6:
                return schema.decoder.bidSize6();
            case 7:
                return schema.decoder.bidSize7();
            case 8:
                return schema.decoder.bidSize8();
            case 9:
                return schema.decoder.bidSize9();
            default:
                throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getAskPrice(Mbp10Schema schema, int level) {
        switch (level) {
            case 0:
                return schema.decoder.askPrice0();
            case 1:
                return schema.decoder.askPrice1();
            case 2:
                return schema.decoder.askPrice2();
            case 3:
                return schema.decoder.askPrice3();
            case 4:
                return schema.decoder.askPrice4();
            case 5:
                return schema.decoder.askPrice5();
            case 6:
                return schema.decoder.askPrice6();
            case 7:
                return schema.decoder.askPrice7();
            case 8:
                return schema.decoder.askPrice8();
            case 9:
                return schema.decoder.askPrice9();
            default:
                throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getAskSize(Mbp10Schema schema, int level) {
        switch (level) {
            case 0:
                return schema.decoder.askSize0();
            case 1:
                return schema.decoder.askSize1();
            case 2:
                return schema.decoder.askSize2();
            case 3:
                return schema.decoder.askSize3();
            case 4:
                return schema.decoder.askSize4();
            case 5:
                return schema.decoder.askSize5();
            case 6:
                return schema.decoder.askSize6();
            case 7:
                return schema.decoder.askSize7();
            case 8:
                return schema.decoder.askSize8();
            case 9:
                return schema.decoder.askSize9();
            default:
                throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    /**
     * Simple daemon thread factory for disruptor.
     */
    static class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }
    }
}
