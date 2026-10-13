package group.gnometrading.gateways.outbound.exchanges.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.strings.ViewString;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolymarketUsStampsTest {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(
            longs = {4_102_444_800_000L, 4_102_444_800_001L, 4_102_444_801_999L, 5_102_444_799_999L, 4_200_000_123_456L
            })
    void writtenText_IsTheIsoInstant_AndParsesBack(final long millis) {
        final String text = PolymarketUsOutboundWriterTest.stampText(millis);

        assertEquals(ISO_MILLIS.format(Instant.ofEpochMilli(millis)), text);
        assertEquals(PolymarketUsStamps.LENGTH, text.length());
        assertEquals(millis, PolymarketUsStamps.parseMillis(new ViewString(text)));
    }

    @Test
    void stampsRunFrom2100To2131() {
        assertEquals(
                "2100-01-01T00:00:00.000Z", PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.BASE_MILLIS));
        assertEquals(
                "2131-09-10T01:46:39.999Z",
                PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(
                        PolymarketUsStamps.SLOTS - 1, PolymarketUsStamps.COUNTER_RANGE - 1)));
    }

    @Test
    void eachCounterAndSlotHasItsOwnStamp() {
        assertEquals(PolymarketUsStamps.stampMillis(7, 1) + 1, PolymarketUsStamps.stampMillis(7, 2));
        assertEquals(
                PolymarketUsStamps.stampMillis(7, 1) + PolymarketUsStamps.COUNTER_RANGE,
                PolymarketUsStamps.stampMillis(8, 1));
    }

    @Test
    void onlyTimesInTheRangeAreStamps() {
        assertTrue(PolymarketUsStamps.isStamp(PolymarketUsStamps.stampMillis(0, 1)));
        assertFalse(PolymarketUsStamps.isStamp(
                Instant.parse("2026-12-31T23:59:59.123Z").toEpochMilli()));
        assertFalse(PolymarketUsStamps.isStamp(PolymarketUsStamps.END_MILLIS));
    }

    @Test
    void slotIsStableAndInRange() {
        final int slot = PolymarketUsStamps.slot("8f2c1d9e-session");
        assertEquals(slot, PolymarketUsStamps.slot("8f2c1d9e-session"));
        assertTrue(slot >= 0 && slot < PolymarketUsStamps.SLOTS);
    }
}
