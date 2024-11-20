package group.gnometrading.gateways.fix;

public class FIXTimestampUtils {

    private static final int[] DAYS_IN_MONTH = {
            31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
    };

    /**
     * Put a UTC timestamp into a buffer.
     * @return the number of bytes written
     */
    public static int putTimestamp(final long epochMillis, final FIXTimestampPrecision precision, final byte[] buffer, final int offset) {
        if (precision == FIXTimestampPrecision.MICROSECONDS) {
            throw new IllegalArgumentException("Microseconds are not supported yet");
        }

        final long epochSeconds = epochMillis / 1_000;
        final int millis = (int) (epochMillis % 1_000);

        final long daysSinceEpoch = epochSeconds / 86_400;
        final int secondsOfDay = (int) (epochSeconds % 86_400);

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

        // Compute month and day
        int month = 0;
        int dayOfMonth = dayOfYear + 1; // 1-based day of the month
        final boolean leapYear = isLeapYear(year);
        while (dayOfMonth > ((month == 1 && leapYear) ? 29: DAYS_IN_MONTH[month])) {
            dayOfMonth -= ((month == 1 && leapYear) ? 29: DAYS_IN_MONTH[month]);
            month++;
        }

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
        if (precision == FIXTimestampPrecision.MILLISECONDS) {
            buffer[pos++] = '.';
            pos = appendPadded(buffer, pos, millis, 3); // sss
        }
        return pos - offset;
    }

    private static int appendPadded(final byte[] buffer, final int pos, int value, final int digits) {
        int start = pos + digits - 1;
        for (int i = 0; i < digits; i++) {
            buffer[start--] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        return pos + digits;
    }

    private static boolean isLeapYear(final int year) {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    }
}
