package group.gnometrading.gateways.codecs;

import java.nio.ByteBuffer;

public interface Decoder<T> {
    boolean decode(final ByteBuffer source, final T output);
}
