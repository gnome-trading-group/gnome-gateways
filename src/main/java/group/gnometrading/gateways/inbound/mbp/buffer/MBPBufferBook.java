package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.schemas.MBP10Encoder;
import group.gnometrading.schemas.MBP10Schema;

/**
 * MBPBufferBook is used when an exchange sends incremental updates rather than entire book updates.
 */
public class MBPBufferBook implements Book<MBP10Schema> {

    private final MBPBufferSide asks, bids;
    private long sequenceNumber;

    public MBPBufferBook(int maxLevels) {
        this.asks = new MBPBufferSide(maxLevels, false);
        this.bids = new MBPBufferSide(maxLevels, true);
    }

    @Override
    public long getSequenceNumber() {
        return this.sequenceNumber;
    }

    @Override
    public void writeTo(MBP10Schema schema) {
        this.bids.writeTo(schema);
        this.asks.writeTo(schema);
    }

    @Override
    public void updateFrom(MBP10Schema schema) {
        this.bids.updateFrom(schema);
        this.asks.updateFrom(schema);
        this.sequenceNumber = schema.decoder.sequence();
    }

    @Override
    public void copyFrom(Book<MBP10Schema> mbp10SchemaBook) {
        assert mbp10SchemaBook instanceof MBPBufferBook;
        MBPBufferBook other = (MBPBufferBook) mbp10SchemaBook;
        this.sequenceNumber = other.sequenceNumber;
        this.bids.copyFrom(other.bids);
        this.asks.copyFrom(other.asks);
    }

    @Override
    public void reset() {
        this.sequenceNumber = MBP10Encoder.sequenceNullValue();
        this.bids.reset();
        this.asks.reset();
    }

    @Override
    public int compareTo(Book<MBP10Schema> o) {
        if (this.sequenceNumber < o.getSequenceNumber()) {
            return -1;
        } else if (this.sequenceNumber > o.getSequenceNumber()) {
            return 1;
        }
        return 0;
    }

    public int updateAsk(final long price, final long size, final long count) {
        return this.asks.update(price, size, count);
    }

    public int updateBid(final long price, final long size, final long count) {
        return this.bids.update(price, size, count);
    }
}
