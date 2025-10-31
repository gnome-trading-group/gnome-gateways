package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.schemas.MBP10Encoder;
import group.gnometrading.schemas.MBP10Schema;
import org.agrona.concurrent.UnsafeBuffer;

class MBPBufferSide {

    private static final int ENTRY_SIZE = 24; // 8 bytes price + 8 bytes size + 8 bytes count

    private final UnsafeBuffer buf;
    private final int maxLevels;
    private final boolean isBid;

    private int depth = 0;

    public MBPBufferSide(int maxLevels, boolean isBid) {
        if (maxLevels < 1) {
            throw new IllegalArgumentException("Invalid max levels: " + maxLevels);
        }
        this.maxLevels = maxLevels;
        this.isBid = isBid;
        this.buf = new UnsafeBuffer(new byte[maxLevels * ENTRY_SIZE]);
    }

    /**
     * Direct update from the exchange. If `size` == 0, remove the level.
     *
     * @param price the price to update
     * @param size the size to update
     * @return the depth of insertion
     */
    public int update(final long price, final long size, final long count) {
        int idxOrIns = binarySearch(price);
        if (idxOrIns >= 0) {
            if (size == 0L) {
                removeAt(idxOrIns);
            } else {
                putSizeAt(idxOrIns, size);
                putCountAt(idxOrIns, count);
            }
            return idxOrIns;
        } else {
            final int ins = ~idxOrIns;
            if (size == 0L) return -1; // removing a non-present level -> ignore
            insertAt(ins, price, size, count);
            return ins;
        }
    }

    /**
     * Binary search over the sorted entries in the buffer.
     * Returns the index if found, otherwise, (-(insertionPoint) - 1) like Arrays#binarySearch.
     *
     * @param price the price to search for
     * @return the index if found, otherwise, (-(insertionPoint) - 1)
     */
    private int binarySearch(final long price) {
        int low = 0;
        int high = depth - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final long midPrice = getPrice(mid);
            if (midPrice == price) return mid;
            if (isBid) {
                // bids sorted DESC: index 0 = highest price
                if (price > midPrice) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            } else {
                // asks sorted ASC: index 0 = lowest price
                if (price < midPrice) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }
        return ~low;
    }

    private void insertAt(final int pos, final long price, final long size, final long count) {
        if (pos >= maxLevels) {
            return;
        }

        if (depth < maxLevels) {
            depth++;
        }

        final int last = depth - 1;
        for (int i = last; i > pos; i--) {
            copyEntry(i - 1, i);
        }
        putPriceAt(pos, price);
        putSizeAt(pos, size);
        putCountAt(pos, count);
    }

    private void removeAt(final int idx) {
        for (int i = idx; i < depth - 1; i++) {
            copyEntry(i + 1, i);
        }
        depth--;
    }

    private void copyEntry(final int srcIndex, final int dstIndex) {
        final int srcOff = srcIndex * ENTRY_SIZE;
        final int dstOff = dstIndex * ENTRY_SIZE;
        final long price = buf.getLong(srcOff);
        final long size = buf.getLong(srcOff + 8);
        final long count = buf.getLong(srcOff + 16);
        buf.putLong(dstOff, price);
        buf.putLong(dstOff + 8, size);
        buf.putLong(dstOff + 16, count);
    }

    private long getPrice(final int idx) {
        return buf.getLong(idx * ENTRY_SIZE);
    }

    private long getSize(final int idx) {
        return buf.getLong(idx * ENTRY_SIZE + 8);
    }

    private long getCount(final int idx) {
        return buf.getLong(idx * ENTRY_SIZE + 16);
    }

    private void putPriceAt(final int idx, final long price) {
        buf.putLong(idx * ENTRY_SIZE, price);
    }

    private void putSizeAt(final int idx, final long size) {
        buf.putLong(idx * ENTRY_SIZE + 8, size);
    }

    private void putCountAt(final int idx, final long count) {
        buf.putLong(idx * ENTRY_SIZE + 16, count);
    }

    public void updateFrom(MBP10Schema schema) {
        if (isBid) {
            update(schema.decoder.bidPrice0(), schema.decoder.bidSize0(), schema.decoder.bidCount0());
            update(schema.decoder.bidPrice1(), schema.decoder.bidSize1(), schema.decoder.bidCount1());
            update(schema.decoder.bidPrice2(), schema.decoder.bidSize2(), schema.decoder.bidCount2());
            update(schema.decoder.bidPrice3(), schema.decoder.bidSize3(), schema.decoder.bidCount3());
            update(schema.decoder.bidPrice4(), schema.decoder.bidSize4(), schema.decoder.bidCount4());
            update(schema.decoder.bidPrice5(), schema.decoder.bidSize5(), schema.decoder.bidCount5());
            update(schema.decoder.bidPrice6(), schema.decoder.bidSize6(), schema.decoder.bidCount6());
            update(schema.decoder.bidPrice7(), schema.decoder.bidSize7(), schema.decoder.bidCount7());
            update(schema.decoder.bidPrice8(), schema.decoder.bidSize8(), schema.decoder.bidCount8());
            update(schema.decoder.bidPrice9(), schema.decoder.bidSize9(), schema.decoder.bidCount9());
        } else {
            update(schema.decoder.askPrice0(), schema.decoder.askSize0(), schema.decoder.askCount0());
            update(schema.decoder.askPrice1(), schema.decoder.askSize1(), schema.decoder.askCount1());
            update(schema.decoder.askPrice2(), schema.decoder.askSize2(), schema.decoder.askCount2());
            update(schema.decoder.askPrice3(), schema.decoder.askSize3(), schema.decoder.askCount3());
            update(schema.decoder.askPrice4(), schema.decoder.askSize4(), schema.decoder.askCount4());
            update(schema.decoder.askPrice5(), schema.decoder.askSize5(), schema.decoder.askCount5());
            update(schema.decoder.askPrice6(), schema.decoder.askSize6(), schema.decoder.askCount6());
            update(schema.decoder.askPrice7(), schema.decoder.askSize7(), schema.decoder.askCount7());
            update(schema.decoder.askPrice8(), schema.decoder.askSize8(), schema.decoder.askCount8());
            update(schema.decoder.askPrice9(), schema.decoder.askSize9(), schema.decoder.askCount9());
        }
    }

