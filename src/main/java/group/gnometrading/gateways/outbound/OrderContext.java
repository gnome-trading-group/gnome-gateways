package group.gnometrading.gateways.outbound;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;

public final class OrderContext {

    public static final int EXCHANGE_ORDER_ID_MAX_LENGTH = 70;

    /**
     * Orders a gateway may have live at once. Both the writer and the reader hold a copy of every
     * live order, so both pools must be sized from this one bound.
     */
    public static final int MAX_IN_FLIGHT_ORDERS = 256;

    /**
     * Capacity of each writer/reader handoff queue. One writer poll handles at most one outbound
     * buffer's worth of messages, so twice the in-flight bound leaves headroom for every live order
     * to complete plus that poll's own submissions before the writer drains again.
     */
    public static final int HANDOFF_QUEUE_CAPACITY = 2 * MAX_IN_FLIGHT_ORDERS;

    /** Marks a quantity the venue did not report. */
    public static final long QTY_ABSENT = -1L;

    public long clientOidCounter;
    public int clientOidStrategyId;
    public int exchangeId;
    public long securityId;
    public long originalQty;
    public long cumulativeFilledQty;
    public long leavesQty;
    public long cumulativeCost;
    public long cumulativeMakerCost;
    public long cumulativeFees;

    // Reader-side: the order has been acknowledged to the OMS with an ExecType.NEW.
    public boolean acked;
    // Venue fill count at the most recent accepted amend; fills at or below it predate the amend.
    public long amendFillCount = QTY_ABSENT;
    // Writer-to-reader marker: this context reports an accepted amend rather than a report to replay.
    public boolean amendAccepted;
    public Side side;
    public short flags;
    public ExecType execType;
    public OrderStatus orderStatus;
    public RejectReason rejectReason;
    public final byte[] exchangeOrderIdBytes = new byte[EXCHANGE_ORDER_ID_MAX_LENGTH];
    public int exchangeOrderIdLength;
    // An id we choose before sending the order, which the venue echoes on every event for it, so the
    // reader can match events even if the submit's response never arrives. It is also the id every
    // execution report carries as exchangeOrderId: the one a later process can find the order by.
    public final byte[] correlationIdBytes = new byte[EXCHANGE_ORDER_ID_MAX_LENGTH];
    public int correlationIdLength;

    public void reset() {
        this.clientOidCounter = 0;
        this.clientOidStrategyId = 0;
        this.exchangeId = 0;
        this.securityId = 0;
        this.originalQty = 0;
        this.cumulativeFilledQty = 0;
        this.leavesQty = 0;
        this.cumulativeCost = 0;
        this.cumulativeMakerCost = 0;
        this.cumulativeFees = 0;
        this.acked = false;
        this.amendFillCount = QTY_ABSENT;
        this.amendAccepted = false;
        this.side = null;
        this.flags = 0;
        this.execType = null;
        this.orderStatus = null;
        this.rejectReason = null;
        this.exchangeOrderIdLength = 0;
        this.correlationIdLength = 0;
    }

    public void copyFrom(final OrderContext src) {
        this.clientOidCounter = src.clientOidCounter;
        this.clientOidStrategyId = src.clientOidStrategyId;
        this.exchangeId = src.exchangeId;
        this.securityId = src.securityId;
        this.originalQty = src.originalQty;
        this.cumulativeFilledQty = src.cumulativeFilledQty;
        this.leavesQty = src.leavesQty;
        this.cumulativeCost = src.cumulativeCost;
        this.cumulativeMakerCost = src.cumulativeMakerCost;
        this.cumulativeFees = src.cumulativeFees;
        this.acked = src.acked;
        this.amendFillCount = src.amendFillCount;
        this.amendAccepted = src.amendAccepted;
        this.side = src.side;
        this.flags = src.flags;
        this.execType = src.execType;
        this.orderStatus = src.orderStatus;
        this.rejectReason = src.rejectReason;
        this.exchangeOrderIdLength = src.exchangeOrderIdLength;
        System.arraycopy(src.exchangeOrderIdBytes, 0, this.exchangeOrderIdBytes, 0, src.exchangeOrderIdLength);
        this.correlationIdLength = src.correlationIdLength;
        System.arraycopy(src.correlationIdBytes, 0, this.correlationIdBytes, 0, src.correlationIdLength);
    }
}
