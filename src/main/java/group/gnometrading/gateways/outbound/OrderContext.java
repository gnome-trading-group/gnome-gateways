package group.gnometrading.gateways.outbound;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;

public final class OrderContext {

    public static final int EXCHANGE_ORDER_ID_MAX_LENGTH = 70;

    public long orderId;
    public long clientOidCounter;
    public int clientOidStrategyId;
    public int exchangeId;
    public long securityId;
    public long originalQty;
    public long cumulativeFilledQty;
    public long leavesQty;
    public long cumulativeCost;
    public Side side;
    public ExecType execType;
    public OrderStatus orderStatus;
    public RejectReason rejectReason;
    public final byte[] exchangeOrderIdBytes = new byte[EXCHANGE_ORDER_ID_MAX_LENGTH];
    public int exchangeOrderIdLength;

    public void reset() {
        this.orderId = 0;
        this.clientOidCounter = 0;
        this.clientOidStrategyId = 0;
        this.exchangeId = 0;
        this.securityId = 0;
        this.originalQty = 0;
        this.cumulativeFilledQty = 0;
        this.leavesQty = 0;
        this.cumulativeCost = 0;
        this.side = null;
        this.execType = null;
        this.orderStatus = null;
        this.rejectReason = null;
        this.exchangeOrderIdLength = 0;
    }

    public void copyFrom(final OrderContext src) {
        this.orderId = src.orderId;
        this.clientOidCounter = src.clientOidCounter;
        this.clientOidStrategyId = src.clientOidStrategyId;
        this.exchangeId = src.exchangeId;
        this.securityId = src.securityId;
        this.originalQty = src.originalQty;
        this.cumulativeFilledQty = src.cumulativeFilledQty;
        this.leavesQty = src.leavesQty;
        this.cumulativeCost = src.cumulativeCost;
        this.side = src.side;
        this.execType = src.execType;
        this.orderStatus = src.orderStatus;
        this.rejectReason = src.rejectReason;
        this.exchangeOrderIdLength = src.exchangeOrderIdLength;
        System.arraycopy(src.exchangeOrderIdBytes, 0, this.exchangeOrderIdBytes, 0, src.exchangeOrderIdLength);
    }
}
