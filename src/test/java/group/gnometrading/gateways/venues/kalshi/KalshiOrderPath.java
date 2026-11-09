package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strings.MutableString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;

/**
 * One Kalshi order's trip through the pieces the order path is built from, as steps a test can run over and over:
 * build and sign it, read the venue's answer, apply its resting and fill events, amend and cancel paths, and the
 * unknown-outcome policy. The same order each time, so nothing grows between runs.
 */
public final class KalshiOrderPath {

    private static final long COUNTER = 7;
    private static final long PRICE = Statics.PRICE_SCALING_FACTOR / 2;
    private static final long SIZE = 3 * Statics.SIZE_SCALING_FACTOR;

    private final KalshiMarket market = market();
    private final KalshiOrderCodec codec = new KalshiOrderCodec("maker");
    private final KalshiAuthSigner signer;
    private final KalshiUserEvents events = new KalshiUserEvents();
    private final JsonDecoder decoder = new JsonDecoder();
    private final OrderPathTestKit.CountingSink sink = new OrderPathTestKit.CountingSink();
    private final SubmitPolicy policy = new SubmitPolicy(2_000_000_000L);
    private final byte[] prefix = "smoke-session-".getBytes(StandardCharsets.US_ASCII);
    private final ByteBuffer body = ByteBuffer.allocate(2048);
    private final MutableString path = new MutableString(KalshiOrderCodec.pathCapacity(this.market));
    private final OrderContext ctx = new OrderContext();
    // Held once: a method reference made per call would allocate.
    private final java.util.function.Consumer<JsonDecoder.JsonObject> applyMessage = this::apply;
    private final OrderPathTestKit.Answer accepted = new OrderPathTestKit.Answer(
            201, "{\"client_order_id\":\"smoke-session-7\",\"order_id\":\"0a1b2c\",\"remaining_count\":\"3.00\"}");
    private final OrderPathTestKit.Answer amended =
            new OrderPathTestKit.Answer(200, "{\"order\":{\"remaining_count\":\"2.00\"}}");
    private final OrderPathTestKit.Frame resting = new OrderPathTestKit.Frame(update("resting", "0.00", "3.00", "0"));
    private final OrderPathTestKit.Frame filled =
            new OrderPathTestKit.Frame(update("executed", "3.00", "0.00", "1.500000"));

    public KalshiOrderPath() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.signer =
                    new KalshiAuthSigner("key-id", generator.generateKeyPair().getPrivate());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        this.market.exchangeIndex(1);
    }

    /** Builds the order, its id and body, and reads the venue's acceptance. */
    public void build() {
        startOrder();
        this.codec.buildOrder(
                this.market, PRICE, SIZE, Side.Bid, TimeInForce.GOOD_TILL_CANCELED, true, this.ctx, this.body);
        KalshiOrderCodec.readOrderId(this.accepted.get(), this.ctx);
    }

    /** Signs a submit, as every request is. */
    public void sign() {
        try {
            this.signer.sign(1_700_000_000_000L, "POST", KalshiOrderCodec.ORDER_PATH);
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
            this.events.apply(this.sink, FeeTimeline.EMPTY, 0);
        }
    }

    /** An amend's body and path, and the venue's answer; then a cancel's path with its routing. */
    public void amendAndCancel() {
        startOrder();
        KalshiOrderCodec.readOrderId(this.accepted.get(), this.ctx);
        this.codec.buildAmend(this.market, PRICE, SIZE, Side.Bid, this.body);
        KalshiOrderCodec.amendPath(this.ctx, this.path);
        KalshiOrderCodec.readRemainingCount(this.amended.get());
        KalshiOrderCodec.orderPath(this.ctx, this.path);
        KalshiOrderCodec.appendRoutingQuery(this.market, this.path);
    }

    /** No answer, a resend refused, a lookup too soon to trust, then one that finds it. */
    public void unknownOutcome() {
        this.ctx.reset();
        this.ctx.lastSendNanos = 1_000;
        this.policy.afterSend(this.ctx, SubmitResult.UNKNOWN, true);
        this.policy.afterSend(this.ctx, SubmitResult.REJECTED, true);
        this.policy.afterLookUp(this.ctx, LookupResult.NOT_FOUND, 2_000);
        this.policy.afterLookUp(this.ctx, LookupResult.FOUND, 3_000_000_000L);
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
        this.codec.writeClientOrderId(this.prefix, this.ctx);
    }

    private static String update(
            final String status, final String filled, final String remaining, final String takerCost) {
        return "{\"type\":\"user_order\",\"sid\":2,\"msg\":{\"order_id\":\"0a1b2c\",\"client_order_id\":"
                + "\"smoke-session-7\",\"ticker\":\"KXT-1\",\"status\":\"" + status + "\",\"fill_count_fp\":\""
                + filled + "\",\"remaining_count_fp\":\"" + remaining + "\",\"taker_fill_cost_dollars\":\""
                + takerCost + "\",\"maker_fill_cost_dollars\":\"0.000000\",\"taker_fees_dollars\":\"0.050000\","
                + "\"maker_fees_dollars\":\"0.000000\",\"last_updated_ts_ms\":1700000000000}}";
    }

    private static KalshiMarket market() {
        return KalshiMarket.of(new Listing(
                1,
                new Exchange(2, "KALSHI", "Kalshi", "global"),
                new Security(3, "KXT", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "KXT-1:yes",
                "KXT-1"));
    }
}
