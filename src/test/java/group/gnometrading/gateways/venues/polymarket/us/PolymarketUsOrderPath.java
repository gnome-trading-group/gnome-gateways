package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strings.MutableString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * One Polymarket US order's trip through the pieces the order path is built from, as steps a test can run over and
 * over: build it with its stamp, read the venue's answer, build a modify and the order paths, sign a request, and apply
 * its execution events, resting then filled, matched by the stamp. The same order each time.
 */
public final class PolymarketUsOrderPath {

    private static final String SLUG = "test-market";
    private static final int SLOT = 41;
    private static final long COUNTER = 7;
    private static final long PRICE = 55 * Statics.PRICE_SCALING_FACTOR / 100;
    private static final long SIZE = 3 * Statics.SIZE_SCALING_FACTOR;

    private final PolymarketUsMarket market = new PolymarketUsMarket(
            new Listing(
                    1,
                    new Exchange(2, "POLYMARKET_US", "Polymarket US", "us"),
                    new Security(3, SLUG, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                    SLUG + ":long",
                    SLUG),
            new PolymarketUsMarketInfo(Statics.PRICE_SCALING_FACTOR / 100, Statics.SIZE_SCALING_FACTOR / 100));
    private final PolymarketUsOrderCodec codec = new PolymarketUsOrderCodec();
    private final PolymarketUsAuthSigner signer;
    private final PolymarketUsUserEvents events = new PolymarketUsUserEvents(new NullLogger(), 64);
    private final PolymarketUsUserEvents.Markets markets = new PolymarketUsUserEvents.Markets() {
        @Override
        public boolean onListing(final OrderContext ctx, final PolymarketUsOrderFields order) {
            return PolymarketUsOrderPath.this.market.listing().trades(order.intent);
        }

        @Override
        public long listingPrice(final OrderContext ctx, final long venuePrice) {
            return PolymarketUsOrderPath.this.market.listing().convertPrice(venuePrice);
        }
    };
    private final JsonDecoder decoder = new JsonDecoder();
    private final OrderPathTestKit.CountingSink sink = new OrderPathTestKit.CountingSink();
    // Held once: a method reference made per call would allocate.
    private final Consumer<JsonDecoder.JsonObject> applyMessage = this::apply;
    private final ByteBuffer body = ByteBuffer.allocate(2048);
    private final MutableString path = new MutableString(PolymarketUsOrderCodec.MAX_PATH_LENGTH);
    private final OrderContext ctx = new OrderContext();
    private final OrderPathTestKit.Answer accepted =
            new OrderPathTestKit.Answer(200, "{\"id\":\"V1\", \"executions\":[], \"pending\":false}");
    private final OrderContext[] batchCtxs = new OrderContext[PolymarketUsRequests.MAX_BATCH];
    private final OrderPathTestKit.Answer batchAccepted = new OrderPathTestKit.Answer(200, batchAnswer());
    private final OrderPathTestKit.Answer batchRefused =
            new OrderPathTestKit.Answer(400, "{\"code\":3,\"message\":\"order[13]: Market not found: x\"}");
    private final OrderPathTestKit.Frame resting;
    private final OrderPathTestKit.Frame filled;

    public PolymarketUsOrderPath() {
        try {
            this.signer = new PolymarketUsAuthSigner(
                    "key-id",
                    PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(new byte[32])));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < this.batchCtxs.length; i++) {
            this.batchCtxs[i] = new OrderContext();
        }
        startOrder();
        final String stamp =
                new String(this.ctx.correlationIdBytes, 0, this.ctx.correlationIdLength, StandardCharsets.US_ASCII);
        this.resting = new OrderPathTestKit.Frame(
                execution("EXECUTION_TYPE_NEW", "ORDER_STATE_NEW", 0, stamp, "0.0000", "0.0000", "0.0000"));
        this.filled = new OrderPathTestKit.Frame(
                execution("EXECUTION_TYPE_FILL", "ORDER_STATE_FILLED", 3, stamp, "0.5500", "3.0000", "0.5500"));
    }

