package group.gnometrading.gateways.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.TestLaneVenue;
import group.gnometrading.gateways.account.reader.AccountConnection;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.FakeAccountTransport;
import group.gnometrading.gateways.account.reader.TextAccountProtocol;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whole account path under load: the lane sending to a local venue over real connections, the reader on its own
 * thread reading that venue's events, while orders fill, are cancelled and the event socket breaks. Events sent while
 * it is down reach the reader only through catch-up. Every order must end once, with exactly the venue's fills, and be
 * released by both the reader and the lane.
 */
class AccountStressTest {

    private static final int ORDERS = 400;
    private static final int SESSIONS = 4;
    private static final long SIZE = 4;

    /** The venue's side: what each order has done, and the events it sends, or loses while the socket is down. */
    private static final class Venue {
        final Map<String, long[]> orders = new HashMap<>();
        final List<String> lost = new ArrayList<>();
        final FakeAccountTransport transport;
        boolean down;

        Venue(final FakeAccountTransport transport) {
            this.transport = transport;
        }

        synchronized void accept(final String id) {
            this.orders.put(id, new long[] {0, 0});
            send("ACK " + id);
        }

        synchronized boolean cancel(final String id) {
            final long[] order = this.orders.get(id);
            if (order == null || order[1] == 1) {
                return false;
            }
            order[1] = 1;
            send("CANCEL " + id);
            return true;
        }

        synchronized void fill(final String id, final long qty) {
            final long[] order = this.orders.get(id);
            if (order == null || order[1] == 1) {
                return;
            }
            final long filled = Math.min(qty, SIZE - order[0]);
            order[0] += filled;
            if (order[0] == SIZE) {
                order[1] = 1;
            }
            send("FILL " + id + " " + filled);
        }

        synchronized void breakSocket() {
            this.down = true;
            String frame;
            while ((frame = this.transport.frames.poll()) != null) {
                this.lost.add(frame);
            }
            this.transport.broken = true;
        }

        /** What catch-up will find, handed over as the socket comes back. */
        synchronized List<String> reconnect() {
            final List<String> caughtUp = new ArrayList<>(this.lost);
            this.lost.clear();
            this.down = false;
            return caughtUp;
        }

