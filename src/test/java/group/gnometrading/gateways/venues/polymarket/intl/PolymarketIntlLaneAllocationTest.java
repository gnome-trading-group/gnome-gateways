package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A Polymarket International order's trip through its account's lane, with the NEW told from the submit's answer and
 * each cancel sent twice, must not allocate on the lane's thread once warm: cancelled once it rests, cancelled while
 * its submit is unanswered, overtaken by its cancel and refused as already cancelled, or overtaken and cancelled again
 * once it rests. So too the reader's side of those notices.
 */
@Tag("allocation")
class PolymarketIntlLaneAllocationTest {

    // Hardhat account #0: a well-known test key, never funded on Polygon.
    private static final byte[] PRIVATE_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String TOKEN = "71321045679252212594626385532706912750332728571942532289631379312455583992563";
    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==";
    private static final long SLOT = 5;
    private static final long COUNTER = 7;
    private static final int EXCHANGE_ID = 2;
    private static final long SECURITY_ID = 3;
    private static final long SIZE = 10 * Statics.SIZE_SCALING_FACTOR;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final String LIVE = "{\"success\":true,\"errorMsg\":\"\",\"status\":\"live\"}";
    private static final Pattern ORDER_ID = Pattern.compile("\"orderID\":\"(0x[0-9a-f]+)\"");
    private static final int TRIPS = 800;
    private static final int AFTER_ANSWER = 0;
    private static final int BEFORE_ANSWER = 1;
    private static final int REFUSED_AS_CANCELED = 2;
    private static final int OVERTAKEN_THEN_RESTING = 3;
    private static final int MODES = 4;
    private static final String ALREADY_CANCELED = "{\"error\":\"order 0xab is invalid. Already canceled.\"}";
    private static final String NOT_FOUND =
            "{\"canceled\":[],\"not_canceled\":{\"0xab\":\"order can't be found - already canceled or matched\"}}";

