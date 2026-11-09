package group.gnometrading.gateways.account.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.gateways.venues.kalshi.KalshiAccountProtocol;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Side;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An order's whole life on an account's reader, per venue: handed over by the lane, acknowledged and ended by the
 * venue's frames, reported and released, over and over, allocating nothing on the reader's thread.
 */
@Tag("allocation")
class AccountReaderAllocationTest {

    private static final long SLOT = 7;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;

    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<AccountReport> reportQueue =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
    private final ReaderNotices notices = new ReaderNotices(this.noticeQueue);
    private int reports;
    private int releases;
    private final MessageConsumer<AccountReport> countReport = report -> this.reports++;
    private final MessageConsumer<OrderContext> countRelease = ctx -> this.releases++;
    private final OrderContext order = new OrderContext();

    @Test
    void kalshi() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KalshiAccountProtocol protocol = new KalshiAccountProtocol(
                new KalshiAuthSigner("key", generator.generateKeyPair().getPrivate()),
                null,
                "api.test",
                SystemEpochNanoClock.INSTANCE);
        protocol.addMarket(EXCHANGE_ID, SECURITY_ID, FeeTimeline.EMPTY);
        prepare("alpha-7", 3_000_000L);
        run(
                "Kalshi",
                protocol,
                AccountFrames.kalshi("alpha-7", "resting", "0.00", "3.00"),
                AccountFrames.kalshi("alpha-7", "executed", "3.00", "0.00"),
                2);
    }

    @Test
    void polymarketUs() throws Exception {
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key", PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(new byte[32])));
        final PolymarketUsAccountProtocol protocol = new PolymarketUsAccountProtocol(
                new NullLogger(),
                signer,
                List.of(new PolymarketUsVenueOrderQuery(null, "api.test", signer, System::currentTimeMillis)),
                SystemEpochNanoClock.INSTANCE,
                64);
        protocol.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                new Listing(
                        1,
                        new Exchange(EXCHANGE_ID, "POLYMARKET_US", "Polymarket US", "us"),
                        new Security(3, "slug-a", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                        "slug-a:long",
                        "slug-a"));
        final String stamp = "2100-01-05T03:46:40.007Z";
        prepare(stamp, 3_000_000L);
        this.order.limitPrice = 550_000_000L;
        run(
                "Polymarket US",
                protocol,
                AccountFrames.pmUs("EXECUTION_TYPE_NEW", "ORDER_STATE_NEW", 0, stamp, "0.0000"),
                AccountFrames.pmUs("EXECUTION_TYPE_FILL", "ORDER_STATE_FILLED", 3, stamp, "3.0000"),
                2);
    }

    @Test
    void polymarketIntl() {
        final PolymarketIntlAccountProtocol protocol = new PolymarketIntlAccountProtocol(
                new NullLogger(), "key", "c2VjcmV0", "pass", null, SystemEpochNanoClock.INSTANCE);
        protocol.addMarket(EXCHANGE_ID, SECURITY_ID, "token-a", FeeTimeline.EMPTY);
        final String hash = "0x" + "aa".repeat(32);
        prepare(hash, 10_000_000L);
        run(
                "Polymarket International",
                protocol,
                AccountFrames.pmIntl(hash, "PLACEMENT", "LIVE"),
                AccountFrames.pmIntl(hash, "CANCELLATION", "CANCELED"),
                2);
    }

    private void prepare(final String correlationId, final long size) {
        this.order.reset();
        this.order.sessionSlot = SLOT;
        this.order.clientOidCounter = 1;
        this.order.clientOidStrategyId = 1;
        this.order.exchangeId = EXCHANGE_ID;
        this.order.securityId = SECURITY_ID;
        this.order.side = Side.Bid;
        this.order.originalQty = size;
        this.order.leavesQty = size;
        final byte[] id = correlationId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, this.order.correlationIdBytes, 0, id.length);
        System.arraycopy(id, 0, this.order.exchangeOrderIdBytes, 0, id.length);
        this.order.correlationIdLength = id.length;
        this.order.exchangeOrderIdLength = id.length;
    }

    private void run(
            final String name,
            final AccountProtocol<?> protocol,
            final ByteBuffer acknowledged,
            final ByteBuffer ended,
            final int reportsPerOrder) {
        final AccountReader reader = new AccountReader(
                protocol,
                SystemEpochNanoClock.INSTANCE,
                16,
                this.noticeQueue,
                this.releasedOrderQueue,
                this.reportQueue);
        final Runnable life = () -> {
            this.notices.register(this.order);
            reader.beforeRead();
            acknowledged.rewind();
            reader.onFrame(acknowledged, System.nanoTime());
            ended.rewind();
            reader.onFrame(ended, System.nanoTime());
            this.reportQueue.read(this.countReport, Integer.MAX_VALUE);
            this.releasedOrderQueue.read(this.countRelease, Integer.MAX_VALUE);
        };
        OrderPathTestKit.assertNoAllocation(name + " reader order life", 2_000, 10_000, life);
        assertEquals(12_000 * reportsPerOrder, this.reports, name + ": every life was reported");
        assertEquals(12_000, this.releases, name + ": every life was released");
        assertEquals(0, reader.heldOrders());
    }
}
