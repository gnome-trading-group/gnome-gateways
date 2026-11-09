package group.gnometrading.gateways.account.reader;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.schemas.Liquidity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A venue whose events are text: {@code ACK <id>}, {@code FILL <id> <qty>} and {@code CANCEL <id>}, by correlation
 * id. Catch-up applies the events the test scripts in {@link #catchUp}; a {@link #followUp} script is fetched in a
 * follow-up once catch-up has applied.
 */
public final class TextAccountProtocol implements AccountProtocol<FakeAccountTransport> {

    public volatile List<String> catchUp = List.of();
    public volatile List<String> followUp = List.of();
    public final List<Long> catchUpSince = new ArrayList<>();
    public volatile int keepAlives;
    // Set to hold a catch-up fetch until counted down, as a slow venue would.
    public volatile CountDownLatch fetchGate;
    // The venue ids the reader handed over, as a venue matching events by them would index them.
    public final List<String> venueIds = new ArrayList<>();
    private List<String> fetched = List.of();
    private volatile boolean followUpWanted;
    private List<String> followUpFetched = List.of();

    @Override
    public void authenticate(final FakeAccountTransport channel) {
        channel.log.add("authenticate");
    }

    @Override
    public void subscribe(final FakeAccountTransport channel) {
        channel.log.add("subscribe");
    }

    @Override
    public void keepAlive(final FakeAccountTransport channel) {
        this.keepAlives++;
    }

    @Override
    public void onFrame(final ByteBuffer frame, final OrderEventSink sink) {
        apply(StandardCharsets.US_ASCII.decode(frame).toString(), sink);
    }

    @Override
    public void fetchCatchUp(final long sinceMillis, final Collection<OrderContext> held) {
        final CountDownLatch gate = this.fetchGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.catchUpSince.add(sinceMillis);
        // Taken once, as a venue's listing would show these events only to the catch-up that follows them.
        this.fetched = this.catchUp;
        this.catchUp = List.of();
    }

    @Override
    public void applyCatchUp(final OrderEventSink sink) {
        for (final String event : this.fetched) {
            apply(event, sink);
        }
        this.fetched = List.of();
        if (!this.followUp.isEmpty()) {
            this.followUpWanted = true;
        }
    }

    @Override
    public void onVenueOrderId(final OrderContext ctx, final long key) {
        this.venueIds.add(
                new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII));
    }

    @Override
    public boolean wantsFollowUp() {
        return this.followUpWanted;
    }

    @Override
    public void fetchFollowUp() {
        this.followUpFetched = this.followUp;
        this.followUp = List.of();
        this.followUpWanted = false;
    }

    @Override
    public void applyFollowUp(final OrderEventSink sink) {
        for (final String event : this.followUpFetched) {
            apply(event, sink);
        }
        this.followUpFetched = List.of();
    }

    static void apply(final String event, final OrderEventSink sink) {
        final String[] parts = event.split(" ");
        final byte[] id = parts[1].getBytes(StandardCharsets.US_ASCII);
        final OrderContext ctx = sink.order(OrderKeys.of(id, id.length));
        if (ctx == null) {
            return;
        }
        switch (parts[0]) {
            case "ACK" -> {
                if (!ctx.acked) {
                    sink.acknowledge(ctx, ctx.cumulativeFilledQty, ctx.leavesQty, 0);
                }
            }
            case "FILL" -> {
                final long qty = Long.parseLong(parts[2]);
                if (qty <= 0) {
                    return;
                }
                ctx.cumulativeFilledQty += qty;
                ctx.leavesQty -= qty;
                final boolean full = ctx.leavesQty <= 0;
                sink.fill(ctx, qty, 500_000_000L, 0, Liquidity.MAKER, full, 0);
                if (full) {
                    sink.release(ctx);
                }
            }
            case "CANCEL" -> sink.cancel(ctx, 0);
            default -> throw new IllegalArgumentException(event);
        }
    }
}
