package group.gnometrading.gateways.account;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.reader.AccountFrames;
import group.gnometrading.gateways.account.reader.AccountProtocol;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.gateways.venues.kalshi.KalshiAccountProtocol;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.kalshi.KalshiLaneVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlLaneVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsLaneVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarket;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarketInfo;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The account path's latency per venue, signing included: a lane pass from a NEW command to its request written to a
 * real connection (to a local server), and a reader's frame to its report. Prints percentiles and allocation, as the
 * other benchmarks do. Not part of {@code mvn test}: {@code mvn test-compile surefire:test@benchmark
 * -Dtest=AccountLatencyBenchmark}. Compare runs on one machine.
 */
@Tag("benchmark")
class AccountLatencyBenchmark {

    private static final long SLOT = 7;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final int WARM_UP = 500;
    private static final int MEASURED = 2_000;
    // Hardhat account #0: a well-known test key, never funded on Polygon.
    private static final byte[] PM_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String PM_EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String PM_SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==";

    @Test
    void kalshi() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KalshiAuthSigner signer =
                new KalshiAuthSigner("key", generator.generateKeyPair().getPrivate());
        final KalshiLaneVenue venue = new KalshiLaneVenue(new NullLogger(), signer, "maker");
        final KalshiMarket market = KalshiMarket.of(listing("KALSHI", "KXT-1:yes", "KXT-1"));
        market.exchangeIndex(1);
        venue.addMarket(EXCHANGE_ID, SECURITY_ID, market);
        venue.addSession(SLOT, "bench");
        lane(
                "Kalshi lane: NEW to request on the wire",
                venue,
                request -> new Reply(201, "{\"order\":{\"order_id\":\"0a1b2c\",\"client_order_id\":\"bench-1\"}}", 0));

