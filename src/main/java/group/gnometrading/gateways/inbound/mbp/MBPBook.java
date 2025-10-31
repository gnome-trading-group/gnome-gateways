package group.gnometrading.gateways.inbound.mbp;

import group.gnometrading.gateways.inbound.Book;
import group.gnometrading.schemas.MBP10Encoder;
import group.gnometrading.schemas.MBP10Schema;
import group.gnometrading.utils.Copyable;

/**
 * MBPBook is used when an exchange sends entire book updates rather than incremental updates.
 */
public class MBPBook implements Book<MBP10Schema> {

    public final PriceLevel[] asks, bids;
    public long sequenceNumber;

    private final int depth;

    public MBPBook(int depth) {
        this.asks = new PriceLevel[depth];
        this.bids = new PriceLevel[depth];
        this.sequenceNumber = MBP10Encoder.sequenceNullValue();
        for (int i = 0; i < depth; i++) {
            this.asks[i] = new PriceLevel();
            this.bids[i] = new PriceLevel();
        }
        this.depth = depth;
    }

    @Override
    public long getSequenceNumber() {
        return this.sequenceNumber;
    }

    @Override
    public void writeTo(final MBP10Schema schema) {
        schema.encoder.bidPrice0(this.bids[0].price);
        schema.encoder.bidSize0(this.bids[0].size);
        schema.encoder.bidCount0(this.bids[0].count);

        schema.encoder.bidPrice1(this.bids[1].price);
        schema.encoder.bidSize1(this.bids[1].size);
        schema.encoder.bidCount1(this.bids[1].count);

        schema.encoder.bidPrice2(this.bids[2].price);
        schema.encoder.bidSize2(this.bids[2].size);
        schema.encoder.bidCount2(this.bids[2].count);

        schema.encoder.bidPrice3(this.bids[3].price);
        schema.encoder.bidSize3(this.bids[3].size);
        schema.encoder.bidCount3(this.bids[3].count);

        schema.encoder.bidPrice4(this.bids[4].price);
        schema.encoder.bidSize4(this.bids[4].size);
        schema.encoder.bidCount4(this.bids[4].count);

        schema.encoder.bidPrice5(this.bids[5].price);
        schema.encoder.bidSize5(this.bids[5].size);
        schema.encoder.bidCount5(this.bids[5].count);

        schema.encoder.bidPrice6(this.bids[6].price);
        schema.encoder.bidSize6(this.bids[6].size);
        schema.encoder.bidCount6(this.bids[6].count);

        schema.encoder.bidPrice7(this.bids[7].price);
        schema.encoder.bidSize7(this.bids[7].size);
        schema.encoder.bidCount7(this.bids[7].count);

        schema.encoder.bidPrice8(this.bids[8].price);
        schema.encoder.bidSize8(this.bids[8].size);
        schema.encoder.bidCount8(this.bids[8].count);

        schema.encoder.bidPrice9(this.bids[9].price);
        schema.encoder.bidSize9(this.bids[9].size);
        schema.encoder.bidCount9(this.bids[9].count);


        schema.encoder.askPrice0(this.asks[0].price);
        schema.encoder.askSize0(this.asks[0].size);
        schema.encoder.askCount0(this.asks[0].count);


        schema.encoder.askPrice1(this.asks[1].price);
        schema.encoder.askSize1(this.asks[1].size);
        schema.encoder.askCount1(this.asks[1].count);

        schema.encoder.askPrice2(this.asks[2].price);
        schema.encoder.askSize2(this.asks[2].size);
        schema.encoder.askCount2(this.asks[2].count);

        schema.encoder.askPrice3(this.asks[3].price);
        schema.encoder.askSize3(this.asks[3].size);
        schema.encoder.askCount3(this.asks[3].count);

        schema.encoder.askPrice4(this.asks[4].price);
        schema.encoder.askSize4(this.asks[4].size);
        schema.encoder.askCount4(this.asks[4].count);

        schema.encoder.askPrice5(this.asks[5].price);
        schema.encoder.askSize5(this.asks[5].size);
        schema.encoder.askCount5(this.asks[5].count);

        schema.encoder.askPrice6(this.asks[6].price);
        schema.encoder.askSize6(this.asks[6].size);
        schema.encoder.askCount6(this.asks[6].count);

        schema.encoder.askPrice7(this.asks[7].price);
        schema.encoder.askSize7(this.asks[7].size);
        schema.encoder.askCount7(this.asks[7].count);

        schema.encoder.askPrice8(this.asks[8].price);
        schema.encoder.askSize8(this.asks[8].size);
        schema.encoder.askCount8(this.asks[8].count);

        schema.encoder.askPrice9(this.asks[9].price);
        schema.encoder.askSize9(this.asks[9].size);
        schema.encoder.askCount9(this.asks[9].count);
    }

