package group.gnometrading.gateways.codecs;

import group.gnometrading.objects.OrderDecoder;

import java.nio.ByteBuffer;

public interface Encoder {
    boolean encode(final ByteBuffer destination, final OrderDecoder orderDecoder);
}
