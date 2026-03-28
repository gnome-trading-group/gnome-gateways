package group.gnometrading.gateways.fix;

public final class FixTimestampUtils {

    private FixTimestampUtils() {}

    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    /**
     * Put a UTC timestamp into a buffer.
     * @return the number of bytes written
     */
    public static int putTimestamp(
            final long epochMillis, final FixTimestampPrecision precision, final byte[] buffer, final int offset) {
        if (precision == FixTimestampPrecision.MICROSECONDS) {
            throw new IllegalArgumentException("Microseconds are not supported yet");
        }

        final long epochSeconds = epochMillis / 1_000;
        final int millis = (int) (epochMillis % 1_000);

        final long daysSinceEpoch = epochSeconds / 86_400;
        final int secondsOfDay = (int) (epochSeconds % 86_400);

        final int[] yearDay = computeYearAndDay(daysSinceEpoch);
        final int year = yearDay[0];
        final int dayOfYear = yearDay[1];

        final int[] monthDay = computeMonthAndDay(dayOfYear, year);
        final int month = monthDay[0];
        final int dayOfMonth = monthDay[1];

        final int hour = secondsOfDay / 3_600;
        final int minute = (secondsOfDay % 3_600) / 60;
        final int second = secondsOfDay % 60;

        int pos = offset;
        pos = appendPadded(buffer, pos, year, 4); // YYYY
        pos = appendPadded(buffer, pos, month + 1, 2); // MM
        pos = appendPadded(buffer, pos, dayOfMonth, 2); // DD
        buffer[pos++] = '-';
        pos = appendPadded(buffer, pos, hour, 2); // HH
        buffer[pos++] = ':';
        pos = appendPadded(buffer, pos, minute, 2); // mm
        buffer[pos++] = ':';
        pos = appendPadded(buffer, pos, second, 2); // SS
        if (precision == FixTimestampPrecision.MILLISECONDS) {
            buffer[pos++] = '.';
            pos = appendPadded(buffer, pos, millis, 3); // sss
        }
        return pos - offset;
    }

    private static int[] computeYearAndDay(final long daysSinceEpoch) {
        int year = 1970;
        int dayOfYear = (int) daysSinceEpoch;
        while (dayOfYear < 0 || dayOfYear >= (isLeapYear(year) ? 366 : 365)) {
            int daysInYear = isLeapYear(year) ? 366 : 365;
            if (dayOfYear < 0) {
                year--;
                dayOfYear += daysInYear;
            } else {
                dayOfYear -= daysInYear;
                year++;
            }
        }
        return new int[] {year, dayOfYear};
    }

    private static int[] computeMonthAndDay(final int dayOfYear, final int year) {
        int month = 0;
        int dayOfMonth = dayOfYear + 1;
        final boolean leapYear = isLeapYear(year);
        while (dayOfMonth > ((month == 1 && leapYear) ? 29 : DAYS_IN_MONTH[month])) {
            dayOfMonth -= ((month == 1 && leapYear) ? 29 : DAYS_IN_MONTH[month]);
            month++;
        }
        return new int[] {month, dayOfMonth};
    }

    private static int appendPadded(final byte[] buffer, final int pos, final int value, final int digits) {
        int remaining = value;
        int start = pos + digits - 1;
        for (int i = 0; i < digits; i++) {
            buffer[start--] = (byte) ('0' + (remaining % 10));
            remaining /= 10;
        }
        return pos + digits;
    }

    private static boolean isLeapYear(final int year) {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    }
}
