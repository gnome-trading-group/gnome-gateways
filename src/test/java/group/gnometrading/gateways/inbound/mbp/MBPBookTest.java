package group.gnometrading.gateways.inbound.mbp;

import group.gnometrading.schemas.MBP10Encoder;
import group.gnometrading.schemas.MBP10Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for MBPBook covering edge cases and core functionality.
 */
class MBPBookTest {

    private MBPBook book;
    private MBP10Schema schema;

    @BeforeEach
    void setUp() {
        book = new MBPBook(10);
        schema = new MBP10Schema();
    }

    // ========== Constructor Tests ==========

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void testConstructorWithValidDepth(int depth) {
        MBPBook testBook = new MBPBook(depth);
        assertNotNull(testBook);
        assertNotNull(testBook.asks);
        assertNotNull(testBook.bids);
        assertEquals(depth, testBook.asks.length);
        assertEquals(depth, testBook.bids.length);
        assertEquals(MBP10Encoder.sequenceNullValue(), testBook.sequenceNumber);
    }

    @Test
    void testConstructorInitializesAllPriceLevels() {
        for (int i = 0; i < 10; i++) {
            assertNotNull(book.asks[i], "Ask level " + i + " should not be null");
            assertNotNull(book.bids[i], "Bid level " + i + " should not be null");
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.asks[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.asks[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.asks[i].count);
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.bids[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.bids[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.bids[i].count);
        }
    }

    // ========== Sequence Number Tests ==========

    @Test
    void testGetSequenceNumber() {
        assertEquals(MBP10Encoder.sequenceNullValue(), book.getSequenceNumber());

        book.sequenceNumber = 12345L;
        assertEquals(12345L, book.getSequenceNumber());
    }

    @Test
    void testSequenceNumberBoundaries() {
        book.sequenceNumber = Long.MIN_VALUE;
        assertEquals(Long.MIN_VALUE, book.getSequenceNumber());

        book.sequenceNumber = Long.MAX_VALUE;
        assertEquals(Long.MAX_VALUE, book.getSequenceNumber());

        book.sequenceNumber = 0L;
        assertEquals(0L, book.getSequenceNumber());
    }

    // ========== WriteTo Tests ==========

    @Test
    void testWriteToWithEmptyBook() {
        book.writeTo(schema);

        // Verify all bids are written with null values
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), getBidPrice(schema, i));
            assertEquals(MBP10Encoder.askSize0NullValue(), getBidSize(schema, i));
            assertEquals(MBP10Encoder.askCount0NullValue(), getBidCount(schema, i));
        }