    @Override
    public void updateFrom(final MBP10Schema schema) {
        this.bids[0].update(schema.decoder.bidPrice0(), schema.decoder.bidSize0(), schema.decoder.bidCount0());
        this.bids[1].update(schema.decoder.bidPrice1(), schema.decoder.bidSize1(), schema.decoder.bidCount1());
        this.bids[2].update(schema.decoder.bidPrice2(), schema.decoder.bidSize2(), schema.decoder.bidCount2());
        this.bids[3].update(schema.decoder.bidPrice3(), schema.decoder.bidSize3(), schema.decoder.bidCount3());
        this.bids[4].update(schema.decoder.bidPrice4(), schema.decoder.bidSize4(), schema.decoder.bidCount4());
        this.bids[5].update(schema.decoder.bidPrice5(), schema.decoder.bidSize5(), schema.decoder.bidCount5());
        this.bids[6].update(schema.decoder.bidPrice6(), schema.decoder.bidSize6(), schema.decoder.bidCount6());
        this.bids[7].update(schema.decoder.bidPrice7(), schema.decoder.bidSize7(), schema.decoder.bidCount7());
        this.bids[8].update(schema.decoder.bidPrice8(), schema.decoder.bidSize8(), schema.decoder.bidCount8());
        this.bids[9].update(schema.decoder.bidPrice9(), schema.decoder.bidSize9(), schema.decoder.bidCount9());
        this.asks[0].update(schema.decoder.askPrice0(), schema.decoder.askSize0(), schema.decoder.askCount0());
        this.asks[1].update(schema.decoder.askPrice1(), schema.decoder.askSize1(), schema.decoder.askCount1());
        this.asks[2].update(schema.decoder.askPrice2(), schema.decoder.askSize2(), schema.decoder.askCount2());
        this.asks[3].update(schema.decoder.askPrice3(), schema.decoder.askSize3(), schema.decoder.askCount3());
        this.asks[4].update(schema.decoder.askPrice4(), schema.decoder.askSize4(), schema.decoder.askCount4());
        this.asks[5].update(schema.decoder.askPrice5(), schema.decoder.askSize5(), schema.decoder.askCount5());
        this.asks[6].update(schema.decoder.askPrice6(), schema.decoder.askSize6(), schema.decoder.askCount6());
        this.asks[7].update(schema.decoder.askPrice7(), schema.decoder.askSize7(), schema.decoder.askCount7());
        this.asks[8].update(schema.decoder.askPrice8(), schema.decoder.askSize8(), schema.decoder.askCount8());
        this.asks[9].update(schema.decoder.askPrice9(), schema.decoder.askSize9(), schema.decoder.askCount9());
        this.sequenceNumber = schema.decoder.sequence();
    }

    public void reset() {
        this.sequenceNumber = MBP10Encoder.sequenceNullValue();
        for (int i = 0; i < this.depth; i++) {
            this.asks[i].reset();
            this.bids[i].reset();
        }
    }

    @Override
    public void copyFrom(Book<MBP10Schema> book) {
        assert book instanceof MBPBook;
        MBPBook other = (MBPBook) book;
        this.sequenceNumber = other.sequenceNumber;
        for (int i = 0; i < this.depth; i++) {
            this.asks[i].copyFrom(other.asks[i]);
            this.bids[i].copyFrom(other.bids[i]);
        }
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MBPBook other = (MBPBook) obj;
        if (this.sequenceNumber == other.sequenceNumber) {
            for (int i = 0; i < this.depth; i++) {
                if (!this.asks[i].equals(other.asks[i]) || !this.bids[i].equals(other.bids[i])) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static class PriceLevel implements Copyable<PriceLevel> {
        public long price = MBP10Encoder.askPrice0NullValue();
        public long size = MBP10Encoder.askSize0NullValue();
        public long count = MBP10Encoder.askCount0NullValue();

        public void reset() {
            price = MBP10Encoder.askPrice0NullValue();
            size = MBP10Encoder.askSize0NullValue();
            count = MBP10Encoder.askCount0NullValue();
        }

        @Override
        public void copyFrom(PriceLevel other) {
            this.price = other.price;
            this.size = other.size;
            this.count = other.count;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            PriceLevel other = (PriceLevel) obj;
            return this.price == other.price && this.size == other.size && this.count == other.count;
        }

        public boolean update(final long price, final long size, final long count) {
            boolean updated = false;
            if (this.price != price) {
                this.price = price;
                updated = true;
            }
            if (this.size != size) {
                this.size = size;
                updated = true;
            }
            if (this.count != count) {
                this.count = count;
                updated = true;
            }
            return updated;
        }
    }

}
