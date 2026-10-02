package group.gnometrading.gateways.fix;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.strings.ExpandingMutableString;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ArrayCopy;
import group.gnometrading.utils.AsciiEncoding;
import java.nio.ByteBuffer;

public final class FixValue {

    private int length;
    private int offset;
    private ByteBuffer parent;
    private final FixTimestamp timestamp;
    private final ExpandingMutableString string;
    private final byte[] writeBuffer;

    public FixValue(final int writeBufferCapacity) {
        this.length = -1;
        this.offset = -1;
        this.parent = null;
        this.timestamp = new FixTimestamp();
        this.string = new ExpandingMutableString();
        this.writeBuffer = new byte[writeBufferCapacity];
    }

    public int getLength() {
        return this.length;
    }

    @VisibleForTesting
    public int getOffset() {
        return this.offset;
    }

    public void set(final FixValue other) {
        offset = other.offset;
        length = other.length;

        ArrayCopy.arraycopy(other.writeBuffer, offset, this.writeBuffer, offset, length + 1); // SOH char at end
    }

    public boolean asBoolean() {
        return parent.get(this.offset) == FixConstants.YES;
    }

    public void setBoolean(final boolean value) {
        this.writeBuffer[0] = value ? FixConstants.YES : FixConstants.NO;
        this.writeBuffer[1] = FixConstants.SOH;

        offset = 0;
        length = 1;
    }

    public char asChar() {
        return (char) parent.get(this.offset);
    }

    public void setChar(final char value) {
        this.writeBuffer[0] = (byte) value;
        this.writeBuffer[1] = FixConstants.SOH;

        offset = 0;
        length = 1;
    }

    public FixTimestamp asDate() {
        timestamp.parseDateBuffer(this.parent, this.offset, this.length);
        return timestamp;
    }

    public FixTimestamp asTime(final FixTimestampPrecision precision) {
        timestamp.parseTimeBuffer(this.parent, this.offset, this.length, precision);
        return timestamp;
    }

    public FixTimestamp asTimestamp(final FixTimestampPrecision precision) {
        timestamp.parseTimestampBuffer(this.parent, this.offset, this.length, precision);
        return timestamp;
    }

    public void setTimestamp(final long millis, final FixTimestampPrecision precision) {
        offset = 0;
        length = FixTimestampUtils.putTimestamp(millis, precision, this.writeBuffer, 0);
    }

    public GnomeString asString() {
        this.string.reset();
        for (int i = offset; i < offset + length; i++) {
            this.string.append(this.parent.get(i));
        }
        return this.string;
    }

    public void setString(final String value) {
        offset = 0;
        length = value.length();
        for (int i = 0; i < value.length(); i++) {
            this.writeBuffer[i] = (byte) value.charAt(i);
        }
        this.writeBuffer[length] = FixConstants.SOH;
    }

    public void setString(final GnomeString value) {
        offset = 0;
        length = value.length();
        for (int i = 0; i < value.length(); i++) {
            this.writeBuffer[i] = value.byteAt(i);
        }
        this.writeBuffer[length] = FixConstants.SOH;
    }

    public int asInt() {
        boolean negative = false;
        int idx = offset;
        if (this.parent.get(idx) == '-') {
            negative = true;
            idx++;
        }

        int value = 0;
        while (idx < offset + length) {
            final byte byteVal = this.parent.get(idx++);
            value = 10 * value + byteVal - '0';
        }

        return negative ? -value : +value;
    }

    public void setInt(final int value) {
        if (value < 0) {
            setNegativeInt(value);
            return;
        }
        int remaining = value;
        int idx = this.writeBuffer.length;

        this.writeBuffer[--idx] = FixConstants.SOH;

        do {
            this.writeBuffer[--idx] = (byte) ('0' + remaining % 10);

            remaining /= 10;
        } while (remaining > 0);

        offset = idx;
        length = this.writeBuffer.length - offset - 1;
    }

    private void setNegativeInt(final int value) {
        int remaining = value;
        int idx = this.writeBuffer.length;
        this.writeBuffer[--idx] = FixConstants.SOH;

        do {
            this.writeBuffer[--idx] = (byte) ('0' - remaining % 10);

            remaining /= 10;
        } while (remaining < 0);

        this.writeBuffer[--idx] = '-';

        offset = idx;
        length = this.writeBuffer.length - offset - 1;
    }

    public long asLong() {
        boolean negative = false;
        int idx = offset;
        if (this.parent.get(idx) == '-') {
            negative = true;
            idx++;
        }

        long value = 0;
        while (idx < offset + length) {
            final byte byteVal = this.parent.get(idx++);
            value = 10 * value + byteVal - '0';
        }

        return negative ? -value : +value;
    }

