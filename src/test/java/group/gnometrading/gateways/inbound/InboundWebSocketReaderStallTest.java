package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.inbound.InboundWebSocketReaderTest.TestWebSocketReader;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A connection that goes silent without closing (a NAT or load balancer dropping it) must not wedge the reader: a
 * disconnect waits for the reader thread to pause, which it can only do once its blocking read returns.
 */
class InboundWebSocketReaderStallTest {

    private static final String UPGRADE = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            + "\r\n";

    @Test
    void disconnectCompletesWhileTheConnectionIsSilent() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final Thread silentPeer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readRequest(socket.getInputStream());
                    socket.getOutputStream().write(UPGRADE.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    Thread.sleep(30_000);
                } catch (Exception ignored) {
                    // Interrupted or disconnected at the end of the test.
                }
            });
            silentPeer.setDaemon(true);
            silentPeer.start();

            final WebSocketClient client = new WebSocketClientBuilder()
                    .withURI(URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/"))
                    .withSocketFactory(new NativeSocketFactory())
                    .build();
            final SequencedRingBuffer<Mbp10Schema> outputBuffer =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            outputBuffer.start();
            final TestWebSocketReader reader =
                    new TestWebSocketReader(outputBuffer, new InboundWebSocketWriter(client), client);
            reader.pause = false;
            reader.configureSocket(new GatewayConfig.Builder().build());
            reader.testAttachSocket();

            final AtomicBoolean running = new AtomicBoolean(true);
            final Thread readerThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        reader.doWork();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();
            Thread.sleep(300); // the reader is now blocked reading a connection that will never send anything

            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), reader::disconnect);
                assertTrue(reader.isPaused);
            } finally {
                running.set(false);
                reader.pause = false;
                outputBuffer.shutdown();
                silentPeer.interrupt();
            }
        }
    }

    private static void readRequest(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return;
            }
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1
                    : 0;
        }
    }
}