        private void send(final String event) {
            if (this.down) {
                this.lost.add(event);
            } else {
                this.transport.push(event);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {20261104L, 1L, 2L, 3L, 4L})
    void everyOrderEndsOnceWithTheVenuesFillsThroughBreaksAndCatchUp(final long seed) throws Exception {
        final FakeAccountTransport transport = new FakeAccountTransport();
        final Venue venue = new Venue(transport);
        final TestLaneVenue laneVenue = new TestLaneVenue();
        final TextAccountProtocol protocol = new TextAccountProtocol();
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 1024);
        final ManyToOneRingBuffer<OrderContext> noticeQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 1024);
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 1024);
        final ManyToOneRingBuffer<AccountReport> reportQueue =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 4096);

        try (FakeVenueServer server = new FakeVenueServer();
                AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                        .withPort(server.port())
                        .withConnections(8)
                        .build()) {
            server.script(request -> {
                if (request.method().equals("POST")) {
                    venue.accept(request.body());
                    return Reply.ok("v-" + request.body());
                }
                final String id = request.path().substring("/orders/v-".length());
                return venue.cancel(id) ? Reply.ok("") : Reply.status(400);
            });
            final HTTPConnector connector = new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE);
            final AccountLane<String> lane = new AccountLane<>(
                    "stress",
                    laneVenue,
                    connections,
                    new AccountBudget(
                            VenueRateLimits.of(
                                    "Test",
                                    List.of(new VenueRateLimits.Bucket("all", 1_000_000, 10_000, 0.2)),
                                    List.of(new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                                    List.of()),
                            System.nanoTime()),
                    new AccountLane.Timing(
                            0,
                            TimeUnit.SECONDS.toNanos(5),
                            TimeUnit.SECONDS.toNanos(5),
                            TimeUnit.SECONDS.toNanos(30),
                            TimeUnit.MINUTES.toNanos(10),
                            TimeUnit.MINUTES.toNanos(2)),
                    System::nanoTime,
                    SystemEpochClock.INSTANCE,
                    ORDERS,
                    256,
                    128,
                    commands,
                    noticeQueue,
                    releasedOrderQueue);
            final AccountReader reader = new AccountReader(
                    protocol, SystemEpochNanoClock.INSTANCE, ORDERS, noticeQueue, releasedOrderQueue, reportQueue);
            final AccountConnection<FakeAccountTransport> connection = new AccountConnection<>(
                    "stress",
                    new NullLogger(),
                    transport,
                    protocol,
                    reader,
                    new GatewayConfig.Builder().build(),
                    SystemEpochClock.INSTANCE,
                    SystemEpochNanoClock.INSTANCE,
                    ConnectLimiter.UNLIMITED,
                    TimeUnit.MILLISECONDS.toNanos(50));
            final List<Throwable> readerErrors = new CopyOnWriteArrayList<>();
            final GnomeAgentRunner readerRunner = new GnomeAgentRunner(connection.reader(), readerErrors::add);
            GnomeAgentRunner.startOnThread(readerRunner);
            final Map<Long, List<long[]>> reports = new HashMap<>();
            try {
                connection.supervisor().onStart();
                final Random random = new Random(seed);
                int submitted = 0;
                int breaks = 0;
                int caughtUp = 0;
                int breaksDue = 0;
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (System.nanoTime() < deadline) {
                    if (submitted < ORDERS && random.nextInt(4) == 0) {
                        submitted++;
                        command(commands, LaneCommand.NEW, slotOf(submitted), submitted);
                        // At fixed points as well as at random, so every seed breaks the socket a few times.
                        if (submitted % 100 == 50) {
                            breaksDue++;
                        }
                    }
                    if (breaksDue > 0 && !venue.down) {
                        breaksDue--;
                        venue.breakSocket();
                        breaks++;
                    }
                    if (submitted > 0 && random.nextInt(3) == 0) {
                        final int counter = 1 + random.nextInt(submitted);
                        venue.fill(id(slotOf(counter), counter), 1 + random.nextInt(2));
                    }
                    if (submitted > 0 && random.nextInt(8) == 0) {
                        final int counter = 1 + random.nextInt(submitted);
                        command(commands, LaneCommand.CANCEL, slotOf(counter), counter);
                    }
                    if (random.nextInt(120) == 0 && !venue.down) {
                        venue.breakSocket();
                        breaks++;
                    }
                    if (venue.down && connection.state() == AccountState.DOWN && random.nextInt(20) == 0) {
                        protocol.catchUp = venue.reconnect();
                        caughtUp += protocol.catchUp.size();
                        connection.supervisor().doWork();
                        connection.supervisor().doWork();
                    }
                    connector.doWork();
                    lane.doWork();
                    drain(reportQueue, reports);
                    if (submitted == ORDERS && !venue.down) {
                        // Wind down: fill whatever is left, so every order ends.
                        for (int counter = 1; counter <= ORDERS; counter++) {
                            venue.fill(id(slotOf(counter), counter), SIZE);
                        }
                        if (lane.workingOrders() == 0 && reader.heldOrders() == 0) {
                            break;
                        }
                    }
                }
                assertTrue(breaks >= 3, "the socket broke " + breaks + " times");
                assertTrue(protocol.catchUpSince.size() > breaks, "each break was reconnected");
                assertTrue(caughtUp > 0, "some events reached the reader only through catch-up");
                System.out.printf(
                        "stress: %d orders, %d breaks, %d events through catch-up%n", ORDERS, breaks, caughtUp);
                assertEquals(0, lane.workingOrders(), "the lane released every order");
                assertEquals(0, reader.heldOrders(), "the reader ended every order");
            } finally {
                connection.pauseControl().stop();
                readerRunner.close();
            }
            for (final Throwable error : readerErrors) {
                assertTrue(error instanceof SocketClosedException, "the reader failed: " + error);
            }
            int cancelled = 0;
            long fills = 0;
            for (int counter = 1; counter <= ORDERS; counter++) {
                final List<long[]> orderReports = reports.get((long) counter);
                if (orderReports == null) {
                    fail("order " + counter + " got no reports");
                }
                // An order cancelled before it was sent never reached the venue.
                final long[] venueState = venue.orders.getOrDefault(id(slotOf(counter), counter), new long[2]);
                long filled = 0;
                int terminal = 0;
                for (final long[] report : orderReports) {
                    assertEquals(slotOf(counter), report[0], "reported to its own session");
                    final ExecType type = ExecType.get((short) report[1]);
                    if (type == ExecType.PARTIAL_FILL || type == ExecType.FILL) {
                        filled += report[2];
                    }
                    if (type == ExecType.FILL || type == ExecType.CANCEL || type == ExecType.REJECT) {
                        terminal++;
                    }
                }
                assertEquals(1, terminal, "order " + counter + " ended once");
                if (type(orderReports, ExecType.CANCEL)) {
                    cancelled++;
                }
                fills += filled;
                assertEquals(venueState[0], filled, "order " + counter + " has exactly the venue's fills");
            }
            System.out.printf("stress: %d cancelled, %d units filled%n", cancelled, fills);
            assertTrue(cancelled > 0 && cancelled < ORDERS, "some orders were cancelled, some filled");
        }
    }

    private static boolean type(final List<long[]> reports, final ExecType type) {
        for (final long[] report : reports) {
            if (report[1] == type.value()) {
                return true;
            }
        }
        return false;
    }

    private static long slotOf(final long counter) {
        return 1 + counter % SESSIONS;
    }

    private static String id(final long slot, final long counter) {
        return "s" + slot + "-" + counter;
    }

    private static void command(
            final ManyToOneRingBuffer<LaneCommand> commands, final int kind, final long slot, final long counter) {
        final int idx = commands.tryClaim();
        final LaneCommand command = commands.indexAt(idx);
        command.reset();
        command.kind = kind;
        command.sessionSlot = slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = 1;
        command.exchangeId = TestLaneVenue.EXCHANGE_ID;
        command.securityId = TestLaneVenue.SECURITY_ID;
        command.side = Side.Bid;
        command.price = 400_000_000L;
        command.size = SIZE;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        commands.commit(idx);
    }

    private static void drain(final ManyToOneRingBuffer<AccountReport> queue, final Map<Long, List<long[]>> reports) {
        queue.read(
                report -> reports.computeIfAbsent(report.report.getClientOidCounter(), k -> new ArrayList<>())
                        .add(new long[] {
                            report.sessionSlot,
                            report.report.decoder.execType().value(),
                            report.report.decoder.filledQty()
                        }),
                Integer.MAX_VALUE);
    }
}
