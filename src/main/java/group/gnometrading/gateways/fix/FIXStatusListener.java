package group.gnometrading.gateways.fix;

public interface FIXStatusListener {

    void handleLogout(final FIXMessage message);

    void handleHeartbeatTimeout();

    default void handleReject(final FIXMessage message) {}

    default void handleTooLowMsgSeqNum(final FIXMessage message, final int inMsgSeqNum) {}

}