        final KalshiAccountProtocol protocol =
                new KalshiAccountProtocol(signer, null, "api.test", SystemEpochNanoClock.INSTANCE);
        protocol.addMarket(EXCHANGE_ID, SECURITY_ID, FeeTimeline.EMPTY);
        reader(
                "Kalshi reader",
                protocol,
                "alpha-1",
                3_000_000L,
                AccountFrames.kalshi("alpha-1", "resting", "0.00", "3.00"),
                AccountFrames.kalshi("alpha-1", "executed", "3.00", "0.00"));
    }

    @Test
    void polymarketUs() throws Exception {
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key", PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(new byte[32])));
        final Listing listing = listing("POLYMARKET_US", "slug-a:long", "slug-a");
        final PolymarketUsLaneVenue venue = new PolymarketUsLaneVenue(new NullLogger(), signer);
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                new PolymarketUsMarket(
                        listing,
                        new PolymarketUsMarketInfo(
                                Statics.PRICE_SCALING_FACTOR / 100, Statics.SIZE_SCALING_FACTOR / 100)));
        lane("Polymarket US lane: NEW to request on the wire", venue, request -> Reply.ok("{\"id\":\"ord-1\"}"));

        final PolymarketUsAccountProtocol protocol = new PolymarketUsAccountProtocol(
                new NullLogger(),
                signer,
                List.of(new PolymarketUsVenueOrderQuery(null, "api.test", signer, System::currentTimeMillis)),
                SystemEpochNanoClock.INSTANCE,
                64);
        protocol.addMarket(EXCHANGE_ID, SECURITY_ID, listing);
        final String stamp = "2100-01-05T03:46:40.007Z";
        reader(
                "Polymarket US reader",
                protocol,
                stamp,
                3_000_000L,
                AccountFrames.pmUs("EXECUTION_TYPE_NEW", "ORDER_STATE_NEW", 0, stamp, "0.0000"),
                AccountFrames.pmUs("EXECUTION_TYPE_FILL", "ORDER_STATE_FILLED", 3, stamp, "3.0000"));
    }

    @Test
    void polymarketIntl() throws Exception {
        final String token = "71321045679252212594626385532706912750332728571942532289631379312455583992563";
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                new NullLogger(),
                new PolymarketIntlOrderSigner(PM_KEY, PM_EOA, PM_EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA),
                new PolymarketIntlAuthHeaders("key", PM_SECRET, "pass", PM_EOA));
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                PolymarketIntlMarket.of(
                        listing("POLYMARKET", "0xcondition:" + token, "TEST-YES"),
                        new PolymarketIntlMarketInfo(Statics.PRICE_SCALING_FACTOR / 100, 1_000_000L, false, false, 0)));
        lane(
                "Polymarket lane: NEW to request on the wire",
                venue,
                request -> Reply.ok("{\"success\":true,\"errorMsg\":\"\",\"status\":\"live\"}"));

        final PolymarketIntlAccountProtocol protocol = new PolymarketIntlAccountProtocol(
                new NullLogger(), "key", "c2VjcmV0", "pass", null, SystemEpochNanoClock.INSTANCE);
        protocol.addMarket(EXCHANGE_ID, SECURITY_ID, "token-a", FeeTimeline.EMPTY);
        final String hash = "0x" + "aa".repeat(32);
        reader(
                "Polymarket reader",
                protocol,
                hash,
                10_000_000L,
                AccountFrames.pmIntl(hash, "PLACEMENT", "LIVE"),
                AccountFrames.pmIntl(hash, "CANCELLATION", "CANCELED"));
    }

    /** One lane pass from a queued NEW to its request on a connection; the answer and release, untimed. */
    private static <M> void lane(
            final String name, final LaneVenue<M> venue, final Function<FakeVenueServer.Request, Reply> answers)
            throws Exception {
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 256);
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final MessageConsumer<OrderContext> ignore = ctx -> {};
        try (FakeVenueServer server = new FakeVenueServer();
                AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                        .withPort(server.port())
                        .withConnections(2)
                        .build()) {
            server.script(answers::apply);
            final HTTPConnector connector = new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE);
            final AccountLane<M> lane = new AccountLane<>(
                    "bench",
                    venue,
                    connections,
                    new AccountBudget(
                            VenueRateLimits.of(
                                    "Bench",
                                    List.of(new VenueRateLimits.Bucket("all", 1_000_000, 1_000, 0.2)),
                                    List.of(new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                                    List.of()),
                            System.nanoTime()),
                    AccountLane.Timing.defaults(),
                    System::nanoTime,
                    SystemEpochClock.INSTANCE,
                    16,
                    2048,
                    512,
                    commands,
                    notices,
                    released);
            final int[] sent = {0};
            OrderPathTestKit.timeSection(
                    name,
                    WARM_UP,
                    MEASURED,
                    () -> {
                        // A connection open and idle, so the pass measured sends.
                        while (connections.acquire() == AsyncHTTPConnections.NONE) {
                            connector.doWork();
                        }
                        newOrder(commands);
                    },
                    lane::doWork,
                    () -> {
                        sent[0]++;
                        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (server.received() < sent[0] || connections.inFlight() != 0) {
                            if (System.nanoTime() > deadline) {
                                throw new AssertionError(name + ": no answer");
                            }
                            connector.doWork();
                            lane.doWork();
                            notices.read(ignore, Integer.MAX_VALUE);
                        }
                        final int idx = released.tryClaim();
                        final OrderContext release = released.indexAt(idx);
                        release.reset();
                        release.sessionSlot = SLOT;
                        release.clientOidCounter = 1;
                        released.commit(idx);
                        while (lane.workingOrders() != 0) {
                            lane.doWork();
                        }
                        notices.read(ignore, Integer.MAX_VALUE);
                    });
        }
    }

    /** A frame from arrival to its report: the acknowledgement, then the frame that ends the order. */
    private static void reader(
            final String name,
            final AccountProtocol<?> protocol,
            final String correlationId,
            final long size,
            final ByteBuffer acknowledged,
            final ByteBuffer ended) {
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<AccountReport> reports =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
        final AccountReader reader =
                new AccountReader(protocol, SystemEpochNanoClock.INSTANCE, 16, notices, released, reports);
        final ReaderNotices lane = new ReaderNotices(notices);
        final OrderContext order = RecordedReports.order(SLOT, 1, EXCHANGE_ID, SECURITY_ID, correlationId, size);
        order.side = Side.Bid;
        order.limitPrice = 550_000_000L;
        final byte[] id = correlationId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, order.exchangeOrderIdBytes, 0, id.length);
        order.exchangeOrderIdLength = id.length;
        final MessageConsumer<AccountReport> ignoreReport = report -> {};
        final MessageConsumer<OrderContext> ignoreRelease = ctx -> {};
        final Runnable register = () -> {
            lane.register(order);
            reader.beforeRead();
        };
        final Runnable acknowledge = () -> {
            acknowledged.rewind();
            reader.onFrame(acknowledged, System.nanoTime());
        };
        final Runnable end = () -> {
            ended.rewind();
            reader.onFrame(ended, System.nanoTime());
        };
        final Runnable drain = () -> {
            reports.read(ignoreReport, Integer.MAX_VALUE);
            released.read(ignoreRelease, Integer.MAX_VALUE);
        };
        OrderPathTestKit.timeSection(
                name + ": acknowledging frame to report", WARM_UP, MEASURED, register, acknowledge, () -> {
                    end.run();
                    drain.run();
                });
        OrderPathTestKit.timeSection(
                name + ": ending frame to report",
                WARM_UP,
                MEASURED,
                () -> {
                    register.run();
                    acknowledge.run();
                },
                end,
                drain);
    }

    private static void newOrder(final ManyToOneRingBuffer<LaneCommand> commands) {
        final int idx = commands.tryClaim();
        final LaneCommand command = commands.indexAt(idx);
        command.reset();
        command.kind = LaneCommand.NEW;
        command.sessionSlot = SLOT;
        command.clientOidCounter = 1;
        command.clientOidStrategyId = 1;
        command.exchangeId = EXCHANGE_ID;
        command.securityId = SECURITY_ID;
        command.side = Side.Bid;
        command.price = 500_000_000L;
        command.size = 10_000_000L;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        command.postOnly = true;
        commands.commit(idx);
    }

    private static Listing listing(final String exchange, final String exchangeSecurityId, final String symbol) {
        return new Listing(
                1,
                new Exchange(EXCHANGE_ID, exchange, exchange, "global"),
                new Security(
                        (int) SECURITY_ID, symbol, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                symbol);
    }
}
