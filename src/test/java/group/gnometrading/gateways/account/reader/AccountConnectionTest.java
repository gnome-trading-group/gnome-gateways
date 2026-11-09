package group.gnometrading.gateways.account.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.RejectReason;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An account's event socket, connection and reader as the gateway runs them, over an in-memory socket: the connect
 * sequence, catch-up before the new socket's frames, the reports and releases each order gets, and reconnects.
 */
class AccountConnectionTest {

    private static final long SLOT = 9;
    private static final long READ_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    record Report(long slot, ExecType execType, long counter, long cumulativeQty) {}

    private final FakeAccountTransport transport = new FakeAccountTransport();
    private final TextAccountProtocol protocol = new TextAccountProtocol();
    private final ManyToOneRingBuffer<OrderContext> noticeQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
    private final ManyToOneRingBuffer<AccountReport> reportQueue =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
    private final ReaderNotices notices = new ReaderNotices(this.noticeQueue);
    private final List<Report> reports = new ArrayList<>();
    private final List<long[]> released = new ArrayList<>();
    private final List<Throwable> readerErrors = new CopyOnWriteArrayList<>();
    private AccountReader reader;
    private AccountConnection<FakeAccountTransport> connection;
    private GnomeAgentRunner readerRunner;

    @BeforeEach
    void setUp() {
        this.reader = new AccountReader(
                this.protocol,
                SystemEpochNanoClock.INSTANCE,
                64,
                this.noticeQueue,
                this.releasedOrderQueue,
                this.reportQueue);
        this.connection = new AccountConnection<>(
                "test",
                new NullLogger(),
                this.transport,
                this.protocol,
                this.reader,
                new GatewayConfig.Builder().build(),
                SystemEpochClock.INSTANCE,
                SystemEpochNanoClock.INSTANCE,
                ConnectLimiter.UNLIMITED,
                READ_SETTLE_NANOS);
        this.readerRunner = new GnomeAgentRunner(this.connection.reader(), this.readerErrors::add);
        GnomeAgentRunner.startOnThread(this.readerRunner);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.connection.pauseControl().stop();
        this.readerRunner.close();
        for (final Throwable error : this.readerErrors) {
            assertTrue(error instanceof SocketClosedException, "the reader failed: " + error);
        }
    }

    @Test
    void aConnectSubscribesBeforeCatchingUpAndOnlyThenLetsTheReaderRun() throws Exception {
        assertEquals(AccountState.CONNECTING, this.connection.state());
        this.connection.supervisor().onStart();
        assertEquals(List.of("authenticate", "open", "subscribe"), this.transport.log);
        assertEquals(1, this.protocol.catchUpSince.size(), "caught up once, after subscribing");
        await(() -> this.connection.state() == AccountState.READY);
    }

    @Test
    void catchUpIsAppliedBeforeAnythingFromTheNewSocket() throws Exception {
        register("a", 1, 3);
        this.protocol.catchUp = List.of("ACK a", "FILL a 1");
        this.transport.push("CANCEL a");
        this.connection.supervisor().onStart();
        awaitReports(3);
        awaitReleases(1);
        assertEquals(
                List.of(
                        new Report(SLOT, ExecType.NEW, 1, 0),
                        new Report(SLOT, ExecType.PARTIAL_FILL, 1, 1),
                        new Report(SLOT, ExecType.CANCEL, 1, 1)),
                this.reports);
        assertEquals(1, this.released.size());
        assertEquals(SLOT, this.released.get(0)[0], "released to the lane by session slot and counter");
        assertEquals(1, this.released.get(0)[1]);
        assertEquals(0, this.reader.heldOrders());
    }

