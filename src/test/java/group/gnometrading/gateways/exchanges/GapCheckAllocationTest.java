package group.gnometrading.gateways.exchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.inbound.InboundSocketReader;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiInboundReader;
import group.gnometrading.gateways.inbound.exchanges.lighter.LighterInboundReader;
import group.gnometrading.gateways.inbound.exchanges.polymarket.intl.PolymarketIntlInboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gap checks run on every market data message, so a feed that has no gap must read without allocating. Each
 * reader is fed a stream that is correctly numbered from end to end through a stub socket, and measured once warm.
 */
@Tag("allocation")
class GapCheckAllocationTest {

    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 10_000;
    private static final int MESSAGES = 1 + WARM_UP + MEASURED;

    @Test
    void kalshiSequenceCheckDoesNotAllocate() throws Exception {
        assertKalshiDoesNotAllocate("T:yes");
    }

    /** A NO listing writes the complement of the book; that must not allocate either. */
    @Test
    void kalshiNoListingDoesNotAllocate() throws Exception {
        assertKalshiDoesNotAllocate("T:no");
    }

    private static void assertKalshiDoesNotAllocate(final String securityId) throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        messages[0] = ascii("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,\"msg\":{\"market_ticker\":\"T\","
                + "\"yes_dollars_fp\":[[\"0.5500\",\"100.00\"]],\"no_dollars_fp\":[[\"0.4000\",\"50.00\"]]}}");
        for (int i = 1; i < messages.length; i++) {
            messages[i] =
                    ascii("{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + (i + 1) + ",\"msg\":{\"market_ticker\":"
                            + "\"T\",\"price_dollars\":\"0.550\",\"delta_fp\":\"" + (i % 2 == 0 ? "-1.00" : "1.00")
                            + "\",\"side\":\"yes\",\"ts_ms\":1700000000000}}");
        }
        final Feed feed = new Feed(messages);
        final KalshiInboundReader reader = new KalshiInboundReader(
                new NullLogger(),
                feed.ring,
                () -> 9_000_000_000L,
                null,
                listing("KALSHI", securityId),
                feed.client,
                new JsonDecoder(),
                "key",
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate());

        assertNoAllocation(feed, reader);
    }

    @Test
    void lighterNonceCheckDoesNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = ascii("{\"channel\":\"order_book:0\",\"offset\":" + i + ",\"order_book\":{\"code\":0,"
                    + "\"asks\":[{\"price\":\"4212.00\",\"size\":\"" + (i % 2 == 0 ? "2.0" : "3.0") + "\"}],"
                    + "\"bids\":[{\"price\":\"4209.00\",\"size\":\"3.0\"}],\"offset\":" + i + ",\"nonce\":" + (i + 1)
                    + ",\"begin_nonce\":" + (i == 0 ? 0 : i) + "},\"timestamp\":1761585003001,\"type\":\""
                    + (i == 0 ? "subscribed/order_book" : "update/order_book") + "\"}");
        }
        final Feed feed = new Feed(messages);
        final LighterInboundReader reader = new LighterInboundReader(
                new NullLogger(),
                feed.ring,
                () -> 9_000_000_000L,
                null,
                listing("LIGHTER", "0"),
                feed.client,
                new JsonDecoder());

        assertNoAllocation(feed, reader);
    }

    @Test
    void polymarketIntlBestPriceCheckDoesNotAllocate() throws Exception {
        final ByteBuffer[] messages = new ByteBuffer[MESSAGES];
        messages[0] = ascii("[{\"market\":\"c\",\"asset_id\":\"token\",\"timestamp\":\"1782753357000\","
                + "\"bids\":[{\"price\":\"0.08\",\"size\":\"3\"}],\"asks\":[{\"price\":\"0.10\",\"size\":\"6\"}],"
                + "\"event_type\":\"book\",\"last_trade_price\":\"0.09\"}]");
        for (int i = 1; i < messages.length; i++) {
            messages[i] =
                    ascii("{\"market\":\"c\",\"price_changes\":[{\"asset_id\":\"token\",\"price\":\"0.07\",\"size\":\""
                            + (i % 2 == 0 ? "0" : "5") + "\",\"side\":\"BUY\",\"hash\":\"h\",\"best_bid\":\"0.08\","
                            + "\"best_ask\":\"0.10\"}],\"timestamp\":\"" + (1782753357000L + i)
                            + "\",\"event_type\":\"price_change\"}");
        }
        final Feed feed = new Feed(messages);
        final PolymarketIntlInboundReader reader = new PolymarketIntlInboundReader(
                new NullLogger(),
                feed.ring,
                () -> 9_000_000_000L,
                null,
                listing("POLYMARKET_INTL", "c:token"),
                feed.client,
                new JsonDecoder());

        assertNoAllocation(feed, reader);
    }

    private static void assertNoAllocation(final Feed feed, final InboundSocketReader<Mbp10Schema> reader)
            throws Exception {
        reader.buffer = false;
        reader.pauseControl.release();
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long threadId = Thread.currentThread().getId();

        read(feed, reader, 1 + WARM_UP);
        final long before = threads.getThreadAllocatedBytes(threadId);
        read(feed, reader, MEASURED);
        final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        assertEquals(MESSAGES, feed.client.served, "every message was read, none failed the check");
        // Measuring can itself allocate a little; anything per message would be hundreds of kilobytes here.
        assertTrue(allocated < 2_048, "allocated " + allocated + " bytes over " + MEASURED + " messages");
    }

    private static void read(final Feed feed, final InboundSocketReader<Mbp10Schema> reader, final int count)
            throws Exception {
        for (int i = 0; i < count; i++) {
            reader.doWork();
            feed.poller.poll();
        }
    }

    private static Listing listing(final String exchangeCode, final String securityId) {
        return new Listing(
                1,
                new Exchange(2, exchangeCode, exchangeCode, "global", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                securityId,
                securityId);
    }

    private static ByteBuffer ascii(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Feed {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final AtomicLong published = new AtomicLong();
        final SequencedPoller poller =
                ring.createPoller((seq, templateId, buffer, length) -> published.incrementAndGet());
        final ReplayClient client;

        Feed(final ByteBuffer[] messages) throws IOException {
            this.client = new ReplayClient(messages);
        }
    }

    /** Hands out one prepared message per read, in order, without touching a socket. */
    private static final class ReplayClient extends WebSocketClient {
        private final ByteBuffer[] messages;
        private final Response response = new Response();
        int served;

        ReplayClient(final ByteBuffer[] messages) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 16, 1 << 16);
            this.messages = messages;
        }

        @Override
        public WebSocketResponse read() {
            if (this.served == this.messages.length) {
                return this.response.update(true, Opcode.TEXT, null, false);
            }
            final ByteBuffer message = this.messages[this.served++];
            message.position(0);
            return this.response.update(true, Opcode.TEXT, message, false);
        }
    }

    private static final class Response extends WebSocketResponse {}
}
