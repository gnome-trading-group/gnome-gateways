package group.gnometrading.gateways.codecs;

import group.gnometrading.utils.Resettable;

import java.nio.ByteBuffer;

public interface Decoder<T extends Resettable> {
    boolean decode(final ByteBuffer source, final T output);
}