    @Test
    void theLanesReportsFlowWhileCatchUpIsFetchedButTheNewSocketWaitsForIt() throws Exception {
        register("a", 1, 3);
        this.protocol.catchUp = List.of("FILL a 1");
        this.protocol.fetchGate = new CountDownLatch(1);
        this.transport.push("CANCEL a");
        final Thread connecting = new Thread(() -> {
            try {
                this.connection.supervisor().onStart();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        connecting.start();
        await(() -> this.connection.state() == AccountState.CATCHING_UP);

        this.notices.rejectUnheld(SLOT, 9, 1, 0, 0, RejectReason.SESSION_CAP);
        awaitReports(1);
        assertEquals(new Report(SLOT, ExecType.REJECT, 9, 0), this.reports.get(0), "the lane's report, mid catch-up");
        Thread.sleep(50);
        assertEquals(1, this.reports.size(), "nothing read from the new socket yet");

        this.protocol.fetchGate.countDown();
        connecting.join();
        awaitReports(3);
        assertEquals(ExecType.PARTIAL_FILL, this.reports.get(1).execType(), "catch-up first");
        assertEquals(ExecType.CANCEL, this.reports.get(2).execType(), "then the new socket");
        await(() -> this.connection.state() == AccountState.READY);
    }

    @Test
    void anAccountIsReadyOnlyOnceItsReaderHasAppliedTheCatchUp() throws Exception {
        this.connection.supervisor().onStart();
        await(() -> this.connection.state() == AccountState.READY);
        // As between a connect's fetch and the reader's next pass: fetched, not yet applied.
        this.reader.holdFramesForCatchUp();
        assertEquals(AccountState.CATCHING_UP, this.connection.state());
        this.reader.catchUpFetched();
        await(() -> this.connection.state() == AccountState.READY);
    }

    @Test
    void theFirstCatchUpReachesBackToTheOldestRecoveredOrder() throws Exception {
        final long hourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        final OrderContext recovered = order("r", 9, 3, SLOT);
        recovered.submittedAtMillis = hourAgo;
        this.reader.recover(recovered);
        this.connection.supervisor().onStart();
        assertEquals(List.of(hourAgo), this.protocol.catchUpSince.subList(0, 1));
    }

    @Test
    void catchUpStartsWhenTheSocketWasLastHeardNotAtTheOldestOrderHeld() throws Exception {
        this.connection.supervisor().onStart();
        final OrderContext old = order("old", 1, 3, SLOT);
        old.submittedAtMillis = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        this.notices.register(old);
        this.transport.push("ACK old");
        awaitReports(1);
        this.transport.broken = true;
        await(() -> this.connection.state() == AccountState.DOWN && !this.readerErrors.isEmpty());
        this.connection.supervisor().doWork();
        this.connection.supervisor().doWork();
        assertEquals(2, this.protocol.catchUpSince.size());
        assertTrue(
                this.protocol.catchUpSince.get(1) > System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1),
                "an order resting an hour must not make catch-up page back an hour");
    }

    @Test
    void eachOrderIsReportedToItsOwnSession() throws Exception {
        this.connection.supervisor().onStart();
        register("a", 1, 2, 4);
        register("b", 2, 2, 7);
        this.transport.push("FILL b 2");
        this.transport.push("FILL a 1");
        awaitReports(2);
        awaitReleases(1);
        assertEquals(new Report(7, ExecType.FILL, 2, 2), this.reports.get(0));
        assertEquals(new Report(4, ExecType.PARTIAL_FILL, 1, 1), this.reports.get(1));
        assertEquals(7, this.released.get(0)[0]);
    }

    @Test
    void aRecoveredOrderIsCaughtUpFromTheFillsAlreadyReportedAndReportedToItsSession() throws Exception {
        final OrderContext recovered = order("r", 9, 3, 5);
        recovered.acked = true;
        recovered.cumulativeFilledQty = 1;
        recovered.leavesQty = 2;
        this.reader.recover(recovered);
        this.protocol.catchUp = List.of("FILL r 2");

        this.connection.supervisor().onStart();

        awaitReports(1);
        assertEquals(new Report(5, ExecType.FILL, 9, 3), this.reports.get(0));
        awaitReleases(1);
        assertEquals(5, this.released.get(0)[0]);
    }

    @Test
    void aRecoveredOrdersVenueIdIsHandedToTheVenueThatMatchesEventsByIt() {
        final OrderContext known = order("k", 3, 1, SLOT);
        final byte[] venueId = "v-77".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(venueId, 0, known.exchangeOrderIdBytes, 0, venueId.length);
        known.exchangeOrderIdLength = venueId.length;
        this.reader.recover(order("u", 2, 1, SLOT));
        this.reader.recover(known);
        assertEquals(List.of("v-77"), this.protocol.venueIds);
        assertEquals(2, this.reader.heldOrders());
    }

    @Test
    void theLanesNoticesAreReportedWhetherOrNotTheReaderHoldsTheOrder() throws Exception {
        this.connection.supervisor().onStart();
        // Refused before it was handed over.
        final OrderContext unheld = order("x", 5, 1, SLOT);
        this.notices.reject(unheld, RejectReason.SESSION_CAP);
        // Handed over, then cancelled before it was sent.
        register("y", 6, 1);
        this.notices.canceledUnsent(order("y", 6, 1, SLOT));
        awaitReports(2);
        assertEquals(new Report(SLOT, ExecType.REJECT, 5, 0), this.reports.get(0));
        assertEquals(new Report(SLOT, ExecType.CANCEL, 6, 0), this.reports.get(1));
        assertEquals(0, this.reader.heldOrders(), "the cancelled order is dropped");
        awaitReleases(1);
        assertEquals(
                6, this.released.get(0)[1], "the held one is released; the lane has freed it already and lets it go");
    }

    @Test
    void aBrokenSocketIsReconnectedAndCaughtUpFromWhenItWasLastHeard() throws Exception {
        this.connection.supervisor().onStart();
        register("a", 1, 3);
        this.transport.push("ACK a");
        awaitReports(1);
        this.transport.broken = true;
        await(() -> this.connection.state() == AccountState.DOWN && !this.readerErrors.isEmpty());
        assertTrue(this.readerErrors.get(0) instanceof SocketClosedException);

        this.protocol.catchUp = List.of("FILL a 3");
        // One pass sees the reader stopped, the next reconnects.
        this.connection.supervisor().doWork();
        this.connection.supervisor().doWork();
        await(() -> this.connection.state() == AccountState.READY);
        assertEquals(2, this.protocol.catchUpSince.size());
        assertTrue(
                this.protocol.catchUpSince.get(1) >= this.protocol.catchUpSince.get(0),
                "the second catch-up starts from the order held or when the socket was last heard");
        awaitReports(2);
        awaitReleases(1);
        assertEquals(ExecType.FILL, this.reports.get(this.reports.size() - 1).execType());
        assertEquals(0, this.reader.heldOrders());
    }

    @Test
    void aSecondCatchUpOnceTheVenuesReadsHaveCaughtUpSeesWhatTheFirstMissed() throws Exception {
        register("a", 1, 3);
        // At the connect, the venue's listing still shows the order resting: its cancel came moments before.
        this.protocol.catchUp = List.of("ACK a");
        this.connection.supervisor().onStart();
        awaitReports(1);
        this.protocol.catchUp = List.of("CANCEL a");
        this.connection.supervisor().doWork();
        assertEquals(1, this.reports.size(), "not before the venue's reads have had time to catch up");

        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(READ_SETTLE_NANOS) + 20);
        this.connection.supervisor().doWork();
        awaitReports(2);
        assertEquals(ExecType.CANCEL, this.reports.get(1).execType());
        assertEquals(2, this.protocol.catchUpSince.size());
        assertEquals(this.protocol.catchUpSince.get(0), this.protocol.catchUpSince.get(1), "over the same window");
        await(() -> this.connection.state() == AccountState.READY);
    }

    @Test
    void aFollowUpIsFetchedOnTheSupervisorAndAppliedOnTheReader() throws Exception {
        register("a", 1, 3);
        this.protocol.followUp = List.of("CANCEL a");
        this.connection.supervisor().onStart();
        await(() -> this.protocol.wantsFollowUp());
        assertEquals(1, this.reader.heldOrders(), "nothing has ended it yet");
        this.connection.supervisor().doWork();
        awaitReports(1);
        await(() -> this.reader.heldOrders() == 0);
        assertEquals(ExecType.CANCEL, this.reports.get(0).execType());
        assertEquals(0, this.reader.heldOrders());
    }

    @Test
    void noKeepAliveGoesOnASocketKnownToBeDown() throws Exception {
        this.connection.supervisor().onStart();
        this.transport.broken = true;
        await(() -> this.connection.state() == AccountState.DOWN);
        this.connection.supervisor().forceKeepAlive();
        // Its first pass after the break asks for a reconnect rather than keeping the broken socket alive.
        this.connection.supervisor().doWork();
        assertEquals(0, this.protocol.keepAlives);
        this.connection.supervisor().doWork();
        await(() -> this.connection.state() == AccountState.READY);
    }

    private void register(final String id, final long counter, final long size) {
        register(id, counter, size, SLOT);
    }

    private void register(final String id, final long counter, final long size, final long slot) {
        this.notices.register(order(id, counter, size, slot));
    }

    private static OrderContext order(final String id, final long counter, final long size, final long slot) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = slot;
        ctx.clientOidCounter = counter;
        ctx.clientOidStrategyId = 1;
        ctx.originalQty = size;
        ctx.leavesQty = size;
        ctx.submittedAtMillis = System.currentTimeMillis();
        final byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, ctx.correlationIdBytes, 0, bytes.length);
        ctx.correlationIdLength = bytes.length;
        return ctx;
    }

    private void awaitReports(final int count) {
        await(() -> {
            drain();
            return this.reports.size() >= count;
        });
    }

    private void awaitReleases(final int count) {
        await(() -> {
            drain();
            return this.released.size() >= count;
        });
    }

    private static void await(final BooleanSupplier done) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.onSpinWait();
        }
    }

    private void drain() {
        this.reportQueue.read(
                report -> this.reports.add(new Report(
                        report.sessionSlot,
                        report.report.decoder.execType(),
                        report.report.getClientOidCounter(),
                        report.report.decoder.cumulativeQty())),
                Integer.MAX_VALUE);
        this.releasedOrderQueue.read(
                ctx -> this.released.add(new long[] {ctx.sessionSlot, ctx.clientOidCounter}), Integer.MAX_VALUE);
    }
}
