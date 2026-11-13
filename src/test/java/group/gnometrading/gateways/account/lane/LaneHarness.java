package group.gnometrading.gateways.account.lane;

import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.agrona.concurrent.SystemEpochClock;

/**
 * A lane over real connections to a {@link FakeVenueServer}, driven from the test's thread: commands in, the reader's
 * queues drained into lists, the server's requests to check.
 */
public final class LaneHarness<M> implements AutoCloseable {

    public static final int CONNECTIONS = 8;

    public final FakeVenueServer server;
    public final AsyncHTTPConnections connections;
    public final AccountLane<M> lane;
    public final List<OrderContext> handed = new ArrayList<>();
    public final List<OrderContext> notices = new ArrayList<>();
    private final HTTPConnector connector;
    private final ManyToOneRingBuffer<LaneCommand> commands =
            new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 256);
    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 256);
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 256);
    // The connection pool's own default.
    private static final long DEFAULT_MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final long sessionSlot;
    // Set to stop reading the lane's notices, as a reader paused for catch-up or stalled would.
    public boolean readerStalled;
    private final int exchangeId;
    private final long securityId;

    public LaneHarness(
            final LaneVenue<M> venue,
            final long readSettleNanos,
            final long responseTimeoutNanos,
            final long sessionSlot,
            final int exchangeId,
            final long securityId)
            throws IOException {
        this(
                venue,
                readSettleNanos,
                responseTimeoutNanos,
                TimeUnit.SECONDS.toNanos(5),
                sessionSlot,
                exchangeId,
                securityId);
    }

    public LaneHarness(
            final LaneVenue<M> venue,
            final long readSettleNanos,
            final long responseTimeoutNanos,
            final long maxQueueDelayNanos,
            final long sessionSlot,
            final int exchangeId,
            final long securityId)
            throws IOException {
        this(
                venue,
                new AccountLane.Timing(
                        readSettleNanos,
                        responseTimeoutNanos,
                        maxQueueDelayNanos,
                        AccountLane.Timing.defaults().sweepIntervalNanos(),
                        AccountLane.Timing.defaults().giveUpNanos(),
                        AccountLane.Timing.defaults().giveUpUnlookableNanos()),
                sessionSlot,
                exchangeId,
                securityId);
    }

    public LaneHarness(
            final LaneVenue<M> venue,
            final AccountLane.Timing timing,
            final long sessionSlot,
            final int exchangeId,
            final long securityId)
            throws IOException {
        this(venue, timing, sessionSlot, exchangeId, securityId, DEFAULT_MAX_IDLE_NANOS);
    }

    /** @param maxIdleNanos how long a connection may sit unused before it counts as stale */
    public LaneHarness(
            final LaneVenue<M> venue,
            final AccountLane.Timing timing,
            final long sessionSlot,
            final int exchangeId,
            final long securityId,
            final long maxIdleNanos)
            throws IOException {
        this(
                venue,
                timing,
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", 100_000, 100, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                sessionSlot,
                exchangeId,
                securityId,
                maxIdleNanos);
    }

    /** @param limits the account's budget, which starts empty */
    public LaneHarness(
            final LaneVenue<M> venue,
            final AccountLane.Timing timing,
            final VenueRateLimits limits,
            final long sessionSlot,
            final int exchangeId,
            final long securityId)
            throws IOException {
        this(venue, timing, limits, sessionSlot, exchangeId, securityId, DEFAULT_MAX_IDLE_NANOS);
    }

    /**
     * @param limits the account's budget, which starts empty
     * @param maxIdleNanos how long a connection may sit unused before it counts as stale
     */
    public LaneHarness(
            final LaneVenue<M> venue,
            final AccountLane.Timing timing,
            final VenueRateLimits limits,
            final long sessionSlot,
            final int exchangeId,
            final long securityId,
            final long maxIdleNanos)
            throws IOException {
        this.sessionSlot = sessionSlot;
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.server = new FakeVenueServer();
        this.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                .withPort(this.server.port())
                .withConnections(CONNECTIONS)
                .withMaxIdle(maxIdleNanos)
                .build();
        this.connector = new HTTPConnector(List.of(this.connections), HTTPConnector.Listener.NONE);
        final AccountBudget budget = new AccountBudget(limits, System.nanoTime());
        this.lane = new AccountLane<>(
                "test",
                venue,
                this.connections,
                budget,
                timing,
                System::nanoTime,
                SystemEpochClock.INSTANCE,
                64,
                2048,
                512,
                this.commands,
                this.noticeQueue,
                this.releasedOrderQueue);
    }

    /** A post-only GTC bid for 1 at 0.40, adjusted by {@code terms}. */
    public void submit(final long counter, final Consumer<LaneCommand> terms) {
        send(LaneCommand.NEW, counter, terms);
    }

    public void submit(final long counter) {
        submit(counter, command -> {});
    }

    public void cancel(final long counter) {
        send(LaneCommand.CANCEL, counter, command -> {});
    }

    public void amend(final long counter, final long price, final long size) {
        send(LaneCommand.AMEND, counter, command -> {
            command.price = price;
            command.size = size;
        });
    }

    /** A new order from the session in {@code slot}. */
    public void submitAs(final long slot, final long counter) {
        send(LaneCommand.NEW, counter, command -> command.sessionSlot = slot);
    }

    /** A NEW the gateway put on the lane's ring at {@code enqueuedNanos} (the lane's clock). */
    public void submitStamped(final long slot, final long counter, final long enqueuedNanos) {
        send(LaneCommand.NEW, counter, command -> {
            command.sessionSlot = slot;
            command.enqueuedNanos = enqueuedNanos;
        });
    }

    public void cancelAs(final long slot, final long counter) {
        send(LaneCommand.CANCEL, counter, command -> command.sessionSlot = slot);
    }

    public void amendAs(final long slot, final long counter, final long price) {
        send(LaneCommand.AMEND, counter, command -> {
            command.sessionSlot = slot;
            command.price = price;
        });
    }

    /** The session in {@code slot} joins with this weight and wait; 0 keeps the lane's default. */
    public void session(final long slot, final int weight, final long maxQueueNanos) {
        send(LaneCommand.SESSION, 0, command -> {
            command.sessionSlot = slot;
            command.weight = weight;
            command.maxQueueNanos = maxQueueNanos;
        });
    }

    public void sessionEnded(final long slot) {
        send(LaneCommand.SESSION_ENDED, 0, command -> command.sessionSlot = slot);
    }

    /** The reader learned the venue's id for the order from an event. */
    public void venueIdLearned(final long counter, final String venueId) {
        final int idx = this.releasedOrderQueue.tryClaim();
        final OrderContext notice = this.releasedOrderQueue.indexAt(idx);
        notice.reset();
        notice.sessionSlot = this.sessionSlot;
        notice.clientOidCounter = counter;
        notice.venueOrderIdNotice = true;
        final byte[] id = venueId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, notice.exchangeOrderIdBytes, 0, id.length);
        notice.exchangeOrderIdLength = id.length;
        this.releasedOrderQueue.commit(idx);
    }

    /** A command as is: any kind, any terms. */
    public void command(final int kind, final long counter, final Consumer<LaneCommand> terms) {
        send(kind, counter, terms);
    }

    /** The reader ends the order. */
    public void release(final long counter) {
        final int idx = this.releasedOrderQueue.tryClaim();
        final OrderContext released = this.releasedOrderQueue.indexAt(idx);
        released.reset();
        released.sessionSlot = this.sessionSlot;
        released.clientOidCounter = counter;
        this.releasedOrderQueue.commit(idx);
    }

    private void send(final int kind, final long counter, final Consumer<LaneCommand> terms) {
        final int idx = this.commands.tryClaim();
        final LaneCommand command = this.commands.indexAt(idx);
        command.reset();
        command.kind = kind;
        command.sessionSlot = this.sessionSlot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = 1;
        command.exchangeId = this.exchangeId;
        command.securityId = this.securityId;
        command.side = Side.Bid;
        command.price = 400_000_000L;
        command.size = 1_000_000L;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        command.postOnly = true;
        terms.accept(command);
        this.commands.commit(idx);
    }

    public List<Request> requests(final String method) {
        return this.server.requests().stream()
                .filter(request -> request.method().equals(method))
                .toList();
    }

    /** Whether {@code count} requests of {@code method} have been answered. */
    public boolean answered(final String method, final int count) {
        return requests(method).size() >= count && this.connections.inFlight() == 0;
    }

    public void runUntil(final BooleanSupplier done) {
        runUntil(done, 2_000);
    }

    public void runUntil(final BooleanSupplier done, final long timeoutMillis) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            pass();
            if (done.getAsBoolean()) {
                return;
            }
        }
        fail("timed out; requests " + this.server.requests() + ", notices " + this.notices.size());
    }

    public void runFor(final long millis) {
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            pass();
        }
    }

    private void pass() {
        this.connector.doWork();
        this.lane.doWork();
        if (!this.readerStalled) {
            this.noticeQueue.read(
                    ctx -> (ctx.registration ? this.handed : this.notices).add(copy(ctx)), Integer.MAX_VALUE);
        }
    }

    private static OrderContext copy(final OrderContext ctx) {
        final OrderContext copy = new OrderContext();
        copy.copyFrom(ctx);
        return copy;
    }

    @Override
    public void close() {
        this.connections.close();
        this.server.close();
    }
}
