package group.gnometrading.gateways.fix;

import java.nio.ByteBuffer;

public class FixTestUtils {
    static ByteBuffer fix(String input) {
        input = input.replace('|', (char) ((byte) 0x1));
        return ByteBuffer.wrap(input.getBytes());
    }
}
