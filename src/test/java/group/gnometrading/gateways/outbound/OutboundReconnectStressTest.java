package group.gnometrading.gateways.outbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The writer, reader and supervisor on their own threads against a venue whose socket keeps dropping: orders are
 * submitted, filled and cancelled while the reader reconnects underneath them, losing whatever the venue sent to a
 * socket that was down and catching up from the venue's state each time. Every order must end with the fills the
 * venue made, each reported once, one final report if the venue ended it, and no slot left behind on either side.
 */
class OutboundReconnectStressTest {

    private static final Listing LISTING = new Listing(
            1,
            new Exchange(2, "TEST", "test", "global", SchemaType.MBP_10),
            new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
            "test-id",
            "TEST");
    private static final long UNIT = Statics.SIZE_SCALING_FACTOR;
    private static final long RUN_MILLIS = Long.getLong("stress.millis", 5_000);
    private static final int MAX_WORKING = 150;

    @Test
    @Timeout(120)
    void reconnectsWhileOrdersFillAndCancel_EveryOrderEndsExactlyAsTheVenueSays() throws Exception {
        final FakeVenue venue = new FakeVenue();
        final SequencedRingBuffer<Order> orders = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        final ManyToOneRingBuffer<OrderContext> newOrderQueue = queue();
        final ManyToOneRingBuffer<OrderContext> writerReportQueue = queue();
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue = queue();
        final SequencedRingBuffer<OrderExecutionReport> reports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence(), 1 << 16);
        final Map<Long, List<Report>> reportsByOrder = new ConcurrentHashMap<>();
        reports.handleEventsWith((seq, templateId, buffer, length) -> {
            final OrderExecutionReport report = new OrderExecutionReport();
            report.buffer.putBytes(0, buffer, 0, length);
            report.wrap(report.buffer);
            reportsByOrder
                    .computeIfAbsent(report.getClientOidCounter(), k -> new CopyOnWriteArrayList<>())
                    .add(new Report(
                            report.decoder.execType(), report.decoder.filledQty(), report.decoder.cumulativeQty()));
        });
        reports.start();

        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final StressWriter writer =
                new StressWriter(venue, orders, newOrderQueue, writerReportQueue, releasedOrderQueue);
        final StressReader reader =
                new StressReader(venue, reports, newOrderQueue, writerReportQueue, releasedOrderQueue);
        final OutboundGateway supervisor = new OutboundGateway(
                new NullLogger(),
                reader,
                new GatewayConfig.Builder()
                        .withMaxSilentInterval(Duration.ofSeconds(60))
                        .build(),
                new SystemEpochClock());
        final GnomeAgentRunner supervisorRunner = new GnomeAgentRunner(supervisor, errors::add);
        final GnomeAgentRunner readerRunner = new GnomeAgentRunner(reader, errors::add);
        final GnomeAgentRunner writerRunner = new GnomeAgentRunner(writer, errors::add);
        GnomeAgentRunner.startOnThread(supervisorRunner);
        GnomeAgentRunner.startOnThread(readerRunner);
        GnomeAgentRunner.startOnThread(writerRunner);

        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread market = new Thread(
                () -> {
                    final Random random = new Random(2);
                    while (running.get()) {
                        venue.fillOneUnit(random);
                        Thread.onSpinWait();
                    }
                },
                "market");
        final Thread chaos = new Thread(
                () -> {
                    final Random random = new Random(3);
                    while (running.get()) {
                        supervisor.forceReconnect();
                        sleepQuietly(1 + random.nextInt(15));
                    }
                },
                "chaos");
        market.start();
        chaos.start();

        // The OMS: submits and cancels at random, keeping well inside the writer's pool.
        final Random random = new Random(1);
        final List<Long> mine = new ArrayList<>();
        long nextOid = 1;
        final long until = System.currentTimeMillis() + RUN_MILLIS;
        while (System.currentTimeMillis() < until) {
            if (mine.size() < MAX_WORKING && random.nextInt(3) != 0) {
                publishOrder(orders, nextOid, (1 + random.nextInt(5)) * UNIT);
                mine.add(nextOid++);
            } else if (!mine.isEmpty()) {
                publishCancel(orders, mine.remove(random.nextInt(mine.size())));
            }
            mine.removeIf(oid -> venue.isEnded(oid));
            sleepQuietly(random.nextInt(2));
        }
        running.set(false);
        market.join();
        chaos.join();