        // Verify all asks are written with null values
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), getAskPrice(schema, i));
            assertEquals(MBP10Encoder.askSize0NullValue(), getAskSize(schema, i));
            assertEquals(MBP10Encoder.askCount0NullValue(), getAskCount(schema, i));
        }
    }

    @Test
    void testWriteToWithPopulatedBook() {
        // Populate book with test data
        for (int i = 0; i < 10; i++) {
            book.bids[i].price = 10000L - i * 100L;
            book.bids[i].size = 1000L + i * 10L;
            book.bids[i].count = 5L + i;

            book.asks[i].price = 10100L + i * 100L;
            book.asks[i].size = 2000L + i * 20L;
            book.asks[i].count = 10L + i;
        }

        book.writeTo(schema);

        // Verify all bids are written correctly
        for (int i = 0; i < 10; i++) {
            assertEquals(10000L - i * 100L, getBidPrice(schema, i));
            assertEquals(1000L + i * 10L, getBidSize(schema, i));
            assertEquals(5L + i, getBidCount(schema, i));
        }

        // Verify all asks are written correctly
        for (int i = 0; i < 10; i++) {
            assertEquals(10100L + i * 100L, getAskPrice(schema, i));
            assertEquals(2000L + i * 20L, getAskSize(schema, i));
            assertEquals(10L + i, getAskCount(schema, i));
        }
    }

    // ========== UpdateFrom Tests ==========

    @Test
    void testUpdateFromWithEmptySchema() {
        // Set schema to null values
        setAllSchemaValues(schema, MBP10Encoder.askPrice0NullValue(),
                          MBP10Encoder.askSize0NullValue(),
                          MBP10Encoder.askCount0NullValue(),
                          MBP10Encoder.sequenceNullValue());

        book.updateFrom(schema);

        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.bids[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.bids[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.bids[i].count);
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.asks[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.asks[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.asks[i].count);
        }
        assertEquals(MBP10Encoder.sequenceNullValue(), book.sequenceNumber);
    }

    @Test
    void testUpdateFromWithPopulatedSchema() {
        // Populate schema with test data
        populateSchema(schema);

        book.updateFrom(schema);

        // Verify all bids are updated correctly
        for (int i = 0; i < 10; i++) {
            assertEquals(5000L - i * 50L, book.bids[i].price);
            assertEquals(500L + i * 5L, book.bids[i].size);
            assertEquals(3L + i, book.bids[i].count);
        }

        // Verify all asks are updated correctly
        for (int i = 0; i < 10; i++) {
            assertEquals(5100L + i * 50L, book.asks[i].price);
            assertEquals(600L + i * 6L, book.asks[i].size);
            assertEquals(4L + i, book.asks[i].count);
        }

        assertEquals(99999L, book.sequenceNumber);
    }

    @Test
    void testUpdateFromOverwritesPreviousData() {
        // Set initial data
        book.bids[0].price = 1000L;
        book.asks[0].price = 2000L;
        book.sequenceNumber = 100L;

        // Update with new data
        populateSchema(schema);
        book.updateFrom(schema);

        // Verify old data is overwritten
        assertEquals(5000L, book.bids[0].price);
        assertEquals(5100L, book.asks[0].price);
        assertEquals(99999L, book.sequenceNumber);
    }

    // ========== Reset Tests ==========

    @Test
    void testResetClearsAllData() {
        // Populate book
        for (int i = 0; i < 10; i++) {
            book.bids[i].price = 1000L + i;
            book.bids[i].size = 100L + i;
            book.bids[i].count = 10L + i;
            book.asks[i].price = 2000L + i;
            book.asks[i].size = 200L + i;
            book.asks[i].count = 20L + i;
        }
        book.sequenceNumber = 12345L;

        book.reset();

        // Verify all data is reset to null values
        assertEquals(MBP10Encoder.sequenceNullValue(), book.sequenceNumber);
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.bids[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.bids[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.bids[i].count);
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.asks[i].price);
            assertEquals(MBP10Encoder.askSize0NullValue(), book.asks[i].size);
            assertEquals(MBP10Encoder.askCount0NullValue(), book.asks[i].count);
        }
    }

    @Test
    void testResetOnEmptyBook() {
        book.reset();

        // Should remain in null state
        assertEquals(MBP10Encoder.sequenceNullValue(), book.sequenceNumber);
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.bids[i].price);
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.asks[i].price);
        }
    }

    // ========== CopyFrom Tests ==========

    @Test
    void testCopyFromEmptyBook() {
        MBPBook source = new MBPBook(10);
        book.copyFrom(source);

        assertEquals(source.sequenceNumber, book.sequenceNumber);
        for (int i = 0; i < 10; i++) {
            assertEquals(source.bids[i].price, book.bids[i].price);
            assertEquals(source.bids[i].size, book.bids[i].size);
            assertEquals(source.bids[i].count, book.bids[i].count);
            assertEquals(source.asks[i].price, book.asks[i].price);
            assertEquals(source.asks[i].size, book.asks[i].size);
            assertEquals(source.asks[i].count, book.asks[i].count);
        }
    }

    @Test
    void testCopyFromPopulatedBook() {
        MBPBook source = new MBPBook(10);
        source.sequenceNumber = 54321L;
        for (int i = 0; i < 10; i++) {
            source.bids[i].price = 3000L + i;
            source.bids[i].size = 300L + i;
            source.bids[i].count = 30L + i;
            source.asks[i].price = 4000L + i;
            source.asks[i].size = 400L + i;
            source.asks[i].count = 40L + i;
        }

        book.copyFrom(source);

        assertEquals(54321L, book.sequenceNumber);
        for (int i = 0; i < 10; i++) {
            assertEquals(3000L + i, book.bids[i].price);
            assertEquals(300L + i, book.bids[i].size);
            assertEquals(30L + i, book.bids[i].count);
            assertEquals(4000L + i, book.asks[i].price);
            assertEquals(400L + i, book.asks[i].size);
            assertEquals(40L + i, book.asks[i].count);
        }
    }

    @Test
    void testCopyFromDoesNotShareReferences() {
        MBPBook source = new MBPBook(10);
        source.bids[0].price = 1000L;
        source.asks[0].price = 2000L;

        book.copyFrom(source);

        // Modify source
        source.bids[0].price = 9999L;
        source.asks[0].price = 8888L;

        // Verify book is not affected
        assertEquals(1000L, book.bids[0].price);
        assertEquals(2000L, book.asks[0].price);
    }

    // ========== CompareTo Tests ==========

    @Test
    void testCompareToWithEqualSequence() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 100L;
        other.sequenceNumber = 100L;

        assertEquals(0, book.compareTo(other));
    }

    @Test
    void testCompareToWithLowerSequence() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 50L;
        other.sequenceNumber = 100L;

        assertEquals(-1, book.compareTo(other));
    }

    @Test
    void testCompareToWithHigherSequence() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 200L;
        other.sequenceNumber = 100L;

        assertEquals(1, book.compareTo(other));
    }

    @Test
    void testCompareToWithNullSequences() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = MBP10Encoder.sequenceNullValue();
        other.sequenceNumber = MBP10Encoder.sequenceNullValue();

        assertEquals(0, book.compareTo(other));
    }

    @Test
    void testCompareToWithExtremeValues() {
        MBPBook other = new MBPBook(10);

        book.sequenceNumber = Long.MIN_VALUE;
        other.sequenceNumber = Long.MAX_VALUE;
        assertEquals(-1, book.compareTo(other));

        book.sequenceNumber = Long.MAX_VALUE;
        other.sequenceNumber = Long.MIN_VALUE;
        assertEquals(1, book.compareTo(other));
    }

    // ========== Equals Tests ==========

    @Test
    void testEqualsWithSameObject() {
        assertEquals(book, book);
    }

    @Test
    void testEqualsWithNull() {
        assertNotEquals(null, book);
    }

    @Test
    void testEqualsWithDifferentClass() {
        assertNotEquals("not a book", book);
    }

    @Test
    void testEqualsWithEmptyBooks() {
        MBPBook other = new MBPBook(10);
        assertEquals(book, other);
    }

    @Test
    void testEqualsWithIdenticalBooks() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 123L;
        other.sequenceNumber = 123L;

        for (int i = 0; i < 10; i++) {
            book.bids[i].price = 1000L + i;
            book.bids[i].size = 100L + i;
            book.bids[i].count = 10L + i;
            other.bids[i].price = 1000L + i;
            other.bids[i].size = 100L + i;
            other.bids[i].count = 10L + i;

            book.asks[i].price = 2000L + i;
            book.asks[i].size = 200L + i;
            book.asks[i].count = 20L + i;
            other.asks[i].price = 2000L + i;
            other.asks[i].size = 200L + i;
            other.asks[i].count = 20L + i;
        }

        assertEquals(book, other);
    }

    @Test
    void testEqualsWithDifferentSequence() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 100L;
        other.sequenceNumber = 200L;

        assertNotEquals(book, other);
    }

    @Test
    void testEqualsWithDifferentBidPrice() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 100L;
        other.sequenceNumber = 100L;

        book.bids[0].price = 1000L;
        other.bids[0].price = 2000L;

        assertNotEquals(book, other);
    }

    @Test
    void testEqualsWithDifferentAskSize() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 100L;
        other.sequenceNumber = 100L;

        book.asks[5].size = 1000L;
        other.asks[5].size = 2000L;

        assertNotEquals(book, other);
    }

    @Test
    void testEqualsWithDifferentBidCount() {
        MBPBook other = new MBPBook(10);
        book.sequenceNumber = 100L;
        other.sequenceNumber = 100L;

        book.bids[9].count = 10L;
        other.bids[9].count = 20L;

        assertNotEquals(book, other);
    }


    // ========== PriceLevel Tests ==========

    @Test
    void testPriceLevelReset() {
        MBPBook.PriceLevel level = new MBPBook.PriceLevel();
        level.price = 1000L;
        level.size = 100L;
        level.count = 10L;

        level.reset();

        assertEquals(MBP10Encoder.askPrice0NullValue(), level.price);
        assertEquals(MBP10Encoder.askSize0NullValue(), level.size);
        assertEquals(MBP10Encoder.askCount0NullValue(), level.count);
    }

    @Test
    void testPriceLevelCopyFrom() {
        MBPBook.PriceLevel source = new MBPBook.PriceLevel();
        source.price = 5000L;
        source.size = 500L;
        source.count = 50L;

        MBPBook.PriceLevel target = new MBPBook.PriceLevel();
        target.copyFrom(source);

        assertEquals(5000L, target.price);
        assertEquals(500L, target.size);
        assertEquals(50L, target.count);
    }

    @Test
    void testPriceLevelEquals() {
        MBPBook.PriceLevel level1 = new MBPBook.PriceLevel();
        MBPBook.PriceLevel level2 = new MBPBook.PriceLevel();

        assertEquals(level1, level2);

        level1.price = 1000L;
        level1.size = 100L;
        level1.count = 10L;

        assertNotEquals(level1, level2);

        level2.price = 1000L;
        level2.size = 100L;
        level2.count = 10L;

        assertEquals(level1, level2);
    }

    @Test
    void testPriceLevelUpdateReturnsTrue() {
        MBPBook.PriceLevel level = new MBPBook.PriceLevel();

        assertTrue(level.update(1000L, 100L, 10L));
        assertEquals(1000L, level.price);
        assertEquals(100L, level.size);
        assertEquals(10L, level.count);
    }

    @Test
    void testPriceLevelUpdateReturnsFalseWhenNoChange() {
        MBPBook.PriceLevel level = new MBPBook.PriceLevel();
        level.price = 1000L;
        level.size = 100L;
        level.count = 10L;

        assertFalse(level.update(1000L, 100L, 10L));
    }

    @Test
    void testPriceLevelUpdatePartialChange() {
        MBPBook.PriceLevel level = new MBPBook.PriceLevel();
        level.price = 1000L;
        level.size = 100L;
        level.count = 10L;

        // Only price changes
        assertTrue(level.update(2000L, 100L, 10L));
        assertEquals(2000L, level.price);

        // Only size changes
        assertTrue(level.update(2000L, 200L, 10L));
        assertEquals(200L, level.size);

        // Only count changes
        assertTrue(level.update(2000L, 200L, 20L));
        assertEquals(20L, level.count);
    }

    // ========== Integration Tests ==========

    @Test
    void testWriteToAndUpdateFromRoundTrip() {
        // Populate book
        book.sequenceNumber = 777L;
        for (int i = 0; i < 10; i++) {
            book.bids[i].price = 8000L - i * 10L;
            book.bids[i].size = 800L + i;
            book.bids[i].count = 8L + i;
            book.asks[i].price = 8100L + i * 10L;
            book.asks[i].size = 900L + i;
            book.asks[i].count = 9L + i;
        }

        // Write to schema
        book.writeTo(schema);

        // Create new book and update from schema
        MBPBook newBook = new MBPBook(10);
        schema.encoder.sequence(777L);
        newBook.updateFrom(schema);

        // Verify books are equal
        assertEquals(book, newBook);
    }

    @Test
    void testMultipleUpdatesFromSchema() {
        // First update
        populateSchema(schema);
        book.updateFrom(schema);
        long firstSeq = book.sequenceNumber;

        // Second update with different data
        schema.encoder.sequence(88888L);
        schema.encoder.bidPrice0(9999L);
        schema.encoder.askPrice0(10001L);

        book.updateFrom(schema);

        assertEquals(88888L, book.sequenceNumber);
        assertNotEquals(firstSeq, book.sequenceNumber);
        assertEquals(9999L, book.bids[0].price);
        assertEquals(10001L, book.asks[0].price);
    }

    @Test
    void testResetAfterPopulation() {
        populateSchema(schema);
        book.updateFrom(schema);

        assertNotEquals(MBP10Encoder.sequenceNullValue(), book.sequenceNumber);

        book.reset();

        assertEquals(MBP10Encoder.sequenceNullValue(), book.sequenceNumber);
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.bids[i].price);
            assertEquals(MBP10Encoder.askPrice0NullValue(), book.asks[i].price);
        }
    }

    // ========== Helper Methods ==========

    private void populateSchema(MBP10Schema schema) {
        schema.encoder.sequence(99999L);
        for (int i = 0; i < 10; i++) {
            setBidLevel(schema, i, 5000L - i * 50L, 500L + i * 5L, 3L + i);
            setAskLevel(schema, i, 5100L + i * 50L, 600L + i * 6L, 4L + i);
        }
    }

    private void setAllSchemaValues(MBP10Schema schema, long price, long size, long count, long sequence) {
        schema.encoder.sequence(sequence);
        for (int i = 0; i < 10; i++) {
            setBidLevel(schema, i, price, size, count);
            setAskLevel(schema, i, price, size, count);
        }
    }

    private void setBidLevel(MBP10Schema schema, int level, long price, long size, long count) {
        switch (level) {
            case 0: schema.encoder.bidPrice0(price); schema.encoder.bidSize0(size); schema.encoder.bidCount0(count); break;
            case 1: schema.encoder.bidPrice1(price); schema.encoder.bidSize1(size); schema.encoder.bidCount1(count); break;
            case 2: schema.encoder.bidPrice2(price); schema.encoder.bidSize2(size); schema.encoder.bidCount2(count); break;
            case 3: schema.encoder.bidPrice3(price); schema.encoder.bidSize3(size); schema.encoder.bidCount3(count); break;
            case 4: schema.encoder.bidPrice4(price); schema.encoder.bidSize4(size); schema.encoder.bidCount4(count); break;
            case 5: schema.encoder.bidPrice5(price); schema.encoder.bidSize5(size); schema.encoder.bidCount5(count); break;
            case 6: schema.encoder.bidPrice6(price); schema.encoder.bidSize6(size); schema.encoder.bidCount6(count); break;
            case 7: schema.encoder.bidPrice7(price); schema.encoder.bidSize7(size); schema.encoder.bidCount7(count); break;
            case 8: schema.encoder.bidPrice8(price); schema.encoder.bidSize8(size); schema.encoder.bidCount8(count); break;
            case 9: schema.encoder.bidPrice9(price); schema.encoder.bidSize9(size); schema.encoder.bidCount9(count); break;
        }
    }

    private void setAskLevel(MBP10Schema schema, int level, long price, long size, long count) {
        switch (level) {
            case 0: schema.encoder.askPrice0(price); schema.encoder.askSize0(size); schema.encoder.askCount0(count); break;
            case 1: schema.encoder.askPrice1(price); schema.encoder.askSize1(size); schema.encoder.askCount1(count); break;
            case 2: schema.encoder.askPrice2(price); schema.encoder.askSize2(size); schema.encoder.askCount2(count); break;
            case 3: schema.encoder.askPrice3(price); schema.encoder.askSize3(size); schema.encoder.askCount3(count); break;
            case 4: schema.encoder.askPrice4(price); schema.encoder.askSize4(size); schema.encoder.askCount4(count); break;
            case 5: schema.encoder.askPrice5(price); schema.encoder.askSize5(size); schema.encoder.askCount5(count); break;
            case 6: schema.encoder.askPrice6(price); schema.encoder.askSize6(size); schema.encoder.askCount6(count); break;
            case 7: schema.encoder.askPrice7(price); schema.encoder.askSize7(size); schema.encoder.askCount7(count); break;
            case 8: schema.encoder.askPrice8(price); schema.encoder.askSize8(size); schema.encoder.askCount8(count); break;
            case 9: schema.encoder.askPrice9(price); schema.encoder.askSize9(size); schema.encoder.askCount9(count); break;
        }
    }

    private long getBidPrice(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.bidPrice0();
            case 1 -> schema.decoder.bidPrice1();
            case 2 -> schema.decoder.bidPrice2();
            case 3 -> schema.decoder.bidPrice3();
            case 4 -> schema.decoder.bidPrice4();
            case 5 -> schema.decoder.bidPrice5();
            case 6 -> schema.decoder.bidPrice6();
            case 7 -> schema.decoder.bidPrice7();
            case 8 -> schema.decoder.bidPrice8();
            case 9 -> schema.decoder.bidPrice9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    private long getBidSize(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.bidSize0();
            case 1 -> schema.decoder.bidSize1();
            case 2 -> schema.decoder.bidSize2();
            case 3 -> schema.decoder.bidSize3();
            case 4 -> schema.decoder.bidSize4();
            case 5 -> schema.decoder.bidSize5();
            case 6 -> schema.decoder.bidSize6();
            case 7 -> schema.decoder.bidSize7();
            case 8 -> schema.decoder.bidSize8();
            case 9 -> schema.decoder.bidSize9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    private long getBidCount(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.bidCount0();
            case 1 -> schema.decoder.bidCount1();
            case 2 -> schema.decoder.bidCount2();
            case 3 -> schema.decoder.bidCount3();
            case 4 -> schema.decoder.bidCount4();
            case 5 -> schema.decoder.bidCount5();
            case 6 -> schema.decoder.bidCount6();
            case 7 -> schema.decoder.bidCount7();
            case 8 -> schema.decoder.bidCount8();
            case 9 -> schema.decoder.bidCount9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    private long getAskPrice(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.askPrice0();
            case 1 -> schema.decoder.askPrice1();
            case 2 -> schema.decoder.askPrice2();
            case 3 -> schema.decoder.askPrice3();
            case 4 -> schema.decoder.askPrice4();
            case 5 -> schema.decoder.askPrice5();
            case 6 -> schema.decoder.askPrice6();
            case 7 -> schema.decoder.askPrice7();
            case 8 -> schema.decoder.askPrice8();
            case 9 -> schema.decoder.askPrice9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    private long getAskSize(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.askSize0();
            case 1 -> schema.decoder.askSize1();
            case 2 -> schema.decoder.askSize2();
            case 3 -> schema.decoder.askSize3();
            case 4 -> schema.decoder.askSize4();
            case 5 -> schema.decoder.askSize5();
            case 6 -> schema.decoder.askSize6();
            case 7 -> schema.decoder.askSize7();
            case 8 -> schema.decoder.askSize8();
            case 9 -> schema.decoder.askSize9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    private long getAskCount(MBP10Schema schema, int level) {
        return switch (level) {
            case 0 -> schema.decoder.askCount0();
            case 1 -> schema.decoder.askCount1();
            case 2 -> schema.decoder.askCount2();
            case 3 -> schema.decoder.askCount3();
            case 4 -> schema.decoder.askCount4();
            case 5 -> schema.decoder.askCount5();
            case 6 -> schema.decoder.askCount6();
            case 7 -> schema.decoder.askCount7();
            case 8 -> schema.decoder.askCount8();
            case 9 -> schema.decoder.askCount9();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }
}