    public void writeTo(MBP10Schema schema) {
        if (this.depth > 0) {
            if (isBid) {
                schema.encoder.bidPrice0(getPrice(0));
                schema.encoder.bidSize0(getSize(0));
                schema.encoder.bidCount0(getCount(0));
            } else {
                schema.encoder.askPrice0(getPrice(0));
                schema.encoder.askSize0(getSize(0));
                schema.encoder.askCount0(getCount(0));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice0(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize0(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount0(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice0(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize0(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount0(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 1) {
            if (isBid) {
                schema.encoder.bidPrice1(getPrice(1));
                schema.encoder.bidSize1(getSize(1));
                schema.encoder.bidCount1(getCount(1));
            } else {
                schema.encoder.askPrice1(getPrice(1));
                schema.encoder.askSize1(getSize(1));
                schema.encoder.askCount1(getCount(1));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice1(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize1(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount1(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice1(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize1(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount1(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 2) {
            if (isBid) {
                schema.encoder.bidPrice2(getPrice(2));
                schema.encoder.bidSize2(getSize(2));
                schema.encoder.bidCount2(getCount(2));
            } else {
                schema.encoder.askPrice2(getPrice(2));
                schema.encoder.askSize2(getSize(2));
                schema.encoder.askCount2(getCount(2));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice2(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize2(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount2(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice2(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize2(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount2(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 3) {
            if (isBid) {
                schema.encoder.bidPrice3(getPrice(3));
                schema.encoder.bidSize3(getSize(3));
                schema.encoder.bidCount3(getCount(3));
            } else {
                schema.encoder.askPrice3(getPrice(3));
                schema.encoder.askSize3(getSize(3));
                schema.encoder.askCount3(getCount(3));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice3(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize3(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount3(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice3(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize3(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount3(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 4) {
            if (isBid) {
                schema.encoder.bidPrice4(getPrice(4));
                schema.encoder.bidSize4(getSize(4));
                schema.encoder.bidCount4(getCount(4));
            } else {
                schema.encoder.askPrice4(getPrice(4));
                schema.encoder.askSize4(getSize(4));
                schema.encoder.askCount4(getCount(4));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice4(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize4(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount4(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice4(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize4(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount4(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 5) {
            if (isBid) {
                schema.encoder.bidPrice5(getPrice(5));
                schema.encoder.bidSize5(getSize(5));
                schema.encoder.bidCount5(getCount(5));
            } else {
                schema.encoder.askPrice5(getPrice(5));
                schema.encoder.askSize5(getSize(5));
                schema.encoder.askCount5(getCount(5));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice5(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize5(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount5(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice5(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize5(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount5(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 6) {
            if (isBid) {
                schema.encoder.bidPrice6(getPrice(6));
                schema.encoder.bidSize6(getSize(6));
                schema.encoder.bidCount6(getCount(6));
            } else {
                schema.encoder.askPrice6(getPrice(6));
                schema.encoder.askSize6(getSize(6));
                schema.encoder.askCount6(getCount(6));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice6(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize6(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount6(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice6(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize6(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount6(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 7) {
            if (isBid) {
                schema.encoder.bidPrice7(getPrice(7));
                schema.encoder.bidSize7(getSize(7));
                schema.encoder.bidCount7(getCount(7));
            } else {
                schema.encoder.askPrice7(getPrice(7));
                schema.encoder.askSize7(getSize(7));
                schema.encoder.askCount7(getCount(7));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice7(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize7(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount7(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice7(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize7(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount7(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 8) {
            if (isBid) {
                schema.encoder.bidPrice8(getPrice(8));
                schema.encoder.bidSize8(getSize(8));
                schema.encoder.bidCount8(getCount(8));
            } else {
                schema.encoder.askPrice8(getPrice(8));
                schema.encoder.askSize8(getSize(8));
                schema.encoder.askCount8(getCount(8));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice8(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize8(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount8(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice8(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize8(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount8(MBP10Encoder.askCount0NullValue());
            }
        }
        if (this.depth > 9) {
            if (isBid) {
                schema.encoder.bidPrice9(getPrice(9));
                schema.encoder.bidSize9(getSize(9));
                schema.encoder.bidCount9(getCount(9));
            } else {
                schema.encoder.askPrice9(getPrice(9));
                schema.encoder.askSize9(getSize(9));
                schema.encoder.askCount9(getCount(9));
            }
        } else {
            if (isBid) {
                schema.encoder.bidPrice9(MBP10Encoder.bidPrice0NullValue());
                schema.encoder.bidSize9(MBP10Encoder.bidSize0NullValue());
                schema.encoder.bidCount9(MBP10Encoder.bidCount0NullValue());
            } else {
                schema.encoder.askPrice9(MBP10Encoder.askPrice0NullValue());
                schema.encoder.askSize9(MBP10Encoder.askSize0NullValue());
                schema.encoder.askCount9(MBP10Encoder.askCount0NullValue());
            }
        }
    }

    public void copyFrom(MBPBufferSide other) {
        this.depth = other.depth;
        this.buf.putBytes(0, other.buf, 0, other.depth * ENTRY_SIZE);
    }

    public void reset() {
        this.depth = 0;
    }
}
