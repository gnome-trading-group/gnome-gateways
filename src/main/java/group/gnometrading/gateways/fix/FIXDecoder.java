package group.gnometrading.gateways.fix;

import group.gnometrading.gateways.codecs.Decoder;

import java.nio.ByteBuffer;

public class FIXDecoder implements Decoder<FIXMessage> {

    @Override
    public boolean decode(final ByteBuffer source, final FIXMessage output) {
        return output.parseBuffer(source);
    }

}
