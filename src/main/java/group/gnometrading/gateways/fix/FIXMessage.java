package group.gnometrading.gateways.fix;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntMap;
import group.gnometrading.pools.Pool;
import group.gnometrading.pools.PoolNode;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.utils.Resettable;

import java.nio.ByteBuffer;

public class FIXMessage implements Resettable {

    private final IntMap<PoolNode<FIXValue>> tags;
    private final Pool<FIXValue> valuePool;
    private int tagCount;
    private final int[] tagMap;
    private final FIXConfig config;
    private final ByteBuffer writeHeaderBuffer, writeBodyBuffer;
    private final int bodyLengthOffset;
    private final FIXValue bodyLength, checkSum;

    public FIXMessage(final FIXConfig config) {
        this.config = config;
        this.tags = new IntHashMap<>();
        this.tagMap = new int[config.maxTagCapacity()];
        this.valuePool = new SingleThreadedObjectPool<>(this::createFIXValue);
        this.writeBodyBuffer = ByteBuffer.allocate(config.writeBufferCapacity());
        this.writeHeaderBuffer = ByteBuffer.allocate(config.writeBufferCapacity());
        this.bodyLength = this.createFIXValue();
        this.checkSum = this.createFIXValue();

        this.writeHeaderBuffer.put(FIXConstants.BEGIN_STRING_BYTES);
        this.writeHeaderBuffer.put(config.sessionVersion().getBeginStringBytes());
        this.writeHeaderBuffer.put(FIXConstants.SOH);
        this.writeHeaderBuffer.put(FIXConstants.BODY_LENGTH_BYTES);

        this.bodyLengthOffset = this.writeHeaderBuffer.position();
    }

    private FIXValue createFIXValue() {
        return new FIXValue(this.config.valueBufferCapacity());
    }

    public boolean parseBuffer(final ByteBuffer buffer) {
        reset();
        buffer.mark();

        if (buffer.remaining() < 2) {
            return false;
        }

        if (buffer.getShort() != FIXConstants.BEGIN_STRING_SHORT) {
            buffer.reset();
            return false;
        }

        if (!consumeUntil(buffer, FIXConstants.SOH)) { // Do not care about checking FIX version
            buffer.reset();
            return false;
        }

        if (buffer.remaining() < 2) {
            buffer.reset();
            return false;
        }

        if (buffer.getShort() != FIXConstants.BODY_LENGTH_SHORT) {
            buffer.reset();
            return false;
        }

        int length = getFIXInt(buffer, FIXConstants.SOH);
        if (length == -1) {
            buffer.reset();
            return false;
        }

        if (buffer.remaining() < length + FIXConstants.CHECKSUM_LENGTH) {
            buffer.reset();
            return false;
        }

        final int originalPosition = buffer.position();
        final int originalLimit = buffer.limit();
        buffer.limit(originalPosition + length);

        if (!parseRemainingTags(buffer)) {
            // TODO: Should we throw an exception here instead since this represents and invalid FIX message?
            buffer.reset();
            buffer.limit(originalLimit);
            return false;
        }

        buffer.limit(originalLimit);
        buffer.position(originalPosition + length + FIXConstants.CHECKSUM_LENGTH);

        return true;
    }

    private boolean parseRemainingTags(final ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
           int tag = getFIXInt(buffer, FIXConstants.EQUALS);
           if (tag == -1) {
               return false;
           }

           final FIXValue value = addTag(tag);
           if (!value.parseBuffer(buffer)) {
               return false;
           }
        }
        return true;
    }

    public int writeToBuffer(final ByteBuffer output) {
        this.writeBodyBuffer.clear();
        for (int i = 0; i < this.tagCount; i++) {
            final int tag = this.tagMap[i];
            this.putFIXInt(this.writeBodyBuffer, tag);
            this.tags.get(tag).getItem().writeToBuffer(this.writeBodyBuffer);
        }

        this.writeHeaderBuffer.position(this.bodyLengthOffset);
        bodyLength.setInt(this.writeBodyBuffer.position());
        bodyLength.writeToBuffer(this.writeHeaderBuffer);

        this.checkSum.setCheckSum(
                FIXConstants.sum(this.writeHeaderBuffer, 0, this.writeHeaderBuffer.position()) +
                FIXConstants.sum(this.writeBodyBuffer, 0, this.writeBodyBuffer.position())
        );
        this.writeBodyBuffer.put(FIXConstants.CHECK_SUM_BYTES);
        this.checkSum.writeToBuffer(this.writeBodyBuffer);

        this.writeHeaderBuffer.flip();
        this.writeBodyBuffer.flip();

        int totalBytes = this.writeHeaderBuffer.remaining() + this.writeBodyBuffer.remaining();

        output.put(this.writeHeaderBuffer).put(this.writeBodyBuffer);
        return totalBytes;
    }

    public FIXValue addTag(final int tag) {
        if (this.tagCount == config.maxTagCapacity()) {
            // TODO: Should we support expanding?
            throw new RuntimeException("Too many tags created");
        }

        final var node = this.valuePool.acquire();
        this.tagMap[this.tagCount++] = tag;
        this.tags.put(tag, node);
        return node.getItem();
    }

    public FIXValue getTag(final int tag) {
        final var node = this.tags.get(tag);
        if (node != null) {
            return node.getItem();
        }
        return null;
    }

    public int getMsgSeqNum() {
        final var value = this.getTag(FIXDefaultTags.MsgSeqNum);
        return value == null ? 0 : value.asInt();
    }

    @Override
    public void reset() {
        for (int i = 0; i < tagCount; i++) {
            this.valuePool.release(this.tags.get(this.tagMap[i]));
        }

        this.tags.clear();
        this.tagCount = 0;
    }

    private int getFIXInt(final ByteBuffer buffer, final byte end) {
        int result = 0;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == end)
                return result;
            result = 10 * result + b - '0';
        }
        return -1;
    }

    private void putFIXInt(final ByteBuffer buffer, int tag) {
        // Only process tags < 9999
        final byte b1 = (byte)('0' + tag % 10);
        tag /= 10;

        if (tag != 0) {
            final byte b2 = (byte)('0' + tag % 10);
            tag /= 10;
            if (tag != 0) {
                final byte b3 = (byte)('0' + tag % 10);
                tag /= 10;
                if (tag != 0) {
                    final byte b4 = (byte)('0' + tag % 10);
                    tag /= 10;
                    if (tag != 0) {
                        final byte b5 = (byte)('0' + tag % 10);
                        buffer.put(b5);
                    }
                    buffer.put(b4);
                }
                buffer.put(b3);
            }
            buffer.put(b2);
        }

        buffer.put(b1);
        buffer.put(FIXConstants.EQUALS);
    }

    private boolean consumeUntil(final ByteBuffer buffer, final byte end) {
        while (buffer.hasRemaining()) {
            if (buffer.get() == end) {
                return true;
            }
        }
        return false;
    }
}
