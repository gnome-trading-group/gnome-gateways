package group.gnometrading.gateways.fix;

public interface FixStatusListener {

    void handleLogout(FixMessage message);

    void handleHeartbeatTimeout();

    default void handleReject(final FixMessage message) {}

    default void handleTooLowMsgSeqNum(final FixMessage message, final int inMsgSeqNum) {}
}
