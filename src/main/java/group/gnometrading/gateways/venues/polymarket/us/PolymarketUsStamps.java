package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.gateways.Rfc3339;
import group.gnometrading.strings.GnomeString;

/**
 * Polymarket US takes no client order id, but echoes a good-till-date order's {@code goodTillTime} to the
 * millisecond on every report of it. A resting order is therefore sent as good-till-date with an expiry far past any
 * market's settlement, whose milliseconds name the order: a session's slot and the OMS's counter. The canonical text
 * of that time is the order's correlation id and the id the ledger knows it by.
 *
 * <p>The venue normalises the text it echoes (it drops a {@code .000} fraction), so stamps are compared as times,
 * never as text. Stamps run from 2100 to 2131: the venue accepts them, and they stay inside the nanosecond range
 * {@link Rfc3339} parses.
 */
public final class PolymarketUsStamps {

    /** 2100-01-01T00:00:00Z. */
    static final long BASE_MILLIS = 4_102_444_800_000L;
    // Orders a session can send before its stamps wrap; the counter starts at 1 each session.
    static final long COUNTER_RANGE = 10_000_000L;
    // Two sessions trading one market share a stamp only if their slots collide: 1 in this many.
    static final int SLOTS = 100_000;
    static final long END_MILLIS = BASE_MILLIS + SLOTS * COUNTER_RANGE;

    /** {@code YYYY-MM-DDTHH:MM:SS.mmmZ}. */
    public static final int LENGTH = 24;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private PolymarketUsStamps() {}

    /** A session's slot, from the tag that names it. */
    public static int slot(final String sessionTag) {
        return Math.floorMod(sessionTag.hashCode(), SLOTS);
    }

    /** The stamp of the {@code counter}th order of the session in {@code slot}, in epoch millis. */
    static long stampMillis(final int slot, final long counter) {
        return BASE_MILLIS + slot * COUNTER_RANGE + Math.floorMod(counter, COUNTER_RANGE);
    }

    /** Whether a time can be one of our stamps. */
    static boolean isStamp(final long epochMillis) {
        return epochMillis >= BASE_MILLIS && epochMillis < END_MILLIS;
    }

    /** A {@code goodTillTime} as epoch millis, or {@link Rfc3339#INVALID} if it isn't a time. */
    static long parseMillis(final GnomeString text) {
        final long nanos = Rfc3339.toEpochNanos(text);
        return nanos == Rfc3339.INVALID ? Rfc3339.INVALID : nanos / NANOS_PER_MILLI;
    }

    /** Writes the canonical text of {@code epochMillis} at {@code offset}; returns {@link #LENGTH}. */
    static int write(final long epochMillis, final byte[] dest, final int offset) {
        final long days = Math.floorDiv(epochMillis, MILLIS_PER_DAY);
        final long millisOfDay = Math.floorMod(epochMillis, MILLIS_PER_DAY);
        // Howard Hinnant's civil_from_days.
        final long z = days + 719_468;
        final long era = Math.floorDiv(z, 146_097);
        final long doe = z - era * 146_097;
        final long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        final long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        final long mp = (5 * doy + 2) / 153;
        final long day = doy - (153 * mp + 2) / 5 + 1;
        final long month = mp < 10 ? mp + 3 : mp - 9;
        final long year = yoe + era * 400 + (month <= 2 ? 1 : 0);

        final long seconds = millisOfDay / MILLIS_PER_SECOND;
        int pos = offset;
        pos = digits(dest, pos, year, 4);
        dest[pos++] = '-';
        pos = digits(dest, pos, month, 2);
        dest[pos++] = '-';
        pos = digits(dest, pos, day, 2);
        dest[pos++] = 'T';
        pos = digits(dest, pos, seconds / 3600, 2);
        dest[pos++] = ':';
        pos = digits(dest, pos, seconds / 60 % 60, 2);
        dest[pos++] = ':';
        pos = digits(dest, pos, seconds % 60, 2);
        dest[pos++] = '.';
        pos = digits(dest, pos, millisOfDay % MILLIS_PER_SECOND, 3);
        dest[pos++] = 'Z';
        return pos - offset;
    }

    private static int digits(final byte[] dest, final int offset, final long value, final int width) {
        long remaining = value;
        for (int i = width - 1; i >= 0; i--) {
            dest[offset + i] = (byte) ('0' + remaining % 10);
            remaining /= 10;
        }
        return offset + width;
    }
}
