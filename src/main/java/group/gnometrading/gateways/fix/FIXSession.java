package group.gnometrading.gateways.fix;

import group.gnometrading.networking.client.SocketClient;

import java.io.IOException;

import static group.gnometrading.gateways.fix.FIXDefaultTags.*;
import static group.gnometrading.gateways.fix.FIXDefaultMsgTypes.*;

public class FIXSession {

    private final FIXConfig fixConfig;
    private final SocketClient socketClient;
    private final FIXMessage adminMessage;
    private final FIXStatusListener statusListener;
    private int inMsgSeqNum, outMsgSeqNum;
    private long currentTimestamp, lastTxMillis, lastRxMillis, testRequestTxMillis;
    private final long testRequestMillis, heartbeatMillis;

    public FIXSession(final FIXConfig fixConfig, final SocketClient socketClient, final FIXStatusListener listener) {
        this.fixConfig = fixConfig;
        this.socketClient = socketClient;
        this.statusListener = listener;
        this.adminMessage = new FIXMessage(this.fixConfig);

        this.currentTimestamp = System.currentTimeMillis();
        this.lastRxMillis = currentTimestamp;
        this.lastTxMillis = currentTimestamp;

        this.heartbeatMillis = this.fixConfig.heartbeatSeconds() * 1_000L;
        this.testRequestMillis = this.fixConfig.heartbeatSeconds() * 1_100L;
        this.testRequestTxMillis = 0;
    }

    public void prepareMessage(final FIXMessage message, final char msgType) {
        message.reset();
        message.addTag(MsgType).setChar(msgType);
        prepare(message);
    }

    private void prepare(final FIXMessage message) {
        message.addTag(SenderCompID).setString(fixConfig.senderCompID());
        message.addTag(TargetCompID).setString(fixConfig.targetCompID());
        message.addTag(MsgSeqNum).setInt(outMsgSeqNum);
        message.addTag(SendingTime).setTimestamp(currentTimestamp, this.fixConfig.defaultPrecision());
    }

    public void send(final FIXMessage message) throws IOException {
        int bytes = message.writeToBuffer(this.socketClient.getWriteBuffer());
        int written = this.socketClient.write();
        if (bytes != written) {
            throw new RuntimeException("Did not write entire bytes. What do we do? " + bytes + " != " + written);
        }

        outMsgSeqNum++;
        lastTxMillis = this.currentTimestamp;
    }

    /**
     * Handle a FIX message from the exchange. If the message is an admin message, the session will handle the
     * response and return true. If the message is a generic market update, the method will return false and
     * pass through back to gateway.
     * @param message the message to handle
     * @return true if the message is handled within the session
     */
    public boolean handleFIXMessage(final FIXMessage message) throws IOException {
        this.currentTimestamp = System.currentTimeMillis();
        this.lastRxMillis = this.currentTimestamp;

        final int msgSeqNum = message.getMsgSeqNum();
        final var msgType = message.getTag(FIXDefaultTags.MsgType);

        if (msgType == null || msgSeqNum == 0) {
            throw new RuntimeException("Invalid FIX message");
        }

        if (msgType.asChar() == FIXDefaultMsgTypes.SequenceReset && msgType.getLength() == 1) {
            if (handleSequenceReset(message)) {
                inMsgSeqNum++;
            }
            return true;
        }

        if (msgSeqNum != inMsgSeqNum) {
            handleMsgSeqNum(message, msgType, msgSeqNum);
            return true;
        }

        inMsgSeqNum++;

        if (msgType.getLength() != 1) {
            return false;
        }

        return switch (msgType.asChar()) {
            case Heartbeat ->
                // NO-OP - lastRxMillis is already updated
                true;
            case TestRequest -> {
                handleTestRequest(message);
                yield true;
            }
            case Reject -> {
                handleReject(message);
                yield true;
            }
            case Logout -> {
                handleLogout(message);
                yield true;
            }
            default -> false;
        };

    }

    public void keepAlive() throws IOException {
        if (this.currentTimestamp - this.lastTxMillis > this.heartbeatMillis)
            sendHeartbeat(null);

        if (this.testRequestTxMillis == 0) {
            if (this.currentTimestamp - this.lastRxMillis > this.testRequestMillis) {
                sendTestRequest((int) this.currentTimestamp);

                this.testRequestTxMillis = this.currentTimestamp;
            }
        } else {
            if (this.currentTimestamp - this.testRequestTxMillis > this.testRequestMillis) {
                this.testRequestTxMillis = 0;
                this.statusListener.handleHeartbeatTimeout();
            }
        }
    }

    private void sendTestRequest(int testReqId) throws IOException {
        prepareMessage(adminMessage, TestRequest);
        adminMessage.getTag(TestReqID).setInt(testReqId);
        send(adminMessage);
    }


    private boolean handleSequenceReset(final FIXMessage message) throws IOException {
        final FIXValue value = message.getTag(NewSeqNo);
        if (value == null) {
            sendReject(message);
            return true;
        }

        final int newSeqNo = value.asInt();
        if (newSeqNo < inMsgSeqNum) {
            sendReject(message);
            return true;
        }

        inMsgSeqNum = newSeqNo;

        final FIXValue gapFillFlag = message.getTag(GapFillFlag);

        return gapFillFlag == null || !gapFillFlag.asBoolean();
    }

    private void handleMsgSeqNum(final FIXMessage message, final FIXValue msgType, final int msgSeqNum) throws IOException {
        if (msgSeqNum < inMsgSeqNum) {
            handleTooLowMsgSeqNum(message, msgType);
        } else {
            prepareMessage(adminMessage, ResendRequest);
            adminMessage.addTag(BeginSeqNo).setInt(msgSeqNum);
            adminMessage.addTag(EndSeqNo).setInt(inMsgSeqNum);
            send(adminMessage);
        }
    }

    private void handleTooLowMsgSeqNum(final FIXMessage message, final FIXValue msgType) {
        if (msgType.getLength() == 1 && msgType.asChar() == Logout) {
            handleLogout(message);
        } else {
            final FIXValue possDupFlag = message.getTag(PossDupFlag);
            if (possDupFlag == null || !possDupFlag.asBoolean())
                statusListener.handleTooLowMsgSeqNum(message, inMsgSeqNum);
        }
    }

    private void handleTestRequest(final FIXMessage message) throws IOException {
        final FIXValue testReqId = message.getTag(TestReqID);
        if (testReqId == null) {
            sendReject(message);
            return;
        }
        sendHeartbeat(testReqId);
    }

    public void sendHeartbeat(final FIXValue testReqId) throws IOException {
        prepareMessage(adminMessage, Heartbeat);
        if (testReqId != null) {
            adminMessage.getTag(TestReqID).set(testReqId);
        }
        send(adminMessage);
    }

    public void sendReject(final FIXMessage rejected) throws IOException {
        prepareMessage(adminMessage, Reject);
        adminMessage.addTag(RefSeqNum).setInt(rejected.getMsgSeqNum());
        send(adminMessage);
    }

    private void handleLogout(final FIXMessage message) {
        this.statusListener.handleLogout(message);
    }

    private void handleReject(final FIXMessage message) {
        this.statusListener.handleReject(message);
    }

}
