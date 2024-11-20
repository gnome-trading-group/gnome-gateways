package group.gnometrading.gateways.codecs;

import group.gnometrading.gateways.fix.FIXMessage;

import java.nio.ByteBuffer;

public class FIXDecoder implements Decoder<FIXMessage> {

    @Override
    public boolean decode(final ByteBuffer source, final FIXMessage output) {
        return output.parseBuffer(source);
    }

}
