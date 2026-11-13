package group.gnometrading.gateways.transport;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** A websocket: text and binary frames are venue data; pings are answered here, pongs only prove the link alive. */
public final class WebSocketTransport implements Transport<WebSocketChannel> {

    private final WebSocketClient client;
    private final WebSocketWriter writer;
    private final WebSocketChannel channel;
    private boolean sawTraffic;

    public WebSocketTransport(final WebSocketClient client, final int writeSlotBytes) {
        this.client = client;
        this.writer = new WebSocketWriter(client, writeSlotBytes);
        this.channel = new WebSocketChannel(client, writer);
    }

    @Override
    public WebSocketChannel channel() {
        return channel;
    }

    @Override
    public FrameWriter writer() {
        return writer;
    }

    @Override
    public void configure(final GatewayConfig config) throws IOException {
        config.configure(client, !config.spinReads());
    }

    @Override
    public void open() throws IOException {
        client.connect();
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

    @Override
    public ByteBuffer read() throws IOException {
        final WebSocketResponse result = client.read();
        sawTraffic = false;
        if (result.isClosed()) {
            throw closed(result);
        }
        if (!result.isSuccess()) {
            return null;
        }
        sawTraffic = true;
        final Opcode opcode = result.getOpcode();
        if (opcode == Opcode.TEXT || opcode == Opcode.BINARY) {
            return result.getBody();
        }
        if (opcode == Opcode.PING) {
            writer.sendPong();
        }
        return null;
    }

    /** Why the venue hung up, as far as it said: a close frame carries a status code and reason, a dropped link nothing. */
    private static EOFException closed(final WebSocketResponse closed) {
        if (closed.getOpcode() != Opcode.CLOSING) {
            return new EOFException("The venue dropped the connection without a close frame");
        }
        final ByteBuffer payload = closed.getBody();
        if (payload == null || payload.remaining() < 2) {
            return new VenueClosedException(VenueClosedException.NO_STATUS, "");
        }
        final int code = payload.getShort(payload.position()) & 0xFFFF;
        final byte[] reason = new byte[payload.remaining() - 2];
        payload.get(payload.position() + 2, reason);
        return new VenueClosedException(code, new String(reason, StandardCharsets.UTF_8));
    }

    @Override
    public boolean sawTraffic() {
        return sawTraffic;
    }
}
