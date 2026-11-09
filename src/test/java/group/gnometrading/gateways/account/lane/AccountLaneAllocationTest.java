package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.kalshi.KalshiLaneVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiMarket;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An order's whole trip through the lane, Kalshi's signing and answers included, over real connections to a local
 * server: submitted, accepted, cancelled and released, over and over, allocating nothing on the lane's thread.
 */
@Tag("allocation")
class AccountLaneAllocationTest {

    private static final long SLOT = 5;
    private static final long COUNTER = 7;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private FakeVenueServer server;
    private AsyncHTTPConnections connections;
    private HTTPConnector connector;
    private AccountLane<KalshiMarket> lane;
    private final ManyToOneRingBuffer<LaneCommand> commands =
            new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private int notices;
    private final MessageConsumer<OrderContext> countNotice = ctx -> {
        if (!ctx.registration) {
            this.notices++;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        this.server = new FakeVenueServer();
        this.server.script(request -> request.method().equals("POST")
                ? new Reply(201, "{\"order\":{\"order_id\":\"0a1b2c\",\"client_order_id\":\"smoke-7\"}}", 0)
                : Reply.ok("{}"));
        this.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                .withPort(this.server.port())
                .withConnections(2)
                .build();
        this.connector = new HTTPConnector(List.of(this.connections), HTTPConnector.Listener.NONE);
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KalshiLaneVenue venue = new KalshiLaneVenue(
                new NullLogger(),
                new KalshiAuthSigner("key-id", generator.generateKeyPair().getPrivate()),
                "maker");
        final KalshiMarket market = KalshiMarket.of(new Listing(
                1,
                new Exchange(EXCHANGE_ID, "KALSHI", "Kalshi", "global"),
                new Security(
                        (int) SECURITY_ID, "KXT", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "KXT-1:yes",
                "KXT-1"));
        market.exchangeIndex(1);
        venue.addMarket(EXCHANGE_ID, SECURITY_ID, market);
        venue.addSession(SLOT, "smoke");
        final AccountBudget budget = new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", 1_000_000, 1_000, 0.2)),
                        List.of(new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                System.nanoTime());
        this.lane = new AccountLane<>(
                "alloc",
                venue,
                this.connections,
                budget,
                new AccountLane.Timing(
                        0,
                        TIMEOUT_NANOS,
                        TIMEOUT_NANOS,
                        AccountLane.Timing.defaults().sweepIntervalNanos(),
                        AccountLane.Timing.defaults().giveUpNanos(),
                        AccountLane.Timing.defaults().giveUpUnlookableNanos()),
                System::nanoTime,
                SystemEpochClock.INSTANCE,
                16,
                2048,
                512,
                this.commands,
                this.noticeQueue,
                this.releasedOrderQueue);
    }

    @AfterEach
    void tearDown() {
        this.connections.close();
        this.server.close();
    }

    @Test
    void anOrdersTripAllocatesNothingOnTheLanesThread() {
        OrderPathTestKit.assertNoAllocation("lane order trip", 300, 500, this::trip);
        assertEquals(0, this.notices, "every order was accepted, so the reader was told nothing");
        assertEquals(2 * 800, this.server.received(), "each trip sent its submit and its cancel");
    }

    private void trip() {
        command(LaneCommand.NEW);
        awaitAnswers(this.server.received() + 1);
        command(LaneCommand.CANCEL);
        awaitAnswers(this.server.received() + 1);
        final int idx = this.releasedOrderQueue.tryClaim();
        final OrderContext released = this.releasedOrderQueue.indexAt(idx);
        released.reset();
        released.sessionSlot = SLOT;
        released.clientOidCounter = COUNTER;
        this.releasedOrderQueue.commit(idx);
        final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (this.lane.workingOrders() != 0) {
            pass(deadline);
        }
    }

    private void command(final int kind) {
        final int idx = this.commands.tryClaim();
        final LaneCommand command = this.commands.indexAt(idx);
        command.reset();
        command.kind = kind;
        command.sessionSlot = SLOT;
        command.clientOidCounter = COUNTER;
        command.clientOidStrategyId = 1;
        command.exchangeId = EXCHANGE_ID;
        command.securityId = SECURITY_ID;
        command.side = Side.Bid;
        command.price = 500_000_000L;
        command.size = 3_000_000L;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        command.postOnly = true;
        this.commands.commit(idx);
    }

    private void awaitAnswers(final int received) {
        final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            pass(deadline);
        } while (this.server.received() < received || this.connections.inFlight() != 0);
    }

    private void pass(final long deadline) {
        if (System.nanoTime() > deadline) {
            throw new AssertionError("the lane stalled");
        }
        this.connector.doWork();
        this.lane.doWork();
        this.noticeQueue.read(this.countNotice, Integer.MAX_VALUE);
    }
}