    /** Builds the order with its stamp, reads the venue's acceptance, then builds a modify and both order paths. */
    public void build() {
        startOrder();
        this.codec.buildOrder(this.market, this.ctx, PRICE, Side.Bid, true, this.body);
        this.codec.readSubmitAnswer(this.accepted.get(), 200, this.ctx);
        this.codec.buildModify(this.market, this.ctx, PRICE, SIZE, true, this.body);
        PolymarketUsOrderCodec.orderPath(this.ctx, PolymarketUsRequests.MODIFY_SUFFIX, this.path);
        PolymarketUsOrderCodec.orderPath(this.ctx, PolymarketUsRequests.CANCEL_SUFFIX, this.path);
    }

    /** Reads a full batch's ids into its orders, and the entry a refused batch names. */
    public void batch() {
        this.codec.readCreatedOrderIds(this.batchAccepted.get().getBody(), this.batchCtxs, this.batchCtxs.length);
        if (PolymarketUsOrderCodec.failedEntry(this.batchRefused.get().getBody()) != 13) {
            throw new AssertionError("the refused entry wasn't read");
        }
    }

    public OrderContext[] batchCtxs() {
        return this.batchCtxs;
    }

    private static String batchAnswer() {
        final StringBuilder answer = new StringBuilder("{\"createdOrderIds\":[");
        for (int i = 0; i < PolymarketUsRequests.MAX_BATCH; i++) {
            answer.append(i == 0 ? "" : ",").append("\"V").append(i).append('"');
        }
        return answer.append("]}").toString();
    }

    /** Signs a submit, as every request is. */
    public void sign() {
        try {
            this.signer.sign(1_700_000_000_000L, "POST", PolymarketUsRequests.ORDERS_PATH_GS);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The order rests, then fills in full: an acknowledgement, a fill, and its release. */
    public void events() {
        startOrder();
        this.sink.hold(this.ctx);
        this.resting.decode(this.decoder, this.applyMessage);
        this.filled.decode(this.decoder, this.applyMessage);
    }

    private void apply(final JsonDecoder.JsonObject message) {
        if (this.events.readUpdate(message)) {
            this.events.apply(this.sink, this.markets);
        }
    }

    public OrderPathTestKit.CountingSink sink() {
        return this.sink;
    }

    private void startOrder() {
        this.ctx.reset();
        this.ctx.clientOidCounter = COUNTER;
        this.ctx.side = Side.Bid;
        this.ctx.originalQty = SIZE;
        this.ctx.leavesQty = SIZE;
        this.ctx.limitPrice = PRICE;
        PolymarketUsOrderCodec.writeStampId(SLOT, this.ctx);
    }

    private static String execution(
            final String type,
            final String state,
            final long cumQuantity,
            final String stamp,
            final String avgPx,
            final String lastShares,
            final String lastPx) {
        return "{\"requestId\":\"orders\", \"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\", "
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\", \"order\":{\"id\":\"V1\", \"marketSlug\":\""
                + SLUG
                + "\", \"side\":\"ORDER_SIDE_BUY\", \"type\":\"ORDER_TYPE_LIMIT\", \"price\":{\"value\":\"0.55\","
                + " \"currency\":\"USD\"}, \"quantity\":3, \"cumQuantity\":" + cumQuantity + ", \"leavesQuantity\":"
                + (3 - cumQuantity) + ", \"tif\":\"TIME_IN_FORCE_GOOD_TILL_DATE\", \"goodTillTime\":\"" + stamp
                + "\", \"intent\":\"ORDER_INTENT_BUY_LONG\", \"state\":\"" + state + "\", "
                + "\"commissionNotionalTotalCollected\":{\"value\":\"0.0100\", \"currency\":\"USD\"}, "
                + "\"avgPx\":{\"value\":\"" + avgPx + "\", \"currency\":\"USD\"}, "
                + "\"createTime\":\"2026-10-08T14:33:25.872677584Z\"}, \"lastShares\":\"" + lastShares + "\", "
                + "\"lastPx\":{\"value\":\"" + lastPx + "\", \"currency\":\"USD\"}, \"type\":\"" + type + "\", "
                + "\"text\":\"\", \"tradeId\":\"T\", \"aggressor\":true, "
                + "\"transactTime\":\"2026-10-08T14:33:25.874628732Z\"}}}";
    }
}
