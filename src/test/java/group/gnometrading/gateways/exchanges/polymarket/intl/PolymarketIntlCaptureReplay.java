package group.gnometrading.gateways.exchanges.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.gateways.inbound.exchanges.polymarket.intl.PolymarketIntlInboundReader;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a captured market channel (InboundLiveCheck -Draw, one "recvNanos body" per line) through the real reader:
 * {@code <file> <conditionId> <token> [drop[=n]]}. Without {@code drop}, reports any gap the best-price check finds in
 * real traffic. With it, drops the nth (default first) message that removes the token's best bid or ask and reports
 * where the check notices. The token must be the one the capture subscribed to: the venue sends books only for that. Not a test: it needs a capture, which is too big to keep.
 */
public final class PolymarketIntlCaptureReplay {

    private PolymarketIntlCaptureReplay() {}

    public static void main(final String[] args) throws Exception {
        final String token = args[2];
        final boolean drop = args.length > 3 && args[3].startsWith("drop");
        int skip = drop && args[3].startsWith("drop=") ? Integer.parseInt(args[3].substring(5)) - 1 : 0;
        final List<String> bodies = new ArrayList<>();
        for (final String line : Files.readAllLines(Path.of(args[0]))) {
            final String body = line.substring(line.indexOf(' ') + 1).trim();
            if (!body.isEmpty() && !body.equals("PONG")) {
                bodies.add(body);
            }
        }
        int dropped = -1;
        if (drop) {
            final Pattern entry = Pattern.compile("\\{\"asset_id\":\"" + token
                    + "\", \"price\":\"([^\"]+)\", \"size\":\"([^\"]+)\", \"side\":\"([A-Z]+)\".*?"
                    + "\"best_bid\":\"([^\"]+)\", \"best_ask\":\"([^\"]+)\"");
            // After the first book, so the reader has one to diverge from.
            for (int i = 1; i < bodies.size() && dropped < 0; i++) {
                final Matcher m = entry.matcher(bodies.get(i));
                while (m.find()) {
                    final double price = Double.parseDouble(m.group(1));
                    final boolean bid = m.group(3).equals("BUY");
                    if (Double.parseDouble(m.group(2)) == 0
                            && (bid
                                    ? price > Double.parseDouble(m.group(4))
                                    : price < Double.parseDouble(m.group(5)))) {
                        if (skip-- == 0) {
                            dropped = i;
                        }
                        break;
                    }
                }
            }
            System.out.println(
                    "dropping message " + dropped + ": " + bodies.get(dropped).substring(0, 200));
            bodies.remove(dropped);
        }

        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final int[] suspect = {0};
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            final Mbp10Schema schema = new Mbp10Schema();
            schema.buffer.putBytes(0, buffer, 0, length);
            schema.wrap(schema.buffer);
            if (schema.decoder.flags().maybeBadBook()) {
                suspect[0]++;
            }
        });
        final List<String> gaps = new ArrayList<>();
        final ReplayClient client = new ReplayClient(bodies);
        final PolymarketIntlInboundReader reader = new PolymarketIntlInboundReader(
                new NullLogger() {
                    @Override
                    public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {
                        if (msg == LogMessage.MARKET_DATA_GAP) {
                            gaps.add("at message " + client.served + ": venue " + v2 + " book " + v3);
                        }
                    }
                },
                ring,
                () -> 9_000_000_000L,
                null,
                new Listing(
                        1,
                        new Exchange(2, "POLYMARKET_INTL", "POLYMARKET_INTL", "global", SchemaType.MBP_10),
                        new Security(3, "T", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                        args[1] + ":" + token,
                        "T"),
                client,
                new JsonDecoder());
        reader.buffer = false;
        reader.pauseControl.release();
        while (client.served < bodies.size()) {
            try {
                reader.doWork();
            } catch (final SocketClosedException e) {
                // What a reconnect would follow; the replay carries on to count every gap.
                reader.pauseControl.release();
            }
            poller.poll();
        }
        System.out.println("messages " + bodies.size() + ", gaps " + gaps.size() + ", suspect books " + suspect[0]
                + (dropped < 0 ? "" : ", dropped at " + dropped));
        gaps.stream().limit(5).forEach(g -> System.out.println("  " + g));
    }

    private static final class ReplayClient extends WebSocketClient {
        private final List<ByteBuffer> messages = new ArrayList<>();
        private final Response response = new Response();
        int served;

        ReplayClient(final List<String> bodies) throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 20, 1 << 16);
            for (final String body : bodies) {
                this.messages.add(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public WebSocketResponse read() {
            if (this.served == this.messages.size()) {
                return this.response.update(true, Opcode.TEXT, null, false);
            }
            return this.response.update(true, Opcode.TEXT, this.messages.get(this.served++), false);
        }
    }

    private static final class Response extends WebSocketResponse {}
}
