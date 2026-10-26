package group.gnometrading.gateways.feed.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.feed.RecordingClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketWriterTest {

    private final RecordingClient socket = new RecordingClient();
    private final WebSocketWriter writer = new WebSocketWriter(socket, 1 << 10);

    WebSocketWriterTest() throws Exception {}

    private void send(final String text) {
        writer.sendText(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)), false);
    }

    @Test
    void whatWasQueuedForTheOldSocketIsNeverSentOnTheNew() {
        send("{\"cmd\":\"old\"}");
        writer.newSocket();
        send("{\"cmd\":\"new\"}");
        writer.doWork();

        assertEquals(List.of("{\"cmd\":\"new\"}"), socket.messages);
    }

    @Test
    void theRoomLeftCountsQueuedMessagesUntilTheyAreSent() {
        int empty = writer.messageRoom();
        send("{}");
        send("{}");
        assertEquals(empty - 2, writer.messageRoom());
        writer.doWork();
        assertEquals(empty, writer.messageRoom());
    }
}
