package group.gnometrading.gateways.codecs;

import java.nio.ByteBuffer;

public class JSONDecoder implements Decoder<group.gnometrading.codecs.json.JSONDecoder> {
    @Override
    public boolean decode(ByteBuffer source, group.gnometrading.codecs.json.JSONDecoder output) {
        output.wrap(source);
        return false;
    }
}
