package group.gnometrading.gateways.account.reader;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Where a test's {@link OrderReports} write: every report and release, decoded, in order. */
public final class RecordedReports implements OrderReports.Output {

    public record Report(
            long slot,
            ExecType execType,
            long counter,
            long securityId,
            long filledQty,
            long fillPrice,
            long cumulativeQty,
            long leavesQty) {}

    public final List<Report> reports = new ArrayList<>();
    public final List<Long> released = new ArrayList<>();
    public final List<String> venueIds = new ArrayList<>();

    @Override
    public void publish(final OrderContext ctx, final OrderExecutionReport report) {
        this.reports.add(new Report(
                ctx.sessionSlot,
                report.decoder.execType(),
                report.getClientOidCounter(),
                report.decoder.securityId(),
                report.decoder.filledQty(),
                report.decoder.fillPrice(),
                report.decoder.cumulativeQty(),
                report.decoder.leavesQty()));
    }

    @Override
    public void released(final OrderContext ctx) {
        this.released.add(ctx.clientOidCounter);
    }

    @Override
    public void venueOrderId(final OrderContext ctx) {
        this.venueIds.add(
                new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII));
    }

    /** An order as the lane hands it over. */
    public static OrderContext order(
            final long slot,
            final long counter,
            final int exchangeId,
            final long securityId,
            final String correlationId,
            final long size) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = slot;
        ctx.clientOidCounter = counter;
        ctx.clientOidStrategyId = 1;
        ctx.exchangeId = exchangeId;
        ctx.securityId = securityId;
        ctx.originalQty = size;
        ctx.leavesQty = size;
        ctx.submittedAtMillis = System.currentTimeMillis();
        final byte[] id = correlationId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
        ctx.correlationIdLength = id.length;
        return ctx;
    }

    public List<ExecType> execTypes() {
        final List<ExecType> types = new ArrayList<>();
        for (final Report report : this.reports) {
            types.add(report.execType());
        }
        return types;
    }
}
