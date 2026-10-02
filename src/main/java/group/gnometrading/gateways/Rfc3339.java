package group.gnometrading.gateways;

import group.gnometrading.strings.GnomeString;

/**
 * Allocation-free parser for RFC 3339 timestamps such as {@code 2026-10-02T14:22:56.542213551Z}
 * or {@code 2026-10-02T09:22:56-05:00}.
 */
public final class Rfc3339 {

    public static final long INVALID = Long.MIN_VALUE;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long SECONDS_PER_DAY = 86_400L;
    private static final int MAX_FRACTION_DIGITS = 9;
    private static final int SECONDS_END = 19;
    private static final int OFFSET_LENGTH = 6;

    private Rfc3339() {}

    /**
     * Returns epoch nanoseconds, or {@link #INVALID} when the text is not an RFC 3339 timestamp.
     * Fractions beyond nanosecond precision are truncated.
     */
    public static long toEpochNanos(final GnomeString text) {
        if (!hasDateTimeShape(text)) {
            return INVALID;
        }
        final int month = digits(text, 5, 2);
        final int day = digits(text, 8, 2);
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return INVALID;
        }
        final long seconds = daysFromCivil(digits(text, 0, 4), month, day) * SECONDS_PER_DAY
                + digits(text, 11, 2) * 3600L
                + digits(text, 14, 2) * 60L
                + digits(text, 17, 2);

        final int zoneStart = fractionEnd(text);
        if (zoneStart < 0) {
            return INVALID;
        }
        final long offsetSeconds = offsetSeconds(text, zoneStart);
        if (offsetSeconds == INVALID) {
            return INVALID;
        }
        return (seconds - offsetSeconds) * NANOS_PER_SECOND + fractionNanos(text, zoneStart);
    }

    private static boolean hasDateTimeShape(final GnomeString text) {
        return text.length() > SECONDS_END && hasSeparators(text) && hasDateTimeDigits(text);
    }

    private static boolean hasSeparators(final GnomeString text) {
        final byte separator = text.byteAt(10);
        return text.byteAt(4) == '-'
                && text.byteAt(7) == '-'
                && (separator == 'T' || separator == 't' || separator == ' ')
                && text.byteAt(13) == ':'
                && text.byteAt(16) == ':';
    }

    private static boolean hasDateTimeDigits(final GnomeString text) {
        return digits(text, 0, 4) >= 0
                && digits(text, 11, 2) >= 0
                && digits(text, 14, 2) >= 0
                && digits(text, 17, 2) >= 0;
    }

    /** Returns the index just past the optional fraction, or -1 for a fraction with no digits. */
    private static int fractionEnd(final GnomeString text) {
        if (text.byteAt(SECONDS_END) != '.') {
            return SECONDS_END;
        }
        int pos = SECONDS_END + 1;
        while (pos < text.length() && isDigit(text.byteAt(pos))) {
            pos++;
        }
        return pos == SECONDS_END + 1 ? -1 : pos;
    }

    private static long fractionNanos(final GnomeString text, final int fractionEnd) {
        long nanos = 0;
        int digitCount = 0;
        for (int pos = SECONDS_END + 1; pos < fractionEnd && digitCount < MAX_FRACTION_DIGITS; pos++) {
            nanos = nanos * 10 + (text.byteAt(pos) - '0');
            digitCount++;
        }
        for (; digitCount < MAX_FRACTION_DIGITS; digitCount++) {
            nanos *= 10;
        }
        return nanos;
    }

    /** Returns the UTC offset in seconds, or {@link #INVALID} if the zone is malformed or has trailing text. */
    private static long offsetSeconds(final GnomeString text, final int zoneStart) {
        final int len = text.length();
        if (zoneStart >= len) {
            return INVALID;
        }
        final byte zone = text.byteAt(zoneStart);
        if (zone == 'Z' || zone == 'z') {
            return zoneStart + 1 == len ? 0 : INVALID;
        }
        if (zone != '+' && zone != '-') {
            return INVALID;
        }
        return numericOffsetSeconds(text, zoneStart, zone == '+');
    }

    private static long numericOffsetSeconds(final GnomeString text, final int zoneStart, final boolean positive) {
        if (zoneStart + OFFSET_LENGTH != text.length() || text.byteAt(zoneStart + 3) != ':') {
            return INVALID;
        }
        final int hours = digits(text, zoneStart + 1, 2);
        final int minutes = digits(text, zoneStart + 4, 2);
        if (hours < 0 || minutes < 0) {
            return INVALID;
        }
        final long magnitude = hours * 3600L + minutes * 60L;
        return positive ? magnitude : -magnitude;
    }

    private static boolean isDigit(final byte value) {
        return value >= '0' && value <= '9';
    }

    private static int digits(final GnomeString text, final int offset, final int count) {
        int value = 0;
        for (int i = offset; i < offset + count; i++) {
            final byte at = text.byteAt(i);
            if (!isDigit(at)) {
                return -1;
            }
            value = value * 10 + (at - '0');
        }
        return value;
    }

    // Howard Hinnant's days_from_civil: proleptic Gregorian date to days since 1970-01-01.
    private static long daysFromCivil(final int year, final int month, final int day) {
        final int y = month <= 2 ? year - 1 : year;
        final int era = Math.floorDiv(y, 400);
        final int yearOfEra = y - era * 400;
        final int monthIndex = month > 2 ? month - 3 : month + 9;
        final int dayOfYear = (153 * monthIndex + 2) / 5 + day - 1;
        final int dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146_097L + dayOfEra - 719_468L;
    }
}
