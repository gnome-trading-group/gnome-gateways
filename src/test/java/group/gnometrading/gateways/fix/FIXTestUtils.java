package group.gnometrading.gateways.fix;

import java.nio.ByteBuffer;

public class FIXTestUtils {
    static ByteBuffer fix(String input) {
        input = input.replace('|', (char) ((byte) 0x1));
        return ByteBuffer.wrap(input.getBytes());
    }
}
