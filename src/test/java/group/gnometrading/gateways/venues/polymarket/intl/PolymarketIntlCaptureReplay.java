package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.gateways.feed.GapReason;
import group.gnometrading.gateways.feed.StreamOutput;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectHost;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a captured connection (FeedLiveCheck -Draw, one "recvNanos body" per line) through the real protocol for one
 * token: {@code <file> <token> [drop[=n]]}. Without {@code drop}, reports any gap the best-price check finds in real
 * traffic. With it, drops the nth (default first) message that removes the token's best bid or ask and reports where
 * the check notices. The capture must have subscribed the token: the venue sends books only for that. Not a test: it
 * needs a capture, which is too big to keep.
 */
public final class PolymarketIntlCaptureReplay {

    private PolymarketIntlCaptureReplay() {}

    public static void main(final String[] args) throws Exception {
        final String token = args[1];
        final boolean drop = args.length > 2 && args[2].startsWith("drop");
        int skip = drop && args[2].startsWith("drop=") ? Integer.parseInt(args[2].substring(5)) - 1 : 0;
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
        final int[] flagged = {0};
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            final Mbp10Schema schema = new Mbp10Schema();
            schema.buffer.putBytes(0, buffer, 0, length);
            schema.wrap(schema.buffer);
            if (schema.decoder.flags().badData()) {
                flagged[0]++;
            }
        });
        final List<String> gaps = new ArrayList<>();
        final int[] at = {0};
        final SubjectTable table = new SubjectTable();
        final Subject subject = new Subject(token, 1000, new StreamOutput(77, Projection.IDENTITY, ring));
        subject.attach(new SubjectHost() {
            @Override
            public void requestResync(final Subject market, final GapReason reason, final long atNanos) {
                gaps.add("at message " + at[0] + ": " + reason);
            }

            @Override
            public void requestStale(final Subject market, final GapReason reason) {}

            @Override
            public void requestRepublish(final Subject market) {}
        });
        table.put(subject);
        final PolymarketIntlProtocol protocol = new PolymarketIntlProtocol(table);
        for (final String body : bodies) {
            at[0]++;
            protocol.onFrame(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), at[0]);
            poller.poll();
        }
        System.out.println("messages " + bodies.size() + ", gaps " + gaps.size() + ", flagged books " + flagged[0]
                + (dropped < 0 ? "" : ", dropped at " + dropped));
        gaps.stream().limit(5).forEach(g -> System.out.println("  " + g));
    }
}
