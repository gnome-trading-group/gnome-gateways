package group.gnometrading.gateways.coinbase;

import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.gateways.fix.FIXConfig;
import group.gnometrading.gateways.fix.FIXDefaultMsgTypes;
import group.gnometrading.gateways.fix.FIXMarketInboundGateway;
import group.gnometrading.gateways.fix.FIXMessage;
import group.gnometrading.gateways.fix.fix50sp2.FIX50SP2Tags;
import group.gnometrading.networking.client.SocketClient;
import group.gnometrading.resources.Properties;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;

import javax.crypto.Mac;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

public class CoinbaseInboundGateway extends FIXMarketInboundGateway {

    private static final String API_KEY_KEY = "coinbase.api.key";
    private static final String API_PASSPHRASE_KEY = "coinbase.api.passphrase";
    private static final String API_SECRET_KEY = "coinbase.api.secret";

    private final String apiKey;
    private final String apiPassphrase;
    private final String apiSecret;
    private final Mac mac;

    public CoinbaseInboundGateway(
            final SocketClient socketClient,
            final Publication publication,
            final Decoder<FIXMessage> decoder,
            final FIXMessage messageHolder,
            final List<Listing> listings,
            final FIXConfig fixConfig,
            final Properties properties
    ) {
        super(socketClient, publication, decoder, messageHolder, listings, fixConfig);

        this.apiKey = properties.getStringProperty(API_KEY_KEY);
        this.apiPassphrase = properties.getStringProperty(API_PASSPHRASE_KEY);
        this.apiSecret = properties.getStringProperty(API_SECRET_KEY);
        try {
            this.mac = Mac.getInstance("HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to find SHA-256 algorithm");
        }
    }

    @Override
    protected void handleMarketUpdate(FIXMessage message) {

    }

    @Override
    public void onStart() {
    }

    @Override
    public void handleLogout(FIXMessage message) {

    }

    @Override
    public void handleHeartbeatTimeout() {

    }

    @Override
    public void onSocketClose() {

    }

    private void connect() {
        // TODO: How do we reconnect a socket here?
        try {
            this.socketClient.connect();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendLogon() {
        this.fixSession.prepareMessage(this.adminMessage, FIXDefaultMsgTypes.Logon);

        this.adminMessage.addTag(FIX50SP2Tags.EncryptMethod).setInt(0);
        this.adminMessage.addTag(FIX50SP2Tags.HeartBtInt).setInt(this.fixConfig.heartbeatSeconds());
        this.adminMessage.addTag(FIX50SP2Tags.ResetSeqNumFlag).setBoolean(true);
        this.adminMessage.addTag(FIX50SP2Tags.Username).setString(this.apiKey);
        this.adminMessage.addTag(FIX50SP2Tags.Password).setString(this.apiPassphrase);
        this.adminMessage.addTag(FIX50SP2Tags.DefaultApplVerID).setInt(this.fixConfig.applicationVersion().getApplicationVersionID());

        this.adminMessage.addTag(CoinbaseTags.DefaultSelfTradePreventionStrategy).setChar(CoinbaseEnumerations.DefaultSelfTradePreventionStrategy.CancelAggressingOrders);
        this.adminMessage.addTag(CoinbaseTags.CancelOrdersOnDisconnect).setChar(CoinbaseEnumerations.CancelOrdersOnDisconnect.CancelAllProfileOrders);
        this.adminMessage.addTag(CoinbaseTags.DropCopyFlag).setChar(CoinbaseEnumerations.DropCopyFlag.NormalOrderEntry);

    }
}
