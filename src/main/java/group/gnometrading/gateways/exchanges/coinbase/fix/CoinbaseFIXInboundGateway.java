package group.gnometrading.gateways.exchanges.coinbase.fix;

import com.lmax.disruptor.RingBuffer;
import group.gnometrading.gateways.fix.*;
import group.gnometrading.gateways.fix.fix50sp2.FIX50SP2Enumerations;
import group.gnometrading.gateways.fix.fix50sp2.FIX50SP2MsgTypes;
import group.gnometrading.gateways.fix.fix50sp2.FIX50SP2Tags;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Schema;
import org.agrona.concurrent.EpochNanoClock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class CoinbaseFIXInboundGateway extends FIXMarketInboundGateway {

    private static final String API_KEY_KEY = "coinbase.api.key";
    private static final String API_PASSPHRASE_KEY = "coinbase.api.passphrase";
    private static final String API_SECRET_KEY = "coinbase.api.secret";
    private static final String ALGO = "HmacSHA256";
    private static final int SIGNATURE_BUFFER_SIZE = 1 << 10; // 1kb

    private final String apiKey;
//    private final String apiPassphrase;
    private final String apiSecret;
    private final Mac mac;
    private final ByteBuffer signBuffer;

    public CoinbaseFIXInboundGateway(
            RingBuffer<Schema<?, ?>> ringBuffer,
            EpochNanoClock clock,
            SocketClient socketClient,
            FIXConfig fixConfig,
            Properties properties
    ) {
        super(ringBuffer, clock, socketClient, fixConfig);

        this.apiKey = properties.getStringProperty(API_KEY_KEY);
//        this.apiPassphrase = properties.getStringProperty(API_PASSPHRASE_KEY);
        this.apiSecret = properties.getStringProperty(API_SECRET_KEY);
        this.signBuffer = ByteBuffer.allocate(SIGNATURE_BUFFER_SIZE);
        try {
            this.mac = Mac.getInstance(ALGO);
            Key key = new SecretKeySpec(Base64.getDecoder().decode(this.apiSecret), ALGO);
            this.mac.init(key);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Error initializing Coinbase key", e);
        }
    }

    @Override
    protected void handleMarketUpdate(FIXMessage message) {

    }

    @Override
    public void onStart() {
        this.connect();
    }

    @Override
    public void onClose() {

    }

    @Override
    protected void reconnect() {
        try {
            this.socketClient.close();
            this.socketClient.clearBuffers();
            this.connect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleLogout(FIXMessage message) {
        throw new RuntimeException("Socket logout");
    }

    @Override
    public void handleHeartbeatTimeout() {

    }

    @Override
    public void onSocketClose() {
        throw new RuntimeException("Socket closed");
    }

    private void connect() {
        try {
            this.socketClient.connect();
            this.sendLogon();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendLogon() throws IOException {
        this.fixSession.prepareMessage(this.adminMessage, FIXDefaultMsgTypes.Logon);

        this.adminMessage.addTag(FIX50SP2Tags.EncryptMethod).setInt(FIX50SP2Enumerations.EncryptMethodValues.None);
        this.adminMessage.addTag(FIX50SP2Tags.HeartBtInt).setInt(this.fixConfig.heartbeatSeconds());
        this.adminMessage.addTag(FIX50SP2Tags.ResetSeqNumFlag).setBoolean(true);
        this.adminMessage.addTag(FIX50SP2Tags.Username).setString(this.apiKey);
        this.adminMessage.addTag(FIX50SP2Tags.Password).setString("pass");
        this.adminMessage.addTag(FIX50SP2Tags.DefaultApplVerID).setInt(this.fixConfig.applicationVersion().getApplicationVersionID());

        this.adminMessage.addTag(CoinbaseTags.DefaultSelfTradePreventionStrategy).setChar(CoinbaseEnumerations.DefaultSelfTradePreventionStrategy.CancelAggressingOrders);
        this.adminMessage.addTag(CoinbaseTags.CancelOrdersOnDisconnect).setChar(CoinbaseEnumerations.CancelOrdersOnDisconnect.CancelAllProfileOrders);
        this.adminMessage.addTag(CoinbaseTags.DropCopyFlag).setChar(CoinbaseEnumerations.DropCopyFlag.NormalOrderEntry);

        this.signBuffer.clear();
        this.adminMessage.getTag(FIX50SP2Tags.SendingTime).copyTo(this.signBuffer);
        this.signBuffer.put(FIXConstants.SOH);
        this.signBuffer.putChar(FIX50SP2MsgTypes.Logon);
        this.signBuffer.put(FIXConstants.SOH);
        this.adminMessage.getTag(FIX50SP2Tags.MsgSeqNum).copyTo(this.signBuffer);
        this.signBuffer.put(FIXConstants.SOH);
        this.adminMessage.getTag(FIX50SP2Tags.SenderCompID).copyTo(this.signBuffer);
        this.signBuffer.put(FIXConstants.SOH);
        this.adminMessage.getTag(FIX50SP2Tags.TargetCompID).copyTo(this.signBuffer);
        this.signBuffer.put(FIXConstants.SOH);


        mac.update(this.signBuffer);
        final byte[] signature = mac.doFinal();
        int size = Base64.getEncoder().encode(signature, this.signBuffer.array());

        this.signBuffer.position(0);
        this.signBuffer.limit(size);
        this.adminMessage.addTag(FIX50SP2Tags.RawDataLength).setInt(size);
        this.adminMessage.addTag(FIX50SP2Tags.RawData).setByteBuffer(this.signBuffer);

        System.out.println(this.adminMessage);

        this.fixSession.send(this.adminMessage);
    }
}
