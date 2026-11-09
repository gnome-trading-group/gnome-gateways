package group.gnometrading.gateways.account.reader;

import group.gnometrading.schemas.OrderExecutionReport;

/** One report from an account's reader, for the session in {@link #sessionSlot}. Pooled in the reader's out ring. */
public final class AccountReport {

    public long sessionSlot;
    public final OrderExecutionReport report = new OrderExecutionReport();

    void copyFrom(final long slot, final OrderExecutionReport src) {
        this.sessionSlot = slot;
        this.report.buffer.putBytes(0, src.buffer, 0, src.totalMessageSize());
    }
}