    /**
     * Get the value as a fixed-point long, i.e. the decimal value multiplied by {@code scale}.
     *
     * <p>{@code scale} must be a power of ten. Fraction digits beyond its precision are truncated
     * toward zero rather than rounded. A leading {@code '-'} is honoured.</p>
     *
     * <p>Fraction digits are scaled up to {@code scale} rather than the whole significand being
     * multiplied by it, so the result only overflows if the scaled value itself exceeds a long.</p>
     *
     * @param scale the fixed-point scaling factor, a power of ten
     * @return the value multiplied by {@code scale}
     */
    public long toFixedPointLong(final long scale) {
        int scaleDigits = 0;
        while (AsciiEncoding.LONG_POW_10[scaleDigits] < scale) {
            scaleDigits++;
        }

        int idx = offset;
        final int end = offset + length;
        boolean negative = false;
        if (idx < end && this.parent.get(idx) == '-') {
            negative = true;
            idx++;
        }

        long significand = 0;
        int decimalPlaces = 0;
        boolean seenDecimal = false;
        while (idx < end) {
            final byte byteVal = this.parent.get(idx++);
            if (byteVal == '.') {
                seenDecimal = true;
            } else if (!seenDecimal) {
                significand = 10 * significand + byteVal - '0';
            } else if (decimalPlaces < scaleDigits) {
                significand = 10 * significand + byteVal - '0';
                decimalPlaces++;
            }
        }

        final long value = significand * AsciiEncoding.LONG_POW_10[scaleDigits - decimalPlaces];
        return negative ? -value : value;
    }

    public double asDecimal() {
        boolean negative = false;
        int idx = offset;
        if (this.parent.get(idx) == '-') {
            negative = true;
            idx++;
        }

        long significand = 0;
        while (idx < offset + length) {
            final byte byteVal = this.parent.get(idx++);
            if (byteVal == '.') {
                break;
            }
            significand = 10 * significand + byteVal - '0';
        }

        int exponent = 0;
        while (idx < offset + length) {
            final byte byteVal = this.parent.get(idx++);
            significand = 10 * significand + byteVal - '0';
            exponent++;
        }
        double result = significand / (double) AsciiEncoding.LONG_POW_10[exponent];

        return negative ? -result : +result;
    }

    public void setDecimal(final double value, final int scale) {
        if (value < 0.0) {
            setNegativeDecimal(value, scale);
            return;
        }

        int idx = this.writeBuffer.length;
        this.writeBuffer[--idx] = FixConstants.SOH;

        long scaled = Math.round(AsciiEncoding.LONG_POW_10[scale] * value);

        for (int j = 0; j < scale; j++) {
            this.writeBuffer[--idx] = (byte) ('0' + scaled % 10);
            scaled /= 10;
        }

        if (scale > 0) {
            this.writeBuffer[--idx] = '.';
        }

        do {
            this.writeBuffer[--idx] = (byte) ('0' + scaled % 10);
            scaled /= 10;
        } while (scaled > 0);

        offset = idx;
        length = this.writeBuffer.length - offset - 1;
    }

    private void setNegativeDecimal(final double value, final int scale) {
        int idx = this.writeBuffer.length;

        this.writeBuffer[--idx] = FixConstants.SOH;

        long scaled = Math.round(AsciiEncoding.LONG_POW_10[scale] * -value);
        for (int j = 0; j < scale; j++) {
            this.writeBuffer[--idx] = (byte) ('0' + scaled % 10);
            scaled /= 10;
        }

        if (scale > 0) {
            this.writeBuffer[--idx] = '.';
        }

        do {
            this.writeBuffer[--idx] = (byte) ('0' + scaled % 10);
            scaled /= 10;
        } while (scaled > 0);

        this.writeBuffer[--idx] = '-';

        offset = idx;
        length = this.writeBuffer.length - offset - 1;
    }

    public void setCheckSum(final long value) {
        int mod = (int) (value & 0xff);
        int digits = 3;
        while (digits-- > 0) {
            this.writeBuffer[digits] = (byte) ('0' + mod % 10);

            mod /= 10;
        }
        this.writeBuffer[3] = FixConstants.SOH;

        offset = 0;
        length = 3;
    }

    public void setByteBuffer(final ByteBuffer other) {
        offset = 0;
        length = 0;
        while (other.hasRemaining()) {
            this.writeBuffer[length++] = other.get();
        }
    }

    public void copyTo(final ByteBuffer output) {
        for (int i = offset; i < offset + length; i++) {
            output.put(this.writeBuffer[i]);
        }
    }

    public boolean parseBuffer(final ByteBuffer buffer) {
        this.offset = buffer.position();
        this.length = 0;
        this.parent = buffer;
        while (buffer.hasRemaining()) {
            final byte at = buffer.get();
            if (at == FixConstants.SOH) {
                return true;
            }
            this.length++;
        }
        return false;
    }

    public void writeToBuffer(final ByteBuffer output) {
        this.copyTo(output);
        output.put(FixConstants.SOH);
    }

    @Override
    public String toString() {
        if (length == -1) {
            return null;
        }

        ByteBuffer out = ByteBuffer.allocate(length);
        this.copyTo(out);
        return new String(out.array());
    }
}
