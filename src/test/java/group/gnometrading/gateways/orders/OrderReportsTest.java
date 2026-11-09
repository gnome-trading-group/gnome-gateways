package group.gnometrading.gateways.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.RejectReason;
import java.util.List;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Test;

/** What the sending thread's notices turn into, given what the venue's events have already reported. */
class OrderReportsTest {

    private final RecordedReports recorded = new RecordedReports();
    private final OrderReports reports = new OrderReports(16, SystemEpochNanoClock.INSTANCE, this.recorded);
    private final ManyToOneRingBuffer<OrderContext> queue =
            new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 16);
    private final ReaderNotices notices = new ReaderNotices(this.queue);

    @Test
    void aRejectForAnOrderTheVenueAlreadyEndedIsDropped() {
        final OrderContext order = RecordedReports.order(4, 1, 2, 3, "a", 1_000_000L);
        this.notices.register(order);
        drain();
        final OrderContext held = this.reports.order(OrderKeys.of(order));
        held.cumulativeFilledQty = 1_000_000L;
        held.leavesQty = 0;
        this.reports.fill(held, 1_000_000L, 500_000_000L, 0, Liquidity.MAKER, true, 0);
        this.reports.release(held);

        // The sender's lookup missed it, as the venue's reads lagged: it would end the order a second time.
        this.notices.rejectRegistered(order, RejectReason.EXCHANGE_REJECTED);
        drain();

        assertEquals(List.of(ExecType.FILL), this.recorded.execTypes(), "one terminal report");
    }

    @Test
    void aRejectForAnOrderStillHeldEndsIt() {
        final OrderContext order = RecordedReports.order(4, 1, 2, 3, "a", 1_000_000L);
        this.notices.register(order);
        this.notices.rejectRegistered(order, RejectReason.EXCHANGE_REJECTED);
        drain();
        assertEquals(List.of(ExecType.REJECT), this.recorded.execTypes());
        assertEquals(0, this.reports.size());
        assertTrue(this.recorded.released.isEmpty(), "the sender ended it itself");
    }

    @Test
    void aGivenUpOrderEndsWithTheFillsTheVenuesEventsBrought() {
        final OrderContext order = RecordedReports.order(4, 1, 2, 3, "a", 3_000_000L);
        this.notices.register(order);
        drain();
        final OrderContext held = this.reports.order(OrderKeys.of(order));
        held.cumulativeFilledQty = 1_000_000L;
        held.leavesQty = 2_000_000L;
        this.reports.fill(held, 1_000_000L, 500_000_000L, 0, Liquidity.MAKER, false, 0);

        this.notices.abandon(order);
        drain();

        assertEquals(List.of(ExecType.PARTIAL_FILL, ExecType.CANCEL), this.recorded.execTypes());
        assertEquals(1_000_000L, this.recorded.reports.get(1).cumulativeQty(), "the fills it had, kept");
        assertEquals(List.of(1L), this.recorded.released);
        assertEquals(0, this.reports.size());
    }

    @Test
    void aGivenUpOrderTheVenueAlreadyEndedIsNotEndedAgain() {
        final OrderContext order = RecordedReports.order(4, 1, 2, 3, "a", 3_000_000L);
        this.notices.register(order);
        drain();
        this.reports.cancel(this.reports.order(OrderKeys.of(order)), 0);
        this.notices.abandon(order);
        drain();
        assertEquals(List.of(ExecType.CANCEL), this.recorded.execTypes());
    }

    @Test
    void reportsForOrdersNeverHandedOverAreAlwaysPublished() {
        this.notices.rejectUnheld(4, 1, 1, 2, 3, RejectReason.SESSION_CAP);
        this.notices.canceledUnheld(4, 2, 1, 2, 3);
        drain();
        assertEquals(List.of(ExecType.REJECT, ExecType.CANCEL), this.recorded.execTypes());
        assertEquals(4, this.recorded.reports.get(1).slot());
    }

    @Test
    void aVenueIdLearnedFromAnEventGoesBackToTheSender() {
        final OrderContext order = RecordedReports.order(4, 1, 2, 3, "a", 1_000_000L);
        this.notices.register(order);
        drain();
        final OrderContext held = this.reports.order(OrderKeys.of(order));
        held.exchangeOrderIdBytes[0] = 'v';
        held.exchangeOrderIdLength = 1;
        this.reports.venueOrderIdLearned(held);
        assertEquals(List.of("v"), this.recorded.venueIds);
    }

    private void drain() {
        this.queue.read(this.reports::onNotice, Integer.MAX_VALUE);
    }
}