        // One last reconnect, so whatever the final outage swallowed is caught up, then let everything drain.
        final int catchUpsBefore = reader.catchUps.get();
        supervisor.forceReconnect();
        waitFor(() -> reader.catchUps.get() > catchUpsBefore);
        waitForQuiet(reportsByOrder);

        writerRunner.close();
        reader.pauseControl.stop();
        readerRunner.close();
        supervisorRunner.close();
        reports.shutdown();

        System.out.println("stress: " + venue.snapshotByOid().size() + " orders, " + reader.catchUps.get()
                + " reconnects caught up, " + venue.lostEvents.get() + " venue updates lost to a dropped socket");
        assertTrue(errors.isEmpty(), "no agent failed: " + errors);
        assertTrue(reader.catchUps.get() > 50, "the run reconnected often: " + reader.catchUps.get());
        assertTrue(venue.lostEvents.get() > 0, "and lost venue updates to a dropped socket");

        int live = 0;
        for (final Map.Entry<Long, VenueOrder> entry : venue.snapshotByOid().entrySet()) {
            final long oid = entry.getKey();
            final VenueOrder venueOrder = entry.getValue();
            final List<Report> seen = reportsByOrder.getOrDefault(oid, List.of());
            final long reportedFill = seen.stream()
                    .filter(r -> r.execType == ExecType.FILL || r.execType == ExecType.PARTIAL_FILL)
                    .mapToLong(r -> r.filledQty)
                    .sum();
            assertEquals(venueOrder.filled, reportedFill, "order " + oid + " fills: " + seen);
            final long terminal = seen.stream().filter(Report::isTerminal).count();
            if (venueOrder.ended) {
                assertEquals(1, terminal, "order " + oid + " ended once: " + seen);
                // A cancel sent while the order was filling is refused afterwards; that changes nothing.
                final List<Report> changes = seen.stream()
                        .filter(r -> r.execType != ExecType.CANCEL_REJECT)
                        .toList();
                assertTrue(
                        changes.get(changes.size() - 1).isTerminal(), "nothing after the end of " + oid + ": " + seen);
            } else {
                assertEquals(0, terminal, "order " + oid + " is still working: " + seen);
                live++;
            }
        }
        assertEquals(live, writer.activeOrderCount(), "the writer holds exactly the working orders");
        assertEquals(live, reader.orderContextCount(), "and so does the reader");
    }

    // ========== The venue ==========

    private record VenueOrder(long size, long filled, boolean ended) {}

    /** An update as the venue sends it: cumulative, like Kalshi's. */
    private record VenueEvent(String id, long filled, boolean ended) {}

    /** Thread-safe: the writer, the market thread and the supervisor all call into it. */
    private static final class FakeVenue {
        private final Map<String, VenueOrder> orders = new HashMap<>();
        private final ConcurrentLinkedQueue<VenueEvent> socket = new ConcurrentLinkedQueue<>();
        private final AtomicInteger lostEvents = new AtomicInteger();
        private final Map<String, Long> live = new HashMap<>();
        private boolean connected;

        synchronized void place(final String id, final long size) {
            orders.put(id, new VenueOrder(size, 0, false));
            live.put(id, size);
            send(new VenueEvent(id, 0, false));
        }

        synchronized boolean has(final String id) {
            return orders.containsKey(id);
        }

        synchronized boolean cancel(final String id) {
            final VenueOrder order = orders.get(id);
            if (order == null || order.ended) {
                return false;
            }
            orders.put(id, new VenueOrder(order.size, order.filled, true));
            live.remove(id);
            send(new VenueEvent(id, order.filled, true));
            return true;
        }

        synchronized void fillOneUnit(final Random random) {
            if (live.isEmpty()) {
                return;
            }
            final List<String> ids = new ArrayList<>(live.keySet());
            final String id = ids.get(random.nextInt(ids.size()));
            final VenueOrder order = orders.get(id);
            final long filled = order.filled + UNIT;
            final boolean done = filled == order.size;
            orders.put(id, new VenueOrder(order.size, filled, done));
            if (done) {
                live.remove(id);
            }
            send(new VenueEvent(id, filled, done));
        }

        synchronized boolean isEnded(final long oid) {
            final VenueOrder order = orders.get(StressWriter.idFor(oid));
            return order != null && order.ended;
        }

        /** Sent to a socket that is down, an update is gone: only the venue's state still has it. */
        private void send(final VenueEvent event) {
            if (connected) {
                socket.add(event);
            } else {
                lostEvents.incrementAndGet();
            }
        }

        synchronized void connect() {
            socket.clear();
            connected = true;
        }

        synchronized void disconnect() {
            connected = false;
            socket.clear();
        }

        synchronized List<VenueEvent> state() {
            final List<VenueEvent> state = new ArrayList<>();
            orders.forEach((id, order) -> state.add(new VenueEvent(id, order.filled, order.ended)));
            return state;
        }

        synchronized Map<Long, VenueOrder> snapshotByOid() {
            final Map<Long, VenueOrder> byOid = new HashMap<>();
            orders.forEach((id, order) -> byOid.put(Long.parseLong(id.substring(2)), order));
            return byOid;
        }
    }

    // ========== Writer and reader against it ==========

    private static final class StressWriter extends OutboundSocketWriter {
        private final FakeVenue venue;

        StressWriter(
                final FakeVenue venue,
                final SequencedRingBuffer<Order> orders,
                final ManyToOneRingBuffer<OrderContext> newOrderQueue,
                final ManyToOneRingBuffer<OrderContext> writerReportQueue,
                final ManyToOneRingBuffer<OrderContext> releasedOrderQueue) {
            super(orders, newOrderQueue, writerReportQueue, releasedOrderQueue);
            this.venue = venue;
        }

        static String idFor(final long oid) {
            return "s-" + oid;
        }

        @Override
        protected boolean prepareOrder(final OrderContext ctx) {
            final byte[] id = idFor(ctx.clientOidCounter).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
            ctx.correlationIdLength = id.length;
            System.arraycopy(id, 0, ctx.exchangeOrderIdBytes, 0, id.length);
            ctx.exchangeOrderIdLength = id.length;
            return true;
        }

        @Override
        protected SubmitResult submitOrder(final OrderContext ctx) {
            venue.place(idFor(ctx.clientOidCounter), ctx.originalQty);
            return SubmitResult.ACCEPTED;
        }

        @Override
        protected SubmitResult findOrder(final OrderContext ctx) {
            return venue.has(idFor(ctx.clientOidCounter)) ? SubmitResult.ACCEPTED : SubmitResult.REJECTED;
        }

        @Override
        protected boolean cancelOrder(final OrderContext ctx) {
            return venue.cancel(idFor(ctx.clientOidCounter));
        }
    }

    private static final class StressReader extends OutboundSocketReader {
        private final FakeVenue venue;
        private final AtomicInteger catchUps = new AtomicInteger();
        private List<VenueEvent> fetched = List.of();

        StressReader(
                final FakeVenue venue,
                final SequencedRingBuffer<OrderExecutionReport> reports,
                final ManyToOneRingBuffer<OrderContext> newOrderQueue,
                final ManyToOneRingBuffer<OrderContext> writerReportQueue,
                final ManyToOneRingBuffer<OrderContext> releasedOrderQueue) {
            super(
                    new NullLogger(),
                    reports,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    System::nanoTime,
                    LISTING);
            this.venue = venue;
        }

        @Override
        protected ByteBuffer readSocket() {
            final VenueEvent event = venue.socket.poll();
            if (event == null) {
                return null;
            }
            return ByteBuffer.wrap(
                    (event.id + "," + event.filled + "," + event.ended).getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        protected void handleGatewayMessage(final ByteBuffer buffer) {
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            final String[] fields = new String(bytes, StandardCharsets.US_ASCII).split(",");
            apply(new VenueEvent(fields[0], Long.parseLong(fields[1]), Boolean.parseBoolean(fields[2])));
        }

        @Override
        protected void fetchVenueState() {
            this.fetched = venue.state();
        }

        @Override
        protected void applyVenueState() {
            for (final VenueEvent event : this.fetched) {
                apply(event);
            }
            this.fetched = List.of();
            catchUps.incrementAndGet();
        }

        /** Cumulative, as a venue reader does it: reports only what changed, and ends an order once. */
        private void apply(final VenueEvent event) {
            final byte[] id = event.id.getBytes(StandardCharsets.US_ASCII);
            final long key = computeKey(id, id.length);
            final OrderContext ctx = findOrderContext(key);
            if (ctx == null) {
                return;
            }
            if (event.filled > ctx.cumulativeFilledQty) {
                final long fill = event.filled - ctx.cumulativeFilledQty;
                ctx.cumulativeFilledQty = event.filled;
                ctx.leavesQty = event.ended ? 0 : ctx.originalQty - event.filled;
                final boolean full = event.filled == ctx.originalQty;
                prepareExecReportHeader(ctx);
                execReport.encoder.execType(full ? ExecType.FILL : ExecType.PARTIAL_FILL);
                execReport.encoder.orderStatus(full ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
                execReport.encoder.filledQty(fill);
                execReport.encoder.fillPrice(0);
                execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
                execReport.encoder.leavesQty(ctx.leavesQty);
                execReport.encoder.timestampEvent(0);
                execReport.encoder.timestampRecv(clock.nanoTime());
                execReport.encoder.fee(0);
                execReport.encoder.rejectReason(RejectReason.NULL_VAL);
                publishExecReport();
                if (full) {
                    releaseOrderContext(key);
                    return;
                }
            }
            if (event.ended) {
                prepareExecReportHeader(ctx);
                execReport.encoder.execType(ExecType.CANCEL);
                execReport.encoder.orderStatus(OrderStatus.CANCELED);
                execReport.encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
                execReport.encoder.cumulativeQty(ctx.cumulativeFilledQty);
                execReport.encoder.leavesQty(0);
                execReport.encoder.rejectReason(RejectReason.NULL_VAL);
                publishExecReport();
                releaseOrderContext(key);
            } else if (!ctx.acked) {
                publishNew(ctx, ctx.cumulativeFilledQty, ctx.originalQty - ctx.cumulativeFilledQty, 0, 0);
            }
        }

        @Override
        protected void attachSocket() {
            venue.connect();
        }

        @Override
        protected void disconnectSocket() {
            venue.disconnect();
        }

        @Override
        protected void subscribe() {}

        @Override
        public void keepAlive() {}
    }

    // ========== Helpers ==========

    private record Report(ExecType execType, long filledQty, long cumulativeQty) {
        boolean isTerminal() {
            return execType == ExecType.FILL || execType == ExecType.CANCEL;
        }
    }

    private static ManyToOneRingBuffer<OrderContext> queue() {
        return new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, OrderContext.HANDOFF_QUEUE_CAPACITY);
    }

    private static void publishOrder(final SequencedRingBuffer<Order> orders, final long oid, final long size) {
        final Order order = orders.claim();
        order.encoder.exchangeId(2);
        order.encoder.securityId(3);
        order.encoder.price(Statics.PRICE_SCALING_FACTOR / 2);
        order.encoder.size(size);
        order.encoder.side(Side.Bid);
        order.encoder.orderType(OrderType.LIMIT);
        order.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encoder.flags().clear();
        order.encodeClientOid(oid, 1);
        orders.publish();
    }

    private static void publishCancel(final SequencedRingBuffer<Order> orders, final long oid) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(2);
        cancel.encoder.securityId(3);
        cancel.encodeClientOid(oid, 1);
        orders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    private static void waitFor(final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(5);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting");
    }

    /** Until no report has arrived for a while. */
    private static void waitForQuiet(final Map<Long, List<Report>> reports) {
        int last = -1;
        int quietFor = 0;
        while (quietFor < 10) {
            final int now = reports.values().stream().mapToInt(List::size).sum();
            quietFor = now == last ? quietFor + 1 : 0;
            last = now;
            sleepQuietly(50);
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
