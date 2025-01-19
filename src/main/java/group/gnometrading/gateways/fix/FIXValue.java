package group.gnometrading.gateways.fix;

import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.strings.ExpandingMutableString;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.ArrayCopy;
import group.gnometrading.utils.AsciiEncoding;

import java.nio.ByteBuffer;

public class FIXValue {

    private int length;
    private int offset;
    private ByteBuffer parent;
    private final FIXTimestamp timestamp;
    private final ExpandingMutableString string;
    private final byte[] writeBuffer;

    public FIXValue(final int writeBufferCapacity) {
        this.length = -1;
        this.offset = -1;
        this.parent = null;
        this.timestamp = new FIXTimestamp();
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

    public void set(final FIXValue other) {
        offset = other.offset;
        length = other.length;

        ArrayCopy.arraycopy(other.writeBuffer, offset, this.writeBuffer, offset, length + 1); // SOH char at end
    }

    public boolean asBoolean() {
        return parent.get(this.offset) == FIXConstants.YES;
    }

    public void setBoolean(final boolean value) {
        this.writeBuffer[0] = value ? FIXConstants.YES : FIXConstants.NO;
        this.writeBuffer[1] = FIXConstants.SOH;

        offset = 0;
        length = 1;
    }

    public char asChar() {
        return (char) parent.get(this.offset);
    }

    public void setChar(final char value) {
        this.writeBuffer[0] = (byte) value;
        this.writeBuffer[1] = FIXConstants.SOH;

        offset = 0;
        length = 1;
    }

    public FIXTimestamp asDate() {
        timestamp.parseDateBuffer(this.parent, this.offset, this.length);
        return timestamp;
    }

    public FIXTimestamp asTime(final FIXTimestampPrecision precision) {
        timestamp.parseTimeBuffer(this.parent, this.offset, this.length, precision);
        return timestamp;
    }

    public FIXTimestamp asTimestamp(final FIXTimestampPrecision precision) {
        timestamp.parseTimestampBuffer(this.parent, this.offset, this.length, precision);
        return timestamp;
    }

    public void setTimestamp(final long millis, final FIXTimestampPrecision precision) {
        offset = 0;
        length = FIXTimestampUtils.putTimestamp(millis, precision, this.writeBuffer, 0);
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
        this.writeBuffer[length] = FIXConstants.SOH;
    }

    public void setString(final GnomeString value) {
        offset = 0;
        length = value.length();
        for (int i = 0; i < value.length(); i++) {
            this.writeBuffer[i] = value.byteAt(i);
        }
        this.writeBuffer[length] = FIXConstants.SOH;
    }

    public int asInt() {
        boolean negative = false;
        int i = offset;
        if (this.parent.get(i) == '-') {
            negative = true;
            i++;
        }

        int value = 0;
        while (i < offset + length) {
            final byte b = this.parent.get(i++);
            value = 10 * value + b - '0';
        }

        return negative ? -value : +value;
    }

    public void setInt(int value) {
        if (value < 0) {
            setNegativeInt(value);
            return;
        }
        int i = this.writeBuffer.length;

        this.writeBuffer[--i] = FIXConstants.SOH;

        do {
            this.writeBuffer[--i] = (byte)('0' + value % 10);

            value /= 10;
        } while (value > 0);

        offset = i;
        length = this.writeBuffer.length - offset - 1;
    }

    private void setNegativeInt(int value) {
        int i = this.writeBuffer.length;
        this.writeBuffer[--i] = FIXConstants.SOH;

        do {
            this.writeBuffer[--i] = (byte)('0' - value % 10);

            value /= 10;
        } while (value < 0);

        this.writeBuffer[--i] = '-';

        offset = i;
        length = this.writeBuffer.length - offset - 1;
    }

    public long asLong() {
        boolean negative = false;
        int i = offset;
        if (this.parent.get(i) == '-') {
            negative = true;
            i++;
        }

        long value = 0;
        while (i < offset + length) {
            final byte b = this.parent.get(i++);
            value = 10 * value + b - '0';
        }

        return negative ? -value : +value;
    }

    /**
     * Get the value as a float.
     *
     * <p><strong>Note.</strong> The value is a string representation of
     * a decimal number. As converting an arbitrary decimal number into a
     * floating point number requires arbitrary-precision arithmetic, this
     * method only works with the subset of decimal numbers that can be
     * converted into floating point numbers using floating-point
     * arithmetic.</p>
     *
     * <p>If we represent a decimal number in the form
     *
     *     &plusmn;<i>s</i>&nbsp;&times;&nbsp;10<sup><i>e</i></sup>,
     *
     * where <i>s</i> is an integer significand and <i>e</i> is an integer
     * exponent, this method works for decimal numbers having
     *
     *     0&nbsp;&le;&nbsp;<i>s</i>&nbsp;&le;&nbsp;2<sup>53</sup>&nbsp;-&nbsp;1
     *
     * and
     *
     *     -17&nbsp;&le;&nbsp;<i>e</i>&nbsp;&le;&nbsp;2.</p>
     *
     * @return the value as a decimal
     * @see <a href="https://www.exploringbinary.com/fast-path-decimal-to-floating-point-conversion/">Fast Path Decimal to Floating-Point Conversion</a>
     */
    public double asDecimal() {
        boolean negative = false;
        int i = offset;
        if (this.parent.get(i) == '-') {
            negative = true;
            i++;
        }

        long significand = 0;
        while (i < offset + length) {
            final byte b = this.parent.get(i++);
            if (b == '.') break;
            significand = 10 * significand + b - '0';
        }

        int exponent = 0;
        while (i < offset + length) {
            final byte b = this.parent.get(i++);
            significand = 10 * significand + b - '0';
            exponent++;
        }
        double x = significand / (double) AsciiEncoding.LONG_POW_10[exponent];

        return negative ? -x : +x;
    }

    public void setDecimal(double x, int scale) {
        if (x < 0.0) {
            setNegativeDecimal(x, scale);
            return;
        }

        int i = this.writeBuffer.length;
        this.writeBuffer[--i] = FIXConstants.SOH;

        long y = Math.round(AsciiEncoding.LONG_POW_10[scale] * x);

        for (int j = 0; j < scale; j++) {
            this.writeBuffer[--i] = (byte)('0' + y % 10);
            y /= 10;
        }

        if (scale > 0)
            this.writeBuffer[--i] = '.';

        do {
            this.writeBuffer[--i] = (byte)('0' + y % 10);
            y /= 10;
        } while (y > 0);

        offset = i;
        length = this.writeBuffer.length - offset - 1;
    }

    private void setNegativeDecimal(double x, int scale) {
        int i = this.writeBuffer.length;

        this.writeBuffer[--i] = FIXConstants.SOH;

        long y = Math.round(AsciiEncoding.LONG_POW_10[scale] * -x);
        for (int j = 0; j < scale; j++) {
            this.writeBuffer[--i] = (byte)('0' + y % 10);
            y /= 10;
        }

        if (scale > 0)
            this.writeBuffer[--i] = '.';

        do {
            this.writeBuffer[--i] = (byte)('0' + y % 10);
            y /= 10;
        } while (y > 0);

        this.writeBuffer[--i] = '-';

        offset = i;
        length = this.writeBuffer.length - offset - 1;
    }

    public void setCheckSum(final long value) {
        int mod = (int) (value & 0xff);
        int digits = 3;
        while (digits-- > 0) {
            this.writeBuffer[digits] = (byte)('0' + mod % 10);

            mod /= 10;
        }
        this.writeBuffer[3] = FIXConstants.SOH;

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
            if (at == FIXConstants.SOH) {
                return true;
            }
            this.length++;
        }
        return false;
    }

    public void writeToBuffer(final ByteBuffer output) {
        this.copyTo(output);
        output.put(FIXConstants.SOH);
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