    private FakeVenueServer server;
    private AsyncHTTPConnections connections;
    private HTTPConnector connector;
    private AccountLane<PolymarketIntlMarket> lane;
    private final ManyToOneRingBuffer<LaneCommand> commands =
            new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private volatile int mode;
    private final AtomicInteger tripDeletes = new AtomicInteger();
    private final int[] tripsByMode = new int[MODES];
    private int trips;
    private int resting;
    private int canceledBeforePlaced;
    private int otherNotices;
    private final MessageConsumer<OrderContext> countNotice = ctx -> {
        if (ctx.restingNotice) {
            this.resting++;
        } else if (ctx.canceledBeforePlaced) {
            this.canceledBeforePlaced++;
        } else if (!ctx.registration) {
            this.otherNotices++;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        this.server = new FakeVenueServer();
        this.server.script(this::answer);
        this.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                .withPort(this.server.port())
                .withConnections(4)
                .build();
        this.connector = new HTTPConnector(List.of(this.connections), HTTPConnector.Listener.NONE);
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                new NullLogger(),
                new PolymarketIntlOrderSigner(PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, 1L),
                new PolymarketIntlAuthHeaders("api-key", SECRET, "pass", EOA));
        venue.addMarket(
                EXCHANGE_ID,
                SECURITY_ID,
                PolymarketIntlMarket.of(
                        new Listing(
                                1,
                                new Exchange(EXCHANGE_ID, "POLYMARKET", "Polymarket", "global"),
                                new Security(
                                        (int) SECURITY_ID,
                                        "TEST",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        0L,
                                        0L,
                                        true,
                                        0),
                                "0xcondition:" + TOKEN,
                                "TEST-YES"),
                        new PolymarketIntlMarketInfo(Statics.PRICE_SCALING_FACTOR / 100, SIZE / 2, false, false, 0)));
        this.lane = new AccountLane<>(
                "alloc",
                venue,
                this.connections,
                // Polymarket's own buckets, made roomy enough that every trip's second copy goes.
                new AccountBudget(
                        VenueRateLimits.of(
                                "Test",
                                List.of(
                                        new VenueRateLimits.Bucket("order", 1_000_000, 1_000, 0),
                                        new VenueRateLimits.Bucket("cancel", 1_000_000, 1_000, 0)),
                                List.of(
                                        new VenueRateLimits.Rule(
                                                HTTPMethod.POST, "/order", null, "order", 1, RequestClass.NEW),
                                        new VenueRateLimits.Rule(
                                                HTTPMethod.DELETE, "", null, "cancel", 1, RequestClass.CANCEL),
                                        new VenueRateLimits.Rule(null, "", null, "order", 1, RequestClass.NEW)),
                                List.of()),
                        System.nanoTime()),
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
                4096,
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
    void everyWayAnOrdersCancelGoesAllocatesNothing() {
        OrderPathTestKit.assertNoAllocation("lane order trip, cancel doubled", 300, 500, this::trip);
        assertEquals(TRIPS, this.trips);
        assertEquals(
                this.tripsByMode[AFTER_ANSWER]
                        + this.tripsByMode[BEFORE_ANSWER]
                        + this.tripsByMode[OVERTAKEN_THEN_RESTING],
                this.resting,
                "each live answer told the reader the order rests");
        assertEquals(this.tripsByMode[REFUSED_AS_CANCELED], this.canceledBeforePlaced, "each refusal ended its order");
        assertEquals(0, this.otherNotices, "a copy that found nothing refused nothing");
        assertEquals(
                3 * (TRIPS - this.tripsByMode[OVERTAKEN_THEN_RESTING]) + 5 * this.tripsByMode[OVERTAKEN_THEN_RESTING],
                this.server.received(),
                "each trip sent its submit and two cancels, and an overtaken one two more once its order rested");
    }

    @Test
    void theReadersSideOfTheseNoticesAllocatesNothing() {
        // More orders than the record of refused ones keeps, so it evicts as it goes.
        final int orders = 600;
        final OrderContext[] registrations = new OrderContext[orders];
        final OrderContext[] restings = new OrderContext[orders];
        final OrderContext[] refusals = new OrderContext[orders];
        final long[] keys = new long[orders];
        for (int i = 0; i < orders; i++) {
            final String id = String.format("0x%064x", i + 1);
            registrations[i] = RecordedReports.order(SLOT, i + 1, EXCHANGE_ID, SECURITY_ID, id, SIZE);
            registrations[i].registration = true;
            restings[i] = RecordedReports.order(SLOT, i + 1, EXCHANGE_ID, SECURITY_ID, id, 0);
            restings[i].restingNotice = true;
            refusals[i] = RecordedReports.order(SLOT, i + 1, EXCHANGE_ID, SECURITY_ID, id, SIZE);
            refusals[i].execType = ExecType.CANCEL;
            refusals[i].orderStatus = OrderStatus.CANCELED;
            refusals[i].endsRegistered = true;
            refusals[i].canceledBeforePlaced = true;
            keys[i] = OrderKeys.of(refusals[i].correlationIdBytes, refusals[i].correlationIdLength);
        }
        final int[] published = {0};
        final OrderReports reports = new OrderReports(64, System::nanoTime, new OrderReports.Output() {
            @Override
            public void publish(final OrderContext ctx, final OrderExecutionReport report) {
                published[0]++;
            }

            @Override
            public void released(final OrderContext ctx) {}
        });
        final int[] next = {0};
        // An order resting by its answer, then cancelled; then one refused as already cancelled, and a later event
        // asking after a refused one.
        OrderPathTestKit.assertNoAllocation("reader notices", 2_000, 5_000, () -> {
            final int i = next[0];
            next[0] = (i + 2) % orders;
            reports.onNotice(registrations[i]);
            reports.onNotice(restings[i]);
            reports.onNotice(refusals[i]);
            reports.onNotice(registrations[i + 1]);
            reports.onNotice(refusals[i + 1]);
            reports.endedBeforePlaced(keys[i + 1]);
        });
        assertTrue(published[0] > 0);
    }

    /** The venue's answers for this trip's mode; a submit answered late enough that its cancel goes first. */
    private Reply answer(final FakeVenueServer.Request request) {
        final int now = this.mode;
        if (!request.method().equals("DELETE")) {
            if (now == AFTER_ANSWER) {
                return Reply.ok(LIVE);
            }
            return now == REFUSED_AS_CANCELED
                    ? new Reply(400, ALREADY_CANCELED, 5)
                    : Reply.ok(LIVE).after(5);
        }
        final int delete = this.tripDeletes.getAndIncrement();
        if (now == REFUSED_AS_CANCELED || (now == OVERTAKEN_THEN_RESTING && delete < 2)) {
            return Reply.ok(NOT_FOUND);
        }
        // One copy cancels the order; the other finds it gone.
        return delete % 2 == 0 ? Reply.ok(canceled(request)) : Reply.ok(NOT_FOUND);
    }

    private void trip() {
        final int now = this.trips++ % MODES;
        this.mode = now;
        this.tripsByMode[now]++;
        this.tripDeletes.set(0);
        final int start = this.server.received();
        command(LaneCommand.NEW);
        if (now == AFTER_ANSWER) {
            awaitAnswers(start + 1);
        } else {
            awaitReceived(start + 1);
        }
        command(LaneCommand.CANCEL);
        awaitAnswers(start + (now == OVERTAKEN_THEN_RESTING ? 5 : 3));
        if (now != REFUSED_AS_CANCELED) {
            // The reader releases an order its events ended; one refused the lane frees itself.
            final int idx = this.releasedOrderQueue.tryClaim();
            final OrderContext released = this.releasedOrderQueue.indexAt(idx);
            released.reset();
            released.sessionSlot = SLOT;
            released.clientOidCounter = COUNTER;
            this.releasedOrderQueue.commit(idx);
        }
        final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (this.lane.workingOrders() != 0) {
            pass(deadline);
        }
    }

    private void awaitReceived(final int received) {
        final long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            pass(deadline);
        } while (this.server.received() < received);
    }

    private static String canceled(final FakeVenueServer.Request request) {
        final Matcher id = ORDER_ID.matcher(request.body());
        return "{\"canceled\":[\"" + (id.find() ? id.group(1) : "") + "\"],\"not_canceled\":{}}";
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
        command.size = SIZE;
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
