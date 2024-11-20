package group.gnometrading.gateways.fix;

import group.gnometrading.utils.Resettable;

import java.nio.ByteBuffer;

public class FIXTimestamp implements Resettable {

    private int year = -1;
    private int month = -1;
    private int day = -1;
    private int hour = -1;
    private int minute = -1;
    private int second = -1;
    private int fraction = -1;
    private FIXTimestampPrecision precision;
    private boolean dateOnly = false;
    private boolean timeOnly = false;
    
    public void parseTimeBuffer(final ByteBuffer buffer, final int offset, final int length, final FIXTimestampPrecision precision) {
        this.precision = precision;
        reset();
        this.timeOnly = true;
        
        parseTimeInternal(buffer, offset, length, precision);
    }
    
    public void parseDateBuffer(final ByteBuffer buffer, final int offset, final int length) {
        reset();
        this.dateOnly = true;

        parseDateInternal(buffer, offset);
    }

    public void parseTimestampBuffer(final ByteBuffer buffer, final int offset, final int length, final FIXTimestampPrecision precision) {
        this.precision = precision;
        reset();

        // YYYYMMDD-HH:MM:ss
        // 0123456789
        parseDateInternal(buffer, offset);
        parseTimeInternal(buffer, offset + 9, length - 9, precision);
    }

    private void parseDateInternal(final ByteBuffer buffer, final int offset) {
        this.year = getDigits(buffer, 4, offset);
        this.month = getDigits(buffer, 2, offset + 4);
        this.day = getDigits(buffer, 2, offset + 6);
    }

    private void parseTimeInternal(final ByteBuffer buffer, int offset, int length, final FIXTimestampPrecision precision) {
        // HH:MM:ss[.sss][sss]
        // 01234567 8901  234
        this.hour = getDigits(buffer, 2, offset);
        this.minute = getDigits(buffer, 2, offset + 3);
        this.second = getDigits(buffer, 2, offset + 6);

        if (precision == FIXTimestampPrecision.SECONDS) return;

        // HH:MM:SS.sss[sss] == length of 21 [24]
        if (precision == FIXTimestampPrecision.MILLISECONDS) {
            this.fraction = length >= 12 ? getDigits(buffer, 3, offset + 9) : 0;
        } else { // micros
            this.fraction = length >= 15 ? getDigits(buffer, 6, offset + 9) : 0;
        }
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public int getDay() {
        return day;
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getFraction() {
        return fraction;
    }

    private int getDigits(final ByteBuffer buffer, int digits, int offset) {
        int value = 0;

        for (int i = offset; i < offset + digits; i++) {
            final byte b = buffer.get(i);
            value = 10 * value + b - '0';
        }

        return value;
    }

    private String formatTime() {
       String format = "%02d:%02d:%02d";

       if (precision == FIXTimestampPrecision.SECONDS) {
           return String.format(format, this.hour, this.minute, this.second);
       }

       format += precision == FIXTimestampPrecision.MILLISECONDS ? ".%03d" : ".%06d";
       return String.format(format, this.hour, this.minute, this.second, this.fraction);
    }

    private String formatDate() {
        return String.format("%04d%02d%02d", this.year, this.month, this.day);
    }

    @Override
    public String toString() {
        if (precision == null && !dateOnly) {
            return null; // Nothing has been parsed
        }

        if (this.timeOnly) {
            return formatTime();
        } else if (this.dateOnly) {
            return formatDate();
        }

        return formatDate() + "-" + formatTime();
    }

    @Override
    public void reset() {
        this.year = this.month = this.day = this.hour = this.minute = this.second = this.fraction = -1;
        this.dateOnly = this.timeOnly = false;
    }
}
