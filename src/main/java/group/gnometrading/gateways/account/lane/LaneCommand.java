package group.gnometrading.gateways.account.lane;

import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

/**
 * What a session asks of an account's lane: a new order, a cancel or an amend, named by session slot and counter; or,
 * from the gateway itself, the cancel of every order a session has.
 */
public final class LaneCommand {

    public static final int NEW = 1;
    public static final int CANCEL = 2;
    public static final int AMEND = 3;

    /**
     * Every order of the session in {@link #sessionSlot}: cancelled, and new orders still held here dropped, as when the
     * session has gone silent. Each order is answered as its own cancel would be.
     */
    public static final int CANCEL_SESSION = 4;

    /**
     * The session in {@link #sessionSlot} joins the account, or changes its terms: its share of the budget
     * ({@link #weight}) and how long its new orders and amends may wait for it ({@link #maxQueueNanos}). Sent before its
     * first order; a session never introduced gets weight 1 and the lane's default wait.
     */
    public static final int SESSION = 5;

    /** The gateway is done with the session in {@link #sessionSlot}: its place in the fair share is given up. */
    public static final int SESSION_ENDED = 6;

    /**
     * The working orders of the session in {@link #sessionSlot}, on the listing {@link #exchangeId}/{@link #securityId}
     * or on every listing when both are 0: cancelled, and its new orders still held here dropped, as for a kill. Unlike
     * {@link #CANCEL_SESSION} the session isn't fenced: its later orders are the gateway's to refuse or let through.
     */
    public static final int CANCEL_ORDERS = 7;

    public int kind;
    public long sessionSlot;
    public long clientOidCounter;
    public int clientOidStrategyId;
    public int exchangeId;
    public long securityId;
    public Side side;
    public long price;
    public long size;
    public TimeInForce timeInForce;
    public OrderType orderType;
    public boolean postOnly;
    public int weight;
    public long maxQueueNanos;
    // A SESSION's caps as it asked for them, 0 for the lane's default, which also bounds them: orders it may hold at
    // once, and new orders a second.
    public int maxOpenOrders;
    public int maxNewPerSecond;

    public void reset() {
        this.kind = 0;
        this.sessionSlot = 0;
        this.clientOidCounter = 0;
        this.clientOidStrategyId = 0;
        this.exchangeId = 0;
        this.securityId = 0;
        this.side = null;
        this.price = 0;
        this.size = 0;
        this.timeInForce = null;
        this.orderType = null;
        this.postOnly = false;
        this.weight = 0;
        this.maxQueueNanos = 0;
        this.maxOpenOrders = 0;
        this.maxNewPerSecond = 0;
    }

    public void copyFrom(final LaneCommand src) {
        this.kind = src.kind;
        this.sessionSlot = src.sessionSlot;
        this.clientOidCounter = src.clientOidCounter;
        this.clientOidStrategyId = src.clientOidStrategyId;
        this.exchangeId = src.exchangeId;
        this.securityId = src.securityId;
        this.side = src.side;
        this.price = src.price;
        this.size = src.size;
        this.timeInForce = src.timeInForce;
        this.orderType = src.orderType;
        this.postOnly = src.postOnly;
        this.weight = src.weight;
        this.maxQueueNanos = src.maxQueueNanos;
        this.maxOpenOrders = src.maxOpenOrders;
        this.maxNewPerSecond = src.maxNewPerSecond;
    }
}
