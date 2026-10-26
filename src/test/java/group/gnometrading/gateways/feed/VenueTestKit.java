package group.gnometrading.gateways.feed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketWriter;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** What a venue protocol test needs: markets with captured streams, a table, and a channel that records commands. */
public final class VenueTestKit {

    public final SubjectTable table = new SubjectTable();
    public final List<String> resyncs = new ArrayList<>();
    public final List<String> sent = new ArrayList<>();
    public final List<String> pings = new ArrayList<>();
    public final WebSocketClient client = mock(WebSocketClient.class);
    public final WebSocketChannel channel;
    private int nextStreamId = 1;

    public VenueTestKit() {
        doAnswer(inv -> {
                    ByteBuffer payload = inv.getArgument(2);
                    sent.add(StandardCharsets.UTF_8.decode(payload.duplicate()).toString());
                    return null;
                })
                .when(client)
                .wrapMessage(any(), eq(Opcode.TEXT), any());
        doAnswer(inv -> {
                    pings.add("ping");
                    return null;
                })
                .when(client)
                .wrapPingMessage(any());
        channel = new WebSocketChannel(client, new WebSocketWriter(client, 1 << 14));
    }

    public static long price(final String dollars) {
        return new BigDecimal(dollars)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    public static long size(final String amount) {
        return new BigDecimal(amount)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }

    public static ByteBuffer frame(final String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A stream's ring and what was published on it. */
    public static final class Stream {
        // As the runtime makes them: a market's producer changes thread when it moves between connections.
        public final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(
                Mbp10Schema::new, new GlobalSequence(), 1024, SequencedRingBuffer.Producers.TAKING_TURNS);
        public final List<Mbp10Schema> records = new ArrayList<>();
        private final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            Mbp10Schema copy = new Mbp10Schema();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            records.add(copy);
        });

        public List<Mbp10Schema> drain() throws Exception {
            while (poller.poll() > 0) {}
            return records;
        }

        public Mbp10Schema last() throws Exception {
            List<Mbp10Schema> all = drain();
            return all.get(all.size() - 1);
        }
    }

    /** A market on the connection with one stream per projection, in the order given. */
    public Subject market(final String key, final Stream[] streams, final Projection... projections) {
        StreamOutput[] outputs = new StreamOutput[projections.length];
        for (int i = 0; i < projections.length; i++) {
            streams[i] = new Stream();
            outputs[i] = new StreamOutput(nextStreamId++, projections[i], streams[i].ring);
        }
        Subject subject = new Subject(key, 4096, outputs);
        subject.attach(new RecordingHost(resyncs));
        table.put(subject);
        return subject;
    }

    /** A market with one stream seeing it as it is. */
    public Stream market(final String key) {
        Stream[] streams = new Stream[1];
        market(key, streams, Projection.IDENTITY);
        return streams[0];
    }
}
