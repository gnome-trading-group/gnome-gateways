package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.outbound.OutboundJsonWebSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundReader extends OutboundJsonWebSocketReader {

    // user_orders is a channel on Kalshi's one authenticated socket, which is signed by its path.
    private static final String WS_PATH = "/trade-api/ws/v2";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    // Orders are placed shortly before the time the writer records, by a clock that may run behind Kalshi's.
    private static final long LOOKUP_SLACK_SECONDS = 60;

    private final KalshiAuthSigner authSigner;
    private final FeeTimeline feeTimeline;
    private final KalshiUserEvents events = new KalshiUserEvents();
    private final byte[] subscribeMessage;
    private final String marketTicker;
    // Null when no REST client was given: the reader then can't catch up after a reconnect.
    private final KalshiOrderPages orderPages;
    // Fetched on the supervisor's thread during a connect, applied on the reader's next pass.
    private List<ByteBuffer> venueOrderPages = List.of();

    public KalshiOutboundReader(
            final Logger logger,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final EpochNanoClock clock,
            final Listing listing,
            final WebSocketClient socketClient,
            final JsonDecoder jsonDecoder,
            final KalshiAuthSigner authSigner,
            final HTTPClient restClient,
            final String apiHost,
            final FeeTimeline feeTimeline) {
        super(
                logger,
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                clock,
                listing,
                socketClient,
                jsonDecoder);
        this.authSigner = authSigner;
        this.feeTimeline = feeTimeline;

        // The YES and NO listings trade one market, so the suffix is not part of its ticker.
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIdx = exchangeSecurityId.indexOf(':');
        this.marketTicker = colonIdx >= 0 ? exchangeSecurityId.substring(0, colonIdx) : exchangeSecurityId;
        // Its own HTTP client: it runs on the supervisor's thread, and the writer's client is the writer's alone.
        this.orderPages = restClient == null
                ? null
                : new KalshiOrderPages(restClient, apiHost, authSigner, () -> clock.nanoTime() / NANOS_PER_MILLI);
        this.subscribeMessage = ("{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\"],"
                        + "\"market_tickers\":[\"" + this.marketTicker + "\"]}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void beforeConnect() throws IOException {
        this.authSigner.sign(this.clock.nanoTime() / NANOS_PER_MILLI, "GET", WS_PATH);
        this.socketClient.setHeader("KALSHI-ACCESS-KEY", this.authSigner.apiKey());
        this.socketClient.setHeader("KALSHI-ACCESS-TIMESTAMP", this.authSigner.timestamp());
        this.socketClient.setHeader("KALSHI-ACCESS-SIGNATURE", this.authSigner.signature());
    }

    @Override
    protected void subscribe() throws IOException {
        this.socketClient.writeMessage(Opcode.TEXT, ByteBuffer.wrap(this.subscribeMessage));
    }

    /**
     * Fetches every order of this market placed since just before the oldest one this reader holds or the socket
     * went quiet, all statuses: their fills and cancels while the socket was down are in there.
     */
    @Override
    protected void fetchVenueState() throws IOException {
        this.venueOrderPages = List.of();
        if (this.orderPages == null) {
            return;
        }
        // From the oldest order held, or from when the socket went quiet if that's earlier: orders the writer sent
        // meanwhile are still queued for this reader, not among those it holds.
        final long oldestHeld = oldestOpenOrderMillis();
        final long sinceMillis = oldestHeld < 0 ? lastHeardMillis() : Math.min(oldestHeld, lastHeardMillis());
        this.venueOrderPages = this.orderPages.fetch(
                this.marketTicker, "&min_ts=" + (sinceMillis / MILLIS_PER_SECOND - LOOKUP_SLACK_SECONDS));
    }

    /**
     * Kalshi lists an order with the fields of its user_order updates, so each goes through the same handling:
     * what the reader has already reported is reported again only by what changed, and orders it doesn't hold
     * are skipped.
     */
    @Override
    protected void applyVenueState() {
        for (final ByteBuffer page : this.venueOrderPages) {
            try (var root = this.jsonDecoder.wrap(page);
                    var obj = root.asObject()) {
                while (obj.hasNextKey()) {
                    try (var entry = obj.nextKey()) {
                        if (entry.getName().equals("orders")) {
                            applyOrders(entry);
                        }
                    }
                }
            }
        }
        this.venueOrderPages = List.of();
    }

    private void applyOrders(final JsonDecoder.JsonNode orders) {
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                applyOrder(list.nextItem());
            }
        }
    }

    private void applyOrder(final JsonDecoder.JsonNode item) {
        try (item;
                var order = item.asObject()) {
            this.events.readListedOrder(order);
            this.events.apply(this, this.feeTimeline, this.clock.nanoTime());
        }
    }

    @Override
    public void keepAlive() throws IOException {
        // Kalshi server sends WebSocket pings every 10s; base class handles PONG automatically
    }

    @Override
    public String roleName() {
        return "kalshi-outbound-reader";
    }

    @Override
    protected void handleJsonMessage(final JsonDecoder.JsonObject obj) {
        if (this.events.readUpdate(obj)) {
            this.events.apply(this, this.feeTimeline, this.clock.nanoTime());
        }
    }
}
