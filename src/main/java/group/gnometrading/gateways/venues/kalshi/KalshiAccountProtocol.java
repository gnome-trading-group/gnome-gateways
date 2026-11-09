package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.account.reader.JsonAccountProtocol;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderEventSink;
import group.gnometrading.gateways.transport.WebSocketChannel;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Kalshi's private socket for a whole account: {@code user_orders} with no market filter, so one subscription carries
 * every order on the account. Catch-up after a reconnect lists every order placed since just before the socket went
 * quiet, all statuses, and applies each as its update would be.
 */
public final class KalshiAccountProtocol extends JsonAccountProtocol<WebSocketChannel> {

    private static final String WS_PATH = "/trade-api/ws/v2";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    // Orders are placed shortly before the time the lane records, by a clock that may run behind Kalshi's.
    private static final long LOOKUP_SLACK_SECONDS = 60;
    private static final byte[] SUBSCRIBE =
            "{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\"]}}"
                    .getBytes(StandardCharsets.US_ASCII);

    private final KalshiAuthSigner upgradeSigner;
    private final KalshiOrderPages orderPages;
    private final EpochNanoClock clock;
    private final KalshiUserEvents events = new KalshiUserEvents();
    private final PublishedMap<FeeTimeline> fees = new PublishedMap<>();
    // Fetched by the supervisor while the reader is paused, applied by the reader on its next pass.
    private List<ByteBuffer> orderPagesFetched = List.of();

    /**
     * @param upgradeSigner signs the socket's upgrade and catch-up's listings, on the supervisor's thread only
     * @param restClient catch-up's own client, on the supervisor's thread only
     */
    public KalshiAccountProtocol(
            final KalshiAuthSigner upgradeSigner,
            final HTTPClient restClient,
            final String apiHost,
            final EpochNanoClock clock) {
        this.upgradeSigner = upgradeSigner;
        this.clock = clock;
        this.orderPages =
                new KalshiOrderPages(restClient, apiHost, upgradeSigner, () -> clock.nanoTime() / NANOS_PER_MILLI);
    }

    /** Off the hot path: a listing's fee schedules, for fills whose event doesn't carry Kalshi's own fees. */
    public void addMarket(final int exchangeId, final long securityId, final FeeTimeline feeTimeline) {
        this.fees.put(PublishedMap.listingKey(exchangeId, securityId), feeTimeline);
    }

    @Override
    public void authenticate(final WebSocketChannel channel) throws IOException {
        this.upgradeSigner.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WS_PATH);
        channel.client().setHeader(KalshiOrderCodec.HEADER_KEY, this.upgradeSigner.apiKey());
        channel.client().setHeader(KalshiOrderCodec.HEADER_TIMESTAMP, this.upgradeSigner.timestamp());
        channel.client().setHeader(KalshiOrderCodec.HEADER_SIGNATURE, this.upgradeSigner.signature());
    }

    @Override
    public void subscribe(final WebSocketChannel channel) throws IOException {
        channel.client().writeMessage(Opcode.TEXT, ByteBuffer.wrap(SUBSCRIBE));
    }

    /** Kalshi pings the socket every 10 s, and the transport answers. */
    @Override
    public void keepAlive(final WebSocketChannel channel) {}

    /** An event's time for fee schedules is when its frame arrived, which the sink already has. */
    @Override
    protected void onEvent(final JsonDecoder.JsonObject event, final OrderEventSink sink) {
        if (this.events.readUpdate(event)) {
            apply(sink, sink.receiveNanos());
        }
    }

    @Override
    public void fetchCatchUp(final long sinceMillis, final Collection<OrderContext> held) throws IOException {
        this.orderPagesFetched =
                this.orderPages.fetchAccount("&min_ts=" + (sinceMillis / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS));
    }

    /** Each listed order goes through the same handling as an update; orders not held are skipped. */
    @Override
    public void applyCatchUp(final OrderEventSink sink) {
        for (final ByteBuffer page : this.orderPagesFetched) {
            try (var root = this.jsonDecoder.wrap(page);
                    var obj = root.asObject()) {
                while (obj.hasNextKey()) {
                    try (var entry = obj.nextKey()) {
                        if (entry.getName().equals("orders")) {
                            applyOrders(entry, sink);
                        }
                    }
                }
            }
        }
        this.orderPagesFetched = List.of();
    }

    private void applyOrders(final JsonDecoder.JsonNode orders, final OrderEventSink sink) {
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    this.events.readListedOrder(order);
                    apply(sink, this.clock.nanoTime());
                }
            }
        }
    }

    private void apply(final OrderEventSink sink, final long nowNanos) {
        final OrderContext ctx = sink.order(this.events.correlationKey());
        if (ctx == null) {
            return;
        }
        final FeeTimeline feeTimeline = this.fees.get(PublishedMap.listingKey(ctx.exchangeId, ctx.securityId));
        this.events.apply(sink, feeTimeline != null ? feeTimeline : FeeTimeline.EMPTY, nowNanos);
    }
}
