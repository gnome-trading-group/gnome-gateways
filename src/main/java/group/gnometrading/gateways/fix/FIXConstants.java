package group.gnometrading.gateways.fix;

import java.nio.ByteBuffer;

public class FIXConstants {
    public static final int CHECKSUM_LENGTH = 7; // 35=123|
    public static final byte EQUALS = '=';
    public static final byte SOH = 1;
    public static final byte YES = 'Y';
    public static final byte NO = 'N';
    public static final short BEGIN_STRING_SHORT = '8' << 8 | '=';
    public static final short BODY_LENGTH_SHORT = '9' << 8 | '=';
    public static final byte[] BEGIN_STRING_BYTES = { '8', '=' };
    public static final byte[] BODY_LENGTH_BYTES = { '9', '=' };
    public static final byte[] CHECK_SUM_BYTES = { '1', '0', '=' };

    static long sum(final ByteBuffer buffer, final int offset, final int length) {
        long sum = 0;
        for (int i = offset; i < offset + length; i++)
            sum += buffer.get(i);
        return sum;
    }
}
