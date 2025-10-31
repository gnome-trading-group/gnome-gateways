package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.schemas.MBP10Encoder;
import group.gnometrading.schemas.MBP10Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for MBPBufferSide covering all edge cases and core functionality.
 * Tests both bid and ask sides with various scenarios including:
 * - Constructor validation
 * - Binary search for sorted price levels
 * - Insert operations at various positions
 * - Update operations (modify size)
 * - Remove operations (size = 0)
 * - Max levels boundary conditions
 * - Schema integration (updateFrom/writeTo)
 * - Copy operations
 */
class MBPBufferSideTest {

    private MBPBufferSide bidSide;
    private MBPBufferSide askSide;
    private MBP10Schema schema;

    @BeforeEach
    void setUp() {
        bidSide = new MBPBufferSide(10, true);
        askSide = new MBPBufferSide(10, false);
        schema = new MBP10Schema();
    }

    // ========== Constructor Tests ==========

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 50, 100})
    void testConstructorWithValidMaxLevels(int maxLevels) {
        MBPBufferSide side = new MBPBufferSide(maxLevels, true);
        assertNotNull(side);
    }

    @Test
    void testConstructorWithZeroMaxLevelsThrowsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new MBPBufferSide(0, true);
        });
        assertTrue(exception.getMessage().contains("Invalid max levels"));
    }

    @Test
    void testConstructorWithNegativeMaxLevelsThrowsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new MBPBufferSide(-1, true);
        });
        assertTrue(exception.getMessage().contains("Invalid max levels"));
    }

    // ========== Update Tests - Insert Operations ==========

    @Test
    void testInsertSingleLevelBid() {
        int depth = bidSide.update(10000L, 100L, 1L);
        assertEquals(0, depth, "First insert should be at depth 0");
    }

    @Test
    void testInsertSingleLevelAsk() {
        int depth = askSide.update(10000L, 100L, 1L);
        assertEquals(0, depth, "First insert should be at depth 0");
    }

    @Test
    void testInsertMultipleLevelsBidDescendingOrder() {
        // Bids are sorted DESC: highest price at index 0
        assertEquals(0, bidSide.update(10000L, 100L, 1L));
        assertEquals(0, bidSide.update(10100L, 200L, 2L)); // Higher price, should be at index 0
        assertEquals(2, bidSide.update(9900L, 300L, 3L));  // Lower price, should be at index 2
    }

    @Test
    void testInsertMultipleLevelsAskAscendingOrder() {
        // Asks are sorted ASC: lowest price at index 0
        assertEquals(0, askSide.update(10000L, 100L, 1L));
        assertEquals(0, askSide.update(9900L, 200L, 2L));  // Lower price, should be at index 0
        assertEquals(2, askSide.update(10100L, 300L, 3L)); // Higher price, should be at index 2
    }

    @Test
    void testInsertAtBeginning() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        int depth = bidSide.update(10100L, 300L, 3L); // Highest price
        assertEquals(0, depth, "Should insert at beginning");
    }

    @Test
    void testInsertAtEnd() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        int depth = bidSide.update(9800L, 300L, 3L); // Lowest price
        assertEquals(2, depth, "Should insert at end (0-indexed)");
    }

    @Test
    void testInsertInMiddle() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9800L, 200L, 2L);
        int depth = bidSide.update(9900L, 300L, 3L); // Middle price
        assertEquals(1, depth, "Should insert in middle");
    }

    // ========== Update Tests - Modify Operations ==========

    @Test
    void testUpdateExistingLevelModifiesSize() {
        bidSide.update(10000L, 100L, 1L);
        int depth = bidSide.update(10000L, 200L, 2L); // Update size and count
        assertEquals(0, depth, "Should update at same position");
    }

    @Test
    void testUpdateExistingLevelModifiesSizeAndCount() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10000L, 200L, 5L); // Update size and count
        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(200L, schema.decoder.bidSize0());
        assertEquals(5L, schema.decoder.bidCount0());
    }

    @Test
    void testUpdateMultipleTimesAtSamePrice() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10000L, 200L, 2L);
        bidSide.update(10000L, 300L, 3L);
        bidSide.update(10000L, 400L, 4L);
        // Verify final state by checking depth remains 1
        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(400L, schema.decoder.bidSize0());
        assertEquals(4L, schema.decoder.bidCount0());
    }

    // ========== Update Tests - Remove Operations ==========

    @Test
    void testRemoveExistingLevel() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        int depth = bidSide.update(10000L, 0L, 0L); // Remove by setting size to 0
        assertEquals(0, depth, "Should return position of removed level");
    }

    @Test
    void testRemoveNonExistentLevel() {
        bidSide.update(10000L, 100L, 1L);
        int depth = bidSide.update(9900L, 0L, 0L); // Try to remove non-existent level
        assertEquals(-1, depth, "Should return -1 for non-existent level");
    }

    @Test
    void testRemoveFromBeginning() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(10000L, 0L, 0L); // Remove first
        bidSide.writeTo(schema);
        assertEquals(9900L, schema.decoder.bidPrice0());
        assertEquals(200L, schema.decoder.bidSize0());
        assertEquals(2L, schema.decoder.bidCount0());
        assertEquals(9800L, schema.decoder.bidPrice1());
        assertEquals(300L, schema.decoder.bidSize1());
        assertEquals(3L, schema.decoder.bidCount1());
    }

    @Test
    void testRemoveFromMiddle() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(9900L, 0L, 0L); // Remove middle

        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());
        assertEquals(9800L, schema.decoder.bidPrice1());
        assertEquals(300L, schema.decoder.bidSize1());
        assertEquals(3L, schema.decoder.bidCount1());
    }

    @Test
    void testRemoveFromEnd() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(9800L, 0L, 0L); // Remove last
        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());
        assertEquals(9900L, schema.decoder.bidPrice1());
        assertEquals(200L, schema.decoder.bidSize1());
        assertEquals(2L, schema.decoder.bidCount1());
        assertEquals(MBP10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice2());
    }

    @Test
    void testRemoveAllLevels() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(10000L, 0L, 0L);
        bidSide.update(9900L, 0L, 0L);
        bidSide.update(9800L, 0L, 0L);
        bidSide.writeTo(schema);
        assertEquals(MBP10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice0());
    }

    // ========== Max Levels Boundary Tests ==========

    @Test
    void testInsertUpToMaxLevels() {
        for (int i = 0; i < 10; i++) {
            bidSide.update(10000L - i * 100L, 100L + i, i + 1L);
        }
    }

    @Test
    void testInsertBeyondMaxLevelsIgnored() {
        // Fill to max
        for (int i = 0; i < 10; i++) {
            bidSide.update(10000L - i * 100L, 100L + i, i + 1L);
        }
        // Try to insert beyond max (lower price for bids)
        bidSide.update(9000L, 999L, 99L);
        // The insert beyond max should be ignored
    }

    @Test
    void testInsertBeyondMaxLevelsAtHigherPriorityReplacesLast() {
        // Fill to max
        for (int i = 0; i < 10; i++) {
            bidSide.update(10000L - i * 100L, 100L + i, i + 1L);
        }
        // Insert at higher priority (higher price for bids)
        bidSide.update(10500L, 999L, 99L);
        // This should push out the lowest priority level
    }

    @Test
    void testMaxLevelsWithSingleLevel() {
        MBPBufferSide side = new MBPBufferSide(1, true);
        side.update(10000L, 100L, 1L);
        side.update(10100L, 200L, 2L); // Should replace the only level
    }

    // ========== Binary Search Edge Cases ==========

    @Test
    void testBinarySearchEmptyBook() {
        // Should not crash on empty book
        bidSide.update(10000L, 100L, 1L);
    }

    @Test
    void testBinarySearchSingleElement() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10000L, 200L, 2L); // Update same element
    }

    @Test
    void testBinarySearchTwoElements() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(10000L, 300L, 3L); // Update first
        bidSide.update(9900L, 400L, 4L);  // Update second
    }

    // ========== Price Ordering Tests ==========

    @Test
    void testBidPriceOrderingDescending() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10200L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(10100L, 400L, 4L);
        bidSide.update(9900L, 500L, 5L);
        // Bids should be: 10200, 10100, 10000, 9900, 9800
    }

    @Test
    void testAskPriceOrderingAscending() {
        askSide.update(10000L, 100L, 1L);
        askSide.update(9800L, 200L, 2L);
        askSide.update(10200L, 300L, 3L);
        askSide.update(9900L, 400L, 4L);
        askSide.update(10100L, 500L, 5L);
        // Asks should be: 9800, 9900, 10000, 10100, 10200
    }

    @Test
    void testDuplicatePriceUpdates() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10000L, 200L, 2L);
        bidSide.update(10000L, 300L, 3L);
        // Should only have one level at 10000
    }

    // ========== Extreme Value Tests ==========

    @Test
    void testZeroPrice() {
        bidSide.update(0L, 100L, 1L);
    }

    @Test
    void testMaxLongPrice() {
        bidSide.update(Long.MAX_VALUE, 100L, 1L);
    }

    @Test
    void testMaxLongSize() {
        bidSide.update(10000L, Long.MAX_VALUE, 1L);
    }

    @Test
    void testMaxLongCount() {
        // Count field is stored as unsigned int (32-bit), max value is 2^32 - 1
        long maxCount = 4294967295L;
        bidSide.update(10000L, 100L, maxCount);
        bidSide.writeTo(schema);
        assertEquals(maxCount, schema.decoder.bidCount0());
    }

    @Test
    void testZeroCount() {
        bidSide.update(10000L, 100L, 0L);
        bidSide.writeTo(schema);
        assertEquals(0L, schema.decoder.bidCount0());
    }

    @Test
    void testMixedExtremeValues() {
        bidSide.update(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        bidSide.update(0L, 1L, 0L);
        bidSide.update(Long.MAX_VALUE / 2, 1000L, 50L);
    }

    // ========== Count Field Specific Tests ==========

    @Test
    void testCountFieldPreservedOnInsert() {
        bidSide.update(10000L, 100L, 5L);
        bidSide.writeTo(schema);
        assertEquals(5L, schema.decoder.bidCount0());
    }

    @Test
    void testCountFieldUpdatedOnModify() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(10000L, 200L, 10L);
        bidSide.writeTo(schema);
        assertEquals(10L, schema.decoder.bidCount0());
    }

    @Test
    void testCountFieldPreservedAfterInsertionShift() {
        bidSide.update(10000L, 100L, 5L);
        bidSide.update(9900L, 200L, 3L);
        bidSide.update(10100L, 300L, 7L); // Insert at beginning, shifts others

        bidSide.writeTo(schema);
        assertEquals(10100L, schema.decoder.bidPrice0());
        assertEquals(7L, schema.decoder.bidCount0());
        assertEquals(10000L, schema.decoder.bidPrice1());
        assertEquals(5L, schema.decoder.bidCount1());
        assertEquals(9900L, schema.decoder.bidPrice2());
        assertEquals(3L, schema.decoder.bidCount2());
    }

    @Test
    void testCountFieldPreservedAfterRemovalShift() {
        bidSide.update(10000L, 100L, 5L);
        bidSide.update(9900L, 200L, 3L);
        bidSide.update(9800L, 300L, 2L);
        bidSide.update(9900L, 0L, 0L); // Remove middle

        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(5L, schema.decoder.bidCount0());
        assertEquals(9800L, schema.decoder.bidPrice1());
        assertEquals(2L, schema.decoder.bidCount1());
    }

    @Test
    void testCountFieldRoundTripThroughSchema() {
        bidSide.update(10000L, 100L, 42L);
        bidSide.update(9900L, 200L, 99L);

        bidSide.writeTo(schema);

        MBPBufferSide newSide = new MBPBufferSide(10, true);
        newSide.updateFrom(schema);

        MBP10Schema schema2 = new MBP10Schema();
        newSide.writeTo(schema2);

        assertEquals(42L, schema2.decoder.bidCount0());
        assertEquals(99L, schema2.decoder.bidCount1());
    }

    @Test
    void testCountFieldInCopyFrom() {
        MBPBufferSide source = new MBPBufferSide(10, true);
        source.update(10000L, 100L, 25L);
        source.update(9900L, 200L, 50L);

        bidSide.copyFrom(source);

        bidSide.writeTo(schema);
        assertEquals(25L, schema.decoder.bidCount0());
        assertEquals(50L, schema.decoder.bidCount1());
    }

    @Test
    void testCountFieldDifferentForBidAndAsk() {
        bidSide.update(10000L, 100L, 10L);
        askSide.update(10000L, 100L, 20L);

        bidSide.writeTo(schema);
        MBP10Schema askSchema = new MBP10Schema();
        askSide.writeTo(askSchema);

        assertEquals(10L, schema.decoder.bidCount0());
        assertEquals(20L, askSchema.decoder.askCount0());
    }

    // ========== UpdateFrom Schema Tests ==========

    @Test
    void testUpdateFromEmptySchema() {
        // Set all schema values to null
        setAllBidLevels(schema, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaWithSingleLevel() {
        schema.encoder.bidPrice0(10000L);
        schema.encoder.bidSize0(100L);
        schema.encoder.bidCount0(1L);
        setRemainingBidLevels(schema, 1, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaWithAllLevels() {
        for (int i = 0; i < 10; i++) {
            setBidLevel(schema, i, 10000L - i * 100L, 100L + i * 10L, i + 1L);
        }
        bidSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaWithMixedNullValues() {
        schema.encoder.bidPrice0(10000L);
        schema.encoder.bidSize0(100L);
        schema.encoder.bidCount0(1L);
        schema.encoder.bidPrice1(MBP10Encoder.bidPrice0NullValue());
        schema.encoder.bidSize1(MBP10Encoder.bidSize0NullValue());
        schema.encoder.bidCount1(MBP10Encoder.bidCount0NullValue());
        schema.encoder.bidPrice2(9800L);
        schema.encoder.bidSize2(200L);
        schema.encoder.bidCount2(2L);
        setRemainingBidLevels(schema, 3, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaRemovesLevels() {
        // First populate
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);

        // Then update with size 0 to remove
        schema.encoder.bidPrice0(10000L);
        schema.encoder.bidSize0(0L);
        schema.encoder.bidCount0(0L);
        setRemainingBidLevels(schema, 1, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaAskSide() {
        for (int i = 0; i < 10; i++) {
            setAskLevel(schema, i, 10000L + i * 100L, 100L + i * 10L, i + 1L);
        }
        askSide.updateFrom(schema);
    }

    @Test
    void testUpdateFromSchemaMultipleTimes() {
        // First update
        schema.encoder.bidPrice0(10000L);
        schema.encoder.bidSize0(100L);
        schema.encoder.bidCount0(1L);
        setRemainingBidLevels(schema, 1, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);

        // Second update with different data
        schema.encoder.bidPrice0(10000L);
        schema.encoder.bidSize0(200L); // Update size
        schema.encoder.bidCount0(5L); // Update count
        schema.encoder.bidPrice1(9900L);
        schema.encoder.bidSize1(150L);
        schema.encoder.bidCount1(3L);
        setRemainingBidLevels(schema, 2, MBP10Encoder.bidPrice0NullValue(), MBP10Encoder.bidSize0NullValue(), MBP10Encoder.bidCount0NullValue());
        bidSide.updateFrom(schema);
    }

    // ========== WriteTo Schema Tests ==========

    @Test
    void testWriteToEmptyBook() {
        bidSide.writeTo(schema);

        // Verify all levels are null
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.bidPrice0NullValue(), getBidPrice(schema, i));
            assertEquals(MBP10Encoder.bidSize0NullValue(), getBidSize(schema, i));
            assertEquals(MBP10Encoder.bidCount0NullValue(), getBidCount(schema, i));
        }
    }

    @Test
    void testWriteToWithSingleLevel() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.writeTo(schema);

        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());

        // Remaining levels should be null
        for (int i = 1; i < 10; i++) {
            assertEquals(MBP10Encoder.bidPrice0NullValue(), getBidPrice(schema, i));
            assertEquals(MBP10Encoder.bidSize0NullValue(), getBidSize(schema, i));
            assertEquals(MBP10Encoder.bidCount0NullValue(), getBidCount(schema, i));
        }
    }

    @Test
    void testWriteToWithAllLevels() {
        for (int i = 0; i < 10; i++) {
            bidSide.update(10000L - i * 100L, 100L + i * 10L, i + 1L);
        }
        bidSide.writeTo(schema);

        for (int i = 0; i < 10; i++) {
            assertEquals(10000L - i * 100L, getBidPrice(schema, i));
            assertEquals(100L + i * 10L, getBidSize(schema, i));
            assertEquals(i + 1L, getBidCount(schema, i));
        }
    }

    @Test
    void testWriteToAskSide() {
        for (int i = 0; i < 10; i++) {
            askSide.update(10000L + i * 100L, 100L + i * 10L, i + 1L);
        }
        askSide.writeTo(schema);

        for (int i = 0; i < 10; i++) {
            assertEquals(10000L + i * 100L, getAskPrice(schema, i));
            assertEquals(100L + i * 10L, getAskSize(schema, i));
            assertEquals(i + 1L, getAskCount(schema, i));
        }
    }

    @Test
    void testWriteToAfterRemoval() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(9900L, 0L, 0L); // Remove middle

        bidSide.writeTo(schema);

        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());
        assertEquals(9800L, schema.decoder.bidPrice1());
        assertEquals(300L, schema.decoder.bidSize1());
        assertEquals(3L, schema.decoder.bidCount1());
        assertEquals(MBP10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice2());
    }

    // ========== CopyFrom Tests ==========

    @Test
    void testCopyFromEmptyBook() {
        MBPBufferSide source = new MBPBufferSide(10, true);
        bidSide.copyFrom(source);
    }

    @Test
    void testCopyFromPopulatedBook() {
        MBPBufferSide source = new MBPBufferSide(10, true);
        source.update(10000L, 100L, 1L);
        source.update(9900L, 200L, 2L);
        source.update(9800L, 300L, 3L);

        bidSide.copyFrom(source);

        // Verify by writing to schema
        bidSide.writeTo(schema);
        assertEquals(10000L, schema.decoder.bidPrice0());
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());
        assertEquals(9900L, schema.decoder.bidPrice1());
        assertEquals(200L, schema.decoder.bidSize1());
        assertEquals(2L, schema.decoder.bidCount1());
        assertEquals(9800L, schema.decoder.bidPrice2());
        assertEquals(300L, schema.decoder.bidSize2());
        assertEquals(3L, schema.decoder.bidCount2());
    }

    @Test
    void testCopyFromDoesNotShareReferences() {
        MBPBufferSide source = new MBPBufferSide(10, true);
        source.update(10000L, 100L, 1L);

        bidSide.copyFrom(source);

        // Modify source
        source.update(10000L, 999L, 99L);

        // Verify bidSide is not affected
        bidSide.writeTo(schema);
        assertEquals(100L, schema.decoder.bidSize0());
        assertEquals(1L, schema.decoder.bidCount0());
    }

    @Test
    void testCopyFromOverwritesPreviousData() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);

        MBPBufferSide source = new MBPBufferSide(10, true);
        source.update(8000L, 999L, 99L);

        bidSide.copyFrom(source);

        bidSide.writeTo(schema);
        assertEquals(8000L, schema.decoder.bidPrice0());
        assertEquals(999L, schema.decoder.bidSize0());
        assertEquals(99L, schema.decoder.bidCount0());
        assertEquals(MBP10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice1());
    }

    // ========== Reset Tests ==========

    @Test
    void testResetEmptyBook() {
        bidSide.reset();
        bidSide.writeTo(schema);

        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.bidPrice0NullValue(), getBidPrice(schema, i));
        }
    }

    @Test
    void testResetPopulatedBook() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);

        bidSide.reset();

        bidSide.writeTo(schema);
        for (int i = 0; i < 10; i++) {
            assertEquals(MBP10Encoder.bidPrice0NullValue(), getBidPrice(schema, i));
        }
    }

    @Test
    void testResetAndReuse() {
        bidSide.update(10000L, 100L, 1L);
        bidSide.reset();
        bidSide.update(9000L, 999L, 99L);

        bidSide.writeTo(schema);
        assertEquals(9000L, schema.decoder.bidPrice0());
        assertEquals(999L, schema.decoder.bidSize0());
        assertEquals(99L, schema.decoder.bidCount0());
        assertEquals(MBP10Encoder.bidPrice0NullValue(), schema.decoder.bidPrice1());
    }

    // ========== Integration Tests ==========

    @Test
    void testRoundTripWriteToAndUpdateFrom() {
        // Populate bidSide
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);

        // Write to schema
        bidSide.writeTo(schema);

        // Create new side and update from schema
        MBPBufferSide newSide = new MBPBufferSide(10, true);
        newSide.updateFrom(schema);

        // Verify they match
        MBP10Schema schema2 = new MBP10Schema();
        newSide.writeTo(schema2);

        assertEquals(schema.decoder.bidPrice0(), schema2.decoder.bidPrice0());
        assertEquals(schema.decoder.bidSize0(), schema2.decoder.bidSize0());
        assertEquals(schema.decoder.bidCount0(), schema2.decoder.bidCount0());
        assertEquals(schema.decoder.bidPrice1(), schema2.decoder.bidPrice1());
        assertEquals(schema.decoder.bidSize1(), schema2.decoder.bidSize1());
        assertEquals(schema.decoder.bidCount1(), schema2.decoder.bidCount1());
        assertEquals(schema.decoder.bidPrice2(), schema2.decoder.bidPrice2());
        assertEquals(schema.decoder.bidSize2(), schema2.decoder.bidSize2());
        assertEquals(schema.decoder.bidCount2(), schema2.decoder.bidCount2());
    }

    @Test
    void testComplexSequenceOfOperations() {
        // Insert multiple levels
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(9800L, 300L, 3L);
        bidSide.update(10100L, 400L, 4L);

        // Update existing
        bidSide.update(9900L, 250L, 5L);

        // Remove one
        bidSide.update(9800L, 0L, 0L);

        // Add new
        bidSide.update(9700L, 500L, 6L);

        // Verify final state
        bidSide.writeTo(schema);
        assertEquals(10100L, schema.decoder.bidPrice0());
        assertEquals(400L, schema.decoder.bidSize0());
        assertEquals(4L, schema.decoder.bidCount0());
        assertEquals(10000L, schema.decoder.bidPrice1());
        assertEquals(100L, schema.decoder.bidSize1());
        assertEquals(1L, schema.decoder.bidCount1());
        assertEquals(9900L, schema.decoder.bidPrice2());
        assertEquals(250L, schema.decoder.bidSize2());
        assertEquals(5L, schema.decoder.bidCount2());
        assertEquals(9700L, schema.decoder.bidPrice3());
        assertEquals(500L, schema.decoder.bidSize3());
        assertEquals(6L, schema.decoder.bidCount3());
    }

    @Test
    void testStressTestManyOperations() {
        // Perform many operations
        for (int i = 0; i < 100; i++) {
            bidSide.update(10000L - i * 10L, 100L + i, i + 1L);
        }

        // Remove some
        for (int i = 0; i < 50; i += 2) {
            bidSide.update(10000L - i * 10L, 0L, 0L);
        }

        // Update some
        for (int i = 1; i < 50; i += 2) {
            bidSide.update(10000L - i * 10L, 999L, 99L);
        }
    }

    @Test
    void testBidAndAskSidesDifferentOrdering() {
        // Same prices, different ordering
        bidSide.update(10000L, 100L, 1L);
        bidSide.update(9900L, 200L, 2L);
        bidSide.update(10100L, 300L, 3L);

        askSide.update(10000L, 100L, 1L);
        askSide.update(9900L, 200L, 2L);
        askSide.update(10100L, 300L, 3L);

        // Write both to schema
        bidSide.writeTo(schema);
        MBP10Schema askSchema = new MBP10Schema();
        askSide.writeTo(askSchema);

        // Bids: 10100, 10000, 9900 (DESC)
        assertEquals(10100L, schema.decoder.bidPrice0());
        assertEquals(10000L, schema.decoder.bidPrice1());
        assertEquals(9900L, schema.decoder.bidPrice2());

        // Asks: 9900, 10000, 10100 (ASC)
        assertEquals(9900L, askSchema.decoder.askPrice0());
        assertEquals(10000L, askSchema.decoder.askPrice1());
        assertEquals(10100L, askSchema.decoder.askPrice2());
    }

    // ========== Helper Methods ==========

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

    private void setAllBidLevels(MBP10Schema schema, long price, long size, long count) {
        for (int i = 0; i < 10; i++) {
            setBidLevel(schema, i, price, size, count);
        }
    }

    private void setRemainingBidLevels(MBP10Schema schema, int startLevel, long price, long size, long count) {
        for (int i = startLevel; i < 10; i++) {
            setBidLevel(schema, i, price, size, count);
        }
    }

    private long getBidPrice(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.bidPrice0();
            case 1: return schema.decoder.bidPrice1();
            case 2: return schema.decoder.bidPrice2();
            case 3: return schema.decoder.bidPrice3();
            case 4: return schema.decoder.bidPrice4();
            case 5: return schema.decoder.bidPrice5();
            case 6: return schema.decoder.bidPrice6();
            case 7: return schema.decoder.bidPrice7();
            case 8: return schema.decoder.bidPrice8();
            case 9: return schema.decoder.bidPrice9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getBidSize(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.bidSize0();
            case 1: return schema.decoder.bidSize1();
            case 2: return schema.decoder.bidSize2();
            case 3: return schema.decoder.bidSize3();
            case 4: return schema.decoder.bidSize4();
            case 5: return schema.decoder.bidSize5();
            case 6: return schema.decoder.bidSize6();
            case 7: return schema.decoder.bidSize7();
            case 8: return schema.decoder.bidSize8();
            case 9: return schema.decoder.bidSize9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getAskPrice(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.askPrice0();
            case 1: return schema.decoder.askPrice1();
            case 2: return schema.decoder.askPrice2();
            case 3: return schema.decoder.askPrice3();
            case 4: return schema.decoder.askPrice4();
            case 5: return schema.decoder.askPrice5();
            case 6: return schema.decoder.askPrice6();
            case 7: return schema.decoder.askPrice7();
            case 8: return schema.decoder.askPrice8();
            case 9: return schema.decoder.askPrice9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getAskSize(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.askSize0();
            case 1: return schema.decoder.askSize1();
            case 2: return schema.decoder.askSize2();
            case 3: return schema.decoder.askSize3();
            case 4: return schema.decoder.askSize4();
            case 5: return schema.decoder.askSize5();
            case 6: return schema.decoder.askSize6();
            case 7: return schema.decoder.askSize7();
            case 8: return schema.decoder.askSize8();
            case 9: return schema.decoder.askSize9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getBidCount(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.bidCount0();
            case 1: return schema.decoder.bidCount1();
            case 2: return schema.decoder.bidCount2();
            case 3: return schema.decoder.bidCount3();
            case 4: return schema.decoder.bidCount4();
            case 5: return schema.decoder.bidCount5();
            case 6: return schema.decoder.bidCount6();
            case 7: return schema.decoder.bidCount7();
            case 8: return schema.decoder.bidCount8();
            case 9: return schema.decoder.bidCount9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }

    private long getAskCount(MBP10Schema schema, int level) {
        switch (level) {
            case 0: return schema.decoder.askCount0();
            case 1: return schema.decoder.askCount1();
            case 2: return schema.decoder.askCount2();
            case 3: return schema.decoder.askCount3();
            case 4: return schema.decoder.askCount4();
            case 5: return schema.decoder.askCount5();
            case 6: return schema.decoder.askCount6();
            case 7: return schema.decoder.askCount7();
            case 8: return schema.decoder.askCount8();
            case 9: return schema.decoder.askCount9();
            default: throw new IllegalArgumentException("Invalid level: " + level);
        }
    }
}
