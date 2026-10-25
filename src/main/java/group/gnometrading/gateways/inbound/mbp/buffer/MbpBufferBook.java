package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Mbp10Encoder;

/**
 * MbpBufferBook is used when an exchange sends incremental updates rather than entire book updates.
 */
public final class MbpBufferBook implements Book<Mbp10Schema> {

    private final MbpBufferSide asks;
    private final MbpBufferSide bids;
    private long sequenceNumber;

    public MbpBufferBook(int maxLevels) {
        this.asks = new MbpBufferSide(maxLevels, false);
        this.bids = new MbpBufferSide(maxLevels, true);
    }

    @Override
    public long getSequenceNumber() {
        return this.sequenceNumber;
    }

    @Override
    public void writeTo(Mbp10Schema schema) {
        this.bids.writeTo(schema);
        this.asks.writeTo(schema);
    }

    @Override
    public void updateFrom(Mbp10Schema schema) {
        this.bids.updateFrom(schema);
        this.asks.updateFrom(schema);
        this.sequenceNumber = schema.decoder.sequence();
    }

    @Override
    public void copyFrom(Book<Mbp10Schema> mbp10SchemaBook) {
        assert mbp10SchemaBook instanceof MbpBufferBook;
        MbpBufferBook other = (MbpBufferBook) mbp10SchemaBook;
        this.sequenceNumber = other.sequenceNumber;
        this.bids.copyFrom(other.bids);
        this.asks.copyFrom(other.asks);
    }

    @Override
    public void reset() {
        this.sequenceNumber = Mbp10Encoder.sequenceNullValue();
        this.bids.reset();
        this.asks.reset();
    }

    @Override
    public int compareTo(Book<Mbp10Schema> other) {
        if (this.sequenceNumber < other.getSequenceNumber()) {
            return -1;
        } else if (this.sequenceNumber > other.getSequenceNumber()) {
            return 1;
        }
        return 0;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    /** The highest bid's price, or {@code empty} if there are no bids. */
    public long bestBid(final long empty) {
        return this.bids.bestPrice(empty);
    }

    /** The lowest ask's price, or {@code empty} if there are no asks. */
    public long bestAsk(final long empty) {
        return this.asks.bestPrice(empty);
    }

    public int updateAsk(final long price, final long size, final long count) {
        return this.asks.update(price, size, count);
    }

    public int updateBid(final long price, final long size, final long count) {
        return this.bids.update(price, size, count);
    }
}
