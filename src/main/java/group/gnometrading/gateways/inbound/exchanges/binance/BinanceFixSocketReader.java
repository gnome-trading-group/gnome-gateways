package group.gnometrading.gateways.inbound.exchanges.binance;

import group.gnometrading.gateways.fix.FixConfig;
import group.gnometrading.gateways.fix.FixConstants;
import group.gnometrading.gateways.fix.FixDefaultMsgTypes;
import group.gnometrading.gateways.fix.FixMessage;
import group.gnometrading.gateways.fix.FixSession;
import group.gnometrading.gateways.fix.FixSocketMessageClient;
import group.gnometrading.gateways.fix.FixStatusListener;
import group.gnometrading.gateways.fix.FixTimestamp;
import group.gnometrading.gateways.fix.FixTimestampPrecision;
import group.gnometrading.gateways.fix.FixValue;
import group.gnometrading.gateways.fix.fix50sp2.Fix50Sp2Tags;
import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.gateways.inbound.SocketReader;
import group.gnometrading.gateways.inbound.mbp.buffer.MbpBufferBook;
import group.gnometrading.gateways.inbound.mbp.buffer.MbpBufferSchemaFactory;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.Mbp10Encoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import org.agrona.concurrent.EpochNanoClock;

public final class BinanceFixSocketReader extends SocketReader<Mbp10Schema>
        implements MbpBufferSchemaFactory, FixStatusListener {

    private static final long NANOS_PER_MICRO = 1_000L;
    private static final int MAX_LEVELS = 10;

    private final FixSocketMessageClient fixClient;
    private final FixSession fixSession;
    private final FixConfig fixConfig;
    private final FixMessage outboundMessage;
    private final MbpBufferBook book;
    private final PrivateKey privateKey;
    private final String apiKey;
    private final String symbol;
    private final ByteBuffer messageReady;
    private final ByteBuffer logonPayloadBuffer;

    private MbpBufferBook snapshotBook;
    private long lastTradePrice;
    private long lastTradeSize;
    private long lastSequenceNumber;

    public BinanceFixSocketReader(
            final Logger logger,
            final SequencedRingBuffer<Mbp10Schema> outputBuffer,
            final EpochNanoClock clock,
            final FixSocketMessageClient fixClient,
            final Listing listing,
            final FixConfig fixConfig,
            final PrivateKey privateKey,
            final String apiKey) {
        super(logger, outputBuffer, clock, null, listing);
        this.fixClient = fixClient;
        this.fixConfig = fixConfig;
        this.fixSession = new FixSession(fixConfig, fixClient, this);
        this.outboundMessage = new FixMessage(fixConfig);
        this.book = (MbpBufferBook) this.internalBook;
        this.privateKey = privateKey;
        this.apiKey = apiKey;
        this.symbol = listing.exchangeSecuritySymbol();
        this.messageReady = ByteBuffer.allocate(1);
        this.logonPayloadBuffer = ByteBuffer.allocate(256);
        this.lastTradePrice = Mbp10Encoder.priceNullValue();
        this.lastTradeSize = Mbp10Encoder.sizeNullValue();
        this.lastSequenceNumber = Mbp10Encoder.sequenceNullValue();
    }

    @Override
    protected void attachSocket() throws IOException {
        try {
            this.fixClient.connect();
        } catch (Exception e) {
            throw new IOException("Failed to connect to Binance FIX endpoint", e);
        }
        sendLogon();
        waitForMsgType(FixDefaultMsgTypes.Logon);
        sendMarketDataRequest("DEPTH", 10, false);
        sendMarketDataRequest("TRADES", 1, true);
        this.snapshotBook = readDepthSnapshot();
    }

    private void sendLogon() throws IOException {
        this.fixSession.prepareMessage(this.outboundMessage, FixDefaultMsgTypes.Logon);
        this.outboundMessage
                .getTag(Fix50Sp2Tags.SendingTime)
                .setTimestamp(System.currentTimeMillis(), FixTimestampPrecision.MILLISECONDS);

        final byte[] payload = buildLogonPayload();
        final byte[] signature = signPayload(payload);
        final byte[] encodedSig = Base64.getEncoder().encode(signature);

        this.outboundMessage.addTag(Fix50Sp2Tags.EncryptMethod).setInt(0);
        this.outboundMessage.addTag(Fix50Sp2Tags.HeartBtInt).setInt(this.fixConfig.heartbeatSeconds());
        this.outboundMessage.addTag(Fix50Sp2Tags.RawDataLength).setInt(encodedSig.length);
        this.outboundMessage.addTag(Fix50Sp2Tags.RawData).setByteBuffer(ByteBuffer.wrap(encodedSig));
        this.outboundMessage.addTag(Fix50Sp2Tags.ResetSeqNumFlag).setBoolean(true);
        this.outboundMessage.addTag(Fix50Sp2Tags.Username).setString(this.apiKey);
        this.outboundMessage.addTag(BinanceFixTags.MessageHandling).setInt(1);

        this.fixSession.send(this.outboundMessage);
    }

    private byte[] buildLogonPayload() {
        this.logonPayloadBuffer.clear();
        appendTagBytes(Fix50Sp2Tags.MsgType);
        this.logonPayloadBuffer.put(FixConstants.SOH);
        appendTagBytes(Fix50Sp2Tags.SenderCompID);
        this.logonPayloadBuffer.put(FixConstants.SOH);
        appendTagBytes(Fix50Sp2Tags.TargetCompID);
        this.logonPayloadBuffer.put(FixConstants.SOH);
        appendTagBytes(Fix50Sp2Tags.MsgSeqNum);
        this.logonPayloadBuffer.put(FixConstants.SOH);
        appendTagBytes(Fix50Sp2Tags.SendingTime);
        this.logonPayloadBuffer.flip();
        final byte[] result = new byte[this.logonPayloadBuffer.remaining()];
        this.logonPayloadBuffer.get(result);
        return result;
    }

    private void appendTagBytes(final int tag) {
        final GnomeString value = this.outboundMessage.getTag(tag).asString();
        for (int i = 0; i < value.length(); i++) {
            this.logonPayloadBuffer.put(value.byteAt(i));
        }
    }

    private byte[] signPayload(final byte[] payload) throws IOException {
        try {
            final Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(this.privateKey);
            sig.update(payload);
            return sig.sign();
        } catch (Exception e) {
            throw new IOException("Ed25519 signing failed", e);
        }
    }

    private void sendMarketDataRequest(final String reqId, final int marketDepth, final boolean tradesOnly)
            throws IOException {
        this.fixSession.prepareMessage(this.outboundMessage, 'V');
        this.outboundMessage.addTag(Fix50Sp2Tags.MDReqID).setString(reqId);
        this.outboundMessage.addTag(Fix50Sp2Tags.SubscriptionRequestType).setChar('1');
        this.outboundMessage.addTag(Fix50Sp2Tags.MarketDepth).setInt(marketDepth);
        this.outboundMessage.addTag(Fix50Sp2Tags.AggregatedBook).setBoolean(true);
        this.outboundMessage.addTag(Fix50Sp2Tags.NoRelatedSym).setInt(1);
        this.outboundMessage.addTag(Fix50Sp2Tags.Symbol).setString(this.symbol);
        if (tradesOnly) {
            this.outboundMessage.addTag(Fix50Sp2Tags.NoMDEntryTypes).setInt(1);
            this.outboundMessage.addTag(Fix50Sp2Tags.MDEntryType).setChar('2');
        } else {
            this.outboundMessage.addTag(Fix50Sp2Tags.NoMDEntryTypes).setInt(2);
            this.outboundMessage.addTag(Fix50Sp2Tags.MDEntryType).setChar('0');
            this.outboundMessage.addTag(Fix50Sp2Tags.MDEntryType).setChar('1');
        }
        this.fixSession.send(this.outboundMessage);
    }

    private void waitForMsgType(final char expectedType) throws IOException {
        final ByteBuffer buf = this.fixClient.getReadBuffer();
        while (true) {
            while (this.fixClient.readMessage(buf) != 1) {
                Thread.yield();
            }
            final FixValue msgType = this.fixClient.getMessage().getTag(Fix50Sp2Tags.MsgType);
            if (msgType != null && msgType.asChar() == expectedType) {
                return;
            }
        }
    }

    private MbpBufferBook readDepthSnapshot() throws IOException {
        final ByteBuffer buf = this.fixClient.getReadBuffer();
        while (true) {
            while (this.fixClient.readMessage(buf) != 1) {
                Thread.yield();
            }
            final FixMessage msg = this.fixClient.getMessage();
            final FixValue msgType = msg.getTag(Fix50Sp2Tags.MsgType);
            if (msgType == null || msgType.asChar() != 'W') {
                continue;
            }
            final FixValue lastBookUpdate = msg.getTag(BinanceFixTags.LastBookUpdateID);
            if (lastBookUpdate == null || lastBookUpdate.asLong() == 0) {
                continue;
            }
            return parseSnapshotBook(msg);
        }
    }

    private MbpBufferBook parseSnapshotBook(final FixMessage message) {
        final MbpBufferBook snapshotBook = createBook();
        long lastBookUpdateId = 0;
        char currentType = 0;
        long currentPrice = 0;
        long currentSize = 0;

        for (int i = 0; i < message.getTagCount(); i++) {
            final int tag = message.getTagAt(i);
            final FixValue value = message.getValueAt(i);
            if (tag == BinanceFixTags.LastBookUpdateID) {
                lastBookUpdateId = value.asLong();
            } else if (tag == Fix50Sp2Tags.MDEntryType) {
                if (currentType != 0 && currentPrice != 0) {
                    applySnapshotLevel(snapshotBook, currentType, currentPrice, currentSize);
                }
                currentType = value.asChar();
                currentPrice = 0;
                currentSize = 0;
            } else if (tag == Fix50Sp2Tags.MDEntryPx) {
                currentPrice = value.toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (tag == Fix50Sp2Tags.MDEntrySize) {
                currentSize = value.toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            }
        }
        if (currentType != 0 && currentPrice != 0) {
            applySnapshotLevel(snapshotBook, currentType, currentPrice, currentSize);
        }
        snapshotBook.setSequenceNumber(lastBookUpdateId + 1);
        return snapshotBook;
    }

    private void applySnapshotLevel(final MbpBufferBook target, final char type, final long price, final long size) {
        if (type == '0') {
            target.updateBid(price, size, 1);
        } else if (type == '1') {
            target.updateAsk(price, size, 1);
        }
    }

    @Override
    public Book<Mbp10Schema> fetchSnapshot() {
        return this.snapshotBook;
    }

    @Override
    protected ByteBuffer readSocket() throws IOException {
        final int result = this.fixClient.readMessage(this.fixClient.getReadBuffer());
        if (result < 0) {
            onSocketClose();
            return null;
        }
        if (result == 0) {
            return null;
        }
        this.messageReady.clear();
        return this.messageReady;
    }

    @Override
    protected void handleGatewayMessage(final ByteBuffer buffer) {
        buffer.position(buffer.limit());
        final FixMessage msg = this.fixClient.getMessage();
        try {
            if (!this.fixSession.handleFixMessage(msg)) {
                dispatchMarketMessage(msg);
            }
            this.fixSession.keepAlive();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void keepAlive() throws IOException {
        this.fixSession.keepAlive();
    }

    @Override
    protected void disconnectSocket() throws Exception {
        this.fixSession.prepareMessage(this.outboundMessage, FixDefaultMsgTypes.Logout);
        this.fixSession.send(this.outboundMessage);
        this.fixClient.close();
    }

    @Override
    public void handleLogout(final FixMessage message) {
        onSocketClose();
    }

    @Override
    public void handleHeartbeatTimeout() {
        onSocketClose();
    }

    private void dispatchMarketMessage(final FixMessage message) {
        final FixValue msgType = message.getTag(Fix50Sp2Tags.MsgType);
        if (msgType != null && msgType.asChar() == 'X') {
            handleIncrementalRefresh(message);
        }
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private void handleIncrementalRefresh(final FixMessage message) {
        int minDepth = Mbp10Encoder.depthNullValue();
        char currentAction = 0;
        char currentType = 0;
        long currentPrice = 0;
        long currentSize = 0;
        long currentEventTime = Mbp10Encoder.timestampEventNullValue();
        int currentAggressorSide = 0;

        final FixValue headerSendingTime = message.getTag(Fix50Sp2Tags.SendingTime);
        final long bookEventTime = headerSendingTime != null
                ? fixTimestampToEpochNanos(headerSendingTime.asTimestamp(FixTimestampPrecision.MICROSECONDS))
                : Mbp10Encoder.timestampEventNullValue();
        currentEventTime = bookEventTime;

        for (int i = 0; i < message.getTagCount(); i++) {
            final int tag = message.getTagAt(i);
            final FixValue value = message.getValueAt(i);
            if (tag == Fix50Sp2Tags.MDUpdateAction) {
                if (currentType != 0) {
                    minDepth = processEntry(
                            currentAction,
                            currentType,
                            currentPrice,
                            currentSize,
                            currentEventTime,
                            currentAggressorSide,
                            minDepth);
                }
                currentAction = value.asChar();
                currentType = 0;
                currentPrice = 0;
                currentSize = 0;
                currentEventTime = bookEventTime;
                currentAggressorSide = 0;
            } else if (tag == Fix50Sp2Tags.MDEntryType) {
                currentType = value.asChar();
            } else if (tag == Fix50Sp2Tags.MDEntryPx) {
                currentPrice = value.toFixedPointLong(Statics.PRICE_SCALING_FACTOR);
            } else if (tag == Fix50Sp2Tags.MDEntrySize) {
                currentSize = value.toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
            } else if (tag == Fix50Sp2Tags.TransactTime) {
                currentEventTime = fixTimestampToEpochNanos(value.asTimestamp(FixTimestampPrecision.MICROSECONDS));
            } else if (tag == BinanceFixTags.AggressorSide) {
                currentAggressorSide = value.asInt();
            } else if (tag == BinanceFixTags.LastBookUpdateID) {
                this.lastSequenceNumber = value.asLong();
            }
        }
        if (currentType != 0) {
            minDepth = processEntry(
                    currentAction,
                    currentType,
                    currentPrice,
                    currentSize,
                    currentEventTime,
                    currentAggressorSide,
                    minDepth);
        }

        if (minDepth != Mbp10Encoder.depthNullValue() && minDepth < MAX_LEVELS) {
            emitBookSchema(bookEventTime, minDepth);
        }
    }

    private int processEntry(
            final char action,
            final char type,
            final long price,
            final long size,
            final long eventTime,
            final int aggressorSide,
            final int minDepth) {
        if (type == '2') {
            emitTrade(price, size, eventTime, aggressorSide);
            return minDepth;
        }
        final long applySize = (action == '2') ? 0L : size;
        final int depth =
                (type == '0') ? this.book.updateBid(price, applySize, 1) : this.book.updateAsk(price, applySize, 1);
        return Math.min(minDepth, depth);
    }

    private void emitTrade(final long price, final long size, final long eventTime, final int aggressorSide) {
        this.lastTradePrice = price;
        this.lastTradeSize = size;
        this.schema.encoder.exchangeId(this.listing.exchange().exchangeId());
        this.schema.encoder.securityId(this.listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(this.recvTimestamp);
        this.schema.encoder.timestampEvent(eventTime);
        this.schema.encoder.sequence(this.lastSequenceNumber);
        this.schema.encoder.price(price);
        this.schema.encoder.size(size);
        this.schema.encoder.action(Action.Trade);
        this.schema.encoder.side(aggressorSide == 1 ? Side.Bid : Side.Ask);
        this.schema.encoder.depth(Mbp10Encoder.depthNullValue());
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);
        offer();
    }

    private void emitBookSchema(final long eventTime, final int depth) {
        this.schema.encoder.exchangeId(this.listing.exchange().exchangeId());
        this.schema.encoder.securityId(this.listing.security().securityId());
        this.schema.encoder.timestampSent(Mbp10Encoder.timestampSentNullValue());
        this.schema.encoder.timestampRecv(this.recvTimestamp);
        this.schema.encoder.timestampEvent(eventTime);
        this.schema.encoder.sequence(this.lastSequenceNumber);
        this.schema.encoder.price(this.lastTradePrice);
        this.schema.encoder.size(this.lastTradeSize);
        this.schema.encoder.action(Action.Modify);
        this.schema.encoder.side(Side.None);
        this.schema.encoder.depth((short) depth);
        this.schema.encoder.flags().clear();
        this.schema.encoder.flags().marketByPrice(true);
        this.book.writeTo(this.schema);
        offer();
    }

    private static long fixTimestampToEpochNanos(final FixTimestamp ts) {
        final long y = ts.getYear();
        final long m = ts.getMonth();
        final long d = ts.getDay();
        final long a = (14 - m) / 12;
        final long yr = y + 4800 - a;
        final long mo = m + 12 * a - 3;
        final long jdn = d + (153 * mo + 2) / 5 + 365 * yr + yr / 4 - yr / 100 + yr / 400 - 32045;
        final long days = jdn - 2440588L;
        final long seconds = days * 86400L + ts.getHour() * 3600L + ts.getMinute() * 60L + ts.getSecond();
        return seconds * 1_000_000_000L + (long) ts.getFraction() * NANOS_PER_MICRO;
    }
}
