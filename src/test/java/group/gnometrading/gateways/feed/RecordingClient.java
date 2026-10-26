package group.gnometrading.gateways.feed;

import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A websocket that is never opened: it unmasks each short text frame written to it and keeps the payload. */
public final class RecordingClient extends WebSocketClient {

    public final List<String> messages = new CopyOnWriteArrayList<>();

    public RecordingClient() throws IOException {
        super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 16, 1 << 16);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean writeBuffer(final ByteBuffer frame) {
        final int start = frame.position();
        final int length = frame.get(start + 1) & 0x7F;
        final byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (frame.get(start + 6 + i) ^ frame.get(start + 2 + (i % 4)));
        }
        messages.add(new String(payload, StandardCharsets.US_ASCII));
        frame.position(frame.limit());
        return true;
    }
}
