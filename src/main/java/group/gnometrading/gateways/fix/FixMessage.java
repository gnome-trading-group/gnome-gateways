package group.gnometrading.gateways.fix;

import group.gnometrading.collections.IntHashMap;
import group.gnometrading.collections.IntMap;
import group.gnometrading.pools.Pool;
import group.gnometrading.pools.PoolNode;
import group.gnometrading.pools.SingleThreadedObjectPool;
import group.gnometrading.utils.Resettable;
import java.nio.ByteBuffer;

public final class FixMessage implements Resettable {

    private final IntMap<PoolNode<FixValue>> tags;
    private final Pool<FixValue> valuePool;
    private int tagCount;
    private final int[] tagMap;
    private final FixConfig config;
    private final ByteBuffer writeHeaderBuffer;
    private final ByteBuffer writeBodyBuffer;
    private final int bodyLengthOffset;
    private final FixValue bodyLength;
    private final FixValue checkSum;

    public FixMessage(final FixConfig config) {
        this.config = config;
        this.tags = new IntHashMap<>();
        this.tagMap = new int[config.maxTagCapacity()];
        this.valuePool = new SingleThreadedObjectPool<>(this::createFixValue);
        this.writeBodyBuffer = ByteBuffer.allocate(config.writeBufferCapacity());
        this.writeHeaderBuffer = ByteBuffer.allocate(config.writeBufferCapacity());
        this.bodyLength = this.createFixValue();
        this.checkSum = this.createFixValue();

        this.writeHeaderBuffer.put(FixConstants.BEGIN_STRING_BYTES);
        this.writeHeaderBuffer.put(config.sessionVersion().getBeginStringBytes());
        this.writeHeaderBuffer.put(FixConstants.SOH);
        this.writeHeaderBuffer.put(FixConstants.BODY_LENGTH_BYTES);

        this.bodyLengthOffset = this.writeHeaderBuffer.position();
    }

    private FixValue createFixValue() {
        return new FixValue(this.config.valueBufferCapacity());
    }

    public boolean parseBuffer(final ByteBuffer buffer) {
        reset();
        buffer.mark();

        if (buffer.remaining() < 2) {
            return false;
        }

        if (buffer.getShort() != FixConstants.BEGIN_STRING_SHORT) {
            buffer.reset();
            return false;
        }

        if (!consumeUntil(buffer, FixConstants.SOH)) { // Do not care about checking FIX version
            buffer.reset();
            return false;
        }

        if (buffer.remaining() < 2) {
            buffer.reset();
            return false;
        }

        if (buffer.getShort() != FixConstants.BODY_LENGTH_SHORT) {
            buffer.reset();
            return false;
        }

        int length = getFixInt(buffer, FixConstants.SOH);
        if (length == -1) {
            buffer.reset();
            return false;
        }

        if (buffer.remaining() < length + FixConstants.CHECKSUM_LENGTH) {
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
        buffer.position(originalPosition + length + FixConstants.CHECKSUM_LENGTH);

        return true;
    }

    private boolean parseRemainingTags(final ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            int tag = getFixInt(buffer, FixConstants.EQUALS);
            if (tag == -1) {
                return false;
            }

            final FixValue value = addTag(tag);
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
            this.putFixInt(this.writeBodyBuffer, tag);
            this.tags.get(tag).getItem().writeToBuffer(this.writeBodyBuffer);
        }

        this.writeHeaderBuffer.position(this.bodyLengthOffset);
        bodyLength.setInt(this.writeBodyBuffer.position());
        bodyLength.writeToBuffer(this.writeHeaderBuffer);

        this.checkSum.setCheckSum(FixConstants.sum(this.writeHeaderBuffer, 0, this.writeHeaderBuffer.position())
                + FixConstants.sum(this.writeBodyBuffer, 0, this.writeBodyBuffer.position()));
        this.writeBodyBuffer.put(FixConstants.CHECK_SUM_BYTES);
        this.checkSum.writeToBuffer(this.writeBodyBuffer);

        this.writeHeaderBuffer.flip();
        this.writeBodyBuffer.flip();

        int totalBytes = this.writeHeaderBuffer.remaining() + this.writeBodyBuffer.remaining();

        output.put(this.writeHeaderBuffer).put(this.writeBodyBuffer);
        return totalBytes;
    }

    public FixValue addTag(final int tag) {
        if (this.tagCount == config.maxTagCapacity()) {
            // TODO: Should we support expanding?
            throw new RuntimeException("Too many tags created");
        }

        final var node = this.valuePool.acquire();
        this.tagMap[this.tagCount++] = tag;
        this.tags.put(tag, node);
        return node.getItem();
    }

    public FixValue getTag(final int tag) {
        final var node = this.tags.get(tag);
        if (node != null) {
            return node.getItem();
        }
        return null;
    }

    public int getMsgSeqNum() {
        final var value = this.getTag(FixDefaultTags.MsgSeqNum);
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

    private int getFixInt(final ByteBuffer buffer, final byte end) {
        int result = 0;
        while (buffer.hasRemaining()) {
            byte byteVal = buffer.get();
            if (byteVal == end) {
                return result;
            }
            result = 10 * result + byteVal - '0';
        }
        return -1;
    }

    private void putFixInt(final ByteBuffer buffer, int tag) {
        // Only process tags < 9999
        int remaining = tag;
        final byte b1 = (byte) ('0' + remaining % 10);
        remaining /= 10;

        if (remaining != 0) {
            final byte b2 = (byte) ('0' + remaining % 10);
            remaining /= 10;
            if (remaining != 0) {
                final byte b3 = (byte) ('0' + remaining % 10);
                remaining /= 10;
                if (remaining != 0) {
                    final byte b4 = (byte) ('0' + remaining % 10);
                    remaining /= 10;
                    if (remaining != 0) {
                        final byte b5 = (byte) ('0' + remaining % 10);
                        buffer.put(b5);
                    }
                    buffer.put(b4);
                }
                buffer.put(b3);
            }
            buffer.put(b2);
        }

        buffer.put(b1);
        buffer.put(FixConstants.EQUALS);
    }

    private boolean consumeUntil(final ByteBuffer buffer, final byte end) {
        while (buffer.hasRemaining()) {
            if (buffer.get() == end) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        ByteBuffer out = ByteBuffer.allocate(1 << 12);
        int length = this.writeToBuffer(out);
        return new String(out.array(), 0, length).replace((char) FixConstants.SOH, '|');
    }
}
