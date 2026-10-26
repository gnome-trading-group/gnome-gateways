package group.gnometrading.gateways.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.WebSocketTransport;
import group.gnometrading.gateways.venues.lighter.LighterProtocol;
import group.gnometrading.logging.NullLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A connection given more markets than its writer holds at once (Lighter: two messages a market, one market a
 * command) sends what fits each pass and the rest later, never overflowing the writer or the source's message budget.
 */
class SubscribeBurstTest {

    private final RecordingClient socket = new RecordingClient();
    private final WebSocketTransport transport = new WebSocketTransport(socket, 1 << 10);
    private final SubjectTable table = new SubjectTable();
    private final VenueTestKit.Stream stream = new VenueTestKit.Stream();
    private long now = TimeUnit.HOURS.toNanos(1);

    SubscribeBurstTest() throws Exception {}

    private FeedConnection<?> connection(final RateBudget budget) {
        FeedConnection<?> connection = new FeedConnection<>(
                "lighter-0",
                new NullLogger(),
                transport,
                new LighterProtocol(table, transport.channel()),
                table,
                new GatewayConfig.Builder().build(),
                () -> now / 1_000_000,
                () -> now,
                ConnectLimiter.UNLIMITED,
                budget,
                FeedListener.NONE);
        connection.assumeConnected();
        for (int i = 0; i < 100; i++) {
            connection.add(new Subject(String.valueOf(i), 16, new StreamOutput(i, Projection.IDENTITY, stream.ring)));
        }
        return connection;
    }

    private List<String> subscribedBooks() {
        List<String> books = new ArrayList<>();
        for (String message : socket.messages) {
            if (message.contains("\"subscribe\"") && message.contains("order_book/")) {
                books.add(message);
            }
        }
        return books;
    }

    @Test
    void aBurstWaitsForRoomInTheWriter() throws Exception {
        FeedConnection<?> connection = connection(RateBudget.UNLIMITED);

        connection.control(now);
        transport.writer().doWork();
        assertEquals(60, subscribedBooks().size(), "the writer's 128 slots, less 8 kept for keep-alives, hold 60");

        connection.control(now);
        transport.writer().doWork();
        assertEquals(100, subscribedBooks().size());
        assertEquals(200, socket.messages.size(), "every market's book and trades, once");
    }

    @Test
    void aBurstStaysWithinTheSourcesMessagesAMinute() throws Exception {
        FeedConnection<?> connection = connection(new RateBudget(180));

        connection.control(now);
        transport.writer().doWork();
        connection.control(now);
        transport.writer().doWork();
        assertEquals(180, socket.messages.size(), "a minute's 180 messages: 90 markets");

        now += TimeUnit.SECONDS.toNanos(7);
        connection.control(now);
        transport.writer().doWork();
        assertEquals(200, socket.messages.size(), "the last ten once there is room again");
    }
}
