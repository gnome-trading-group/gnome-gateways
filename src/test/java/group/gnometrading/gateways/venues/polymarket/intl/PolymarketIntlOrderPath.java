package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * One Polymarket International order's trip through the pieces the order path is built from, as steps a test can run
 * over and over: sign and build it, read the venue's answer, build and read a cancel, sign a request, and apply its
 * placement and a trade that fills it. Trades get ids from a rotating set larger than the reader's dedup window, so
 * each is new to it.
 */
public final class PolymarketIntlOrderPath {

    // Hardhat account #0: a well-known test key, never funded on Polygon.
    private static final byte[] PRIVATE_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String TOKEN = "71321045679252212594626385532706912750332728571942532289631379312455583992563";
    private static final String HASH = "0x" + "ab".repeat(32);
    private static final long PRICE = 55 * Statics.PRICE_SCALING_FACTOR / 100;
    private static final long SIZE = 10 * Statics.SIZE_SCALING_FACTOR;
    private static final int TRADE_IDS = 5_000;

    private final PolymarketIntlMarket market = PolymarketIntlMarket.of(
            new Listing(
                    1,
                    new Exchange(2, "POLYMARKET", "Polymarket", "global"),
                    new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                    "0xcondition:" + TOKEN,
                    "TEST-YES"),
            new PolymarketIntlMarketInfo(Statics.PRICE_SCALING_FACTOR / 100, SIZE / 2, false, false, 0));
    private final PolymarketIntlOrderCodec codec = new PolymarketIntlOrderCodec(
            new PolymarketIntlOrderSigner(PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, 1L),
            "owner-key");
    private final PolymarketIntlAuthHeaders auth =
            new PolymarketIntlAuthHeaders("api-key", "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==", "pass", EOA);
    private final PolymarketIntlUserEvents events = new PolymarketIntlUserEvents(new NullLogger());
    private final byte[] tokenBytes = TOKEN.getBytes(StandardCharsets.US_ASCII);
    private final byte[] hashBytes = HASH.getBytes(StandardCharsets.US_ASCII);
    private final PolymarketIntlUserEvents.Markets markets = new PolymarketIntlUserEvents.Markets() {
        @Override
        public byte[] tokenId(final OrderContext ctx) {
            return PolymarketIntlOrderPath.this.tokenBytes;
        }

        @Override
        public FeeTimeline fees(final OrderContext ctx) {
            return FeeTimeline.EMPTY;
        }
    };
    private final JsonDecoder decoder = new JsonDecoder();
    private final OrderPathTestKit.CountingSink sink = new OrderPathTestKit.CountingSink();
    // Held once: a method reference made per call would allocate.
    private final Consumer<JsonDecoder.JsonObject> applyMessage = this::apply;
    private final ByteBuffer body = ByteBuffer.allocate(2048);
    private final OrderContext ctx = new OrderContext();
    private final OrderPathTestKit.Answer accepted;
    private final OrderPathTestKit.Answer canceled =
            new OrderPathTestKit.Answer(200, "{\"not_canceled\":{},\"canceled\":[\"" + HASH + "\"]}");
    private final OrderPathTestKit.Frame placement = new OrderPathTestKit.Frame(
            "{\"asset_id\":\"" + TOKEN + "\",\"event_type\":\"order\",\"id\":\"" + HASH + "\",\"market\":"
                    + "\"0xcondition\",\"original_size\":\"10\",\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":"
                    + "\"0\",\"status\":\"LIVE\",\"timestamp\":\"1700000000000\",\"type\":\"PLACEMENT\"}");
    private final OrderPathTestKit.Frame[] trades = new OrderPathTestKit.Frame[TRADE_IDS];
    private int nextTrade;

    public PolymarketIntlOrderPath() {
        for (int i = 0; i < TRADE_IDS; i++) {
            this.trades[i] = new OrderPathTestKit.Frame("{\"asset_id\":\"" + TOKEN + "\",\"event_type\":\"trade\","
                    + "\"id\":\"trade-" + i + "\",\"maker_orders\":[{\"asset_id\":\"" + TOKEN + "\",\"matched_amount\":"
                    + "\"10\",\"order_id\":\"0xother\",\"price\":\"0.55\"}],\"market\":\"0xcondition\",\"price\":"
                    + "\"0.55\",\"side\":\"BUY\",\"size\":\"10\",\"status\":\"MATCHED\",\"taker_order_id\":\"" + HASH
                    + "\",\"timestamp\":\"1700000000000\",\"type\":\"TRADE\"}");
        }
        // The answer names the hash the order is held under, which the codec checks.
        this.accepted = new OrderPathTestKit.Answer(
                200,
                "{\"errorMsg\":\"\",\"orderID\":\"" + HASH + "\",\"takingAmount\":\"\",\"makingAmount\":\"\","
                        + "\"status\":\"live\",\"success\":true}");
    }

    /** Signs and builds the order, and its cancel. */
    public void build() {
        startOrder();
        this.codec.buildOrder(
                this.market, PRICE, SIZE, Side.Bid, TimeInForce.GOOD_TILL_CANCELED, false, 1L, this.ctx, this.body);
        this.codec.buildCancel(this.ctx, this.body);
    }

    /** Reads an acceptance and a cancel's answer for an order with a known hash. */
    public void answers() {
        startOrder();
        useHash();
        this.codec.readSubmitAnswer(this.accepted.get(), 200, this.ctx);
        this.codec.readCanceled(this.canceled.get(), this.ctx);
    }

    /** Signs a request's L2 headers, as every request is. */
    public void sign() {
        this.auth.sign(1_700_000_000L, "POST", "/order", null, 0, 0);
    }

    /** The order is placed, then a trade fills it in full: an acknowledgement, a fill, and its release. */
    public void events() {
        startOrder();
        useHash();
        this.sink.hold(this.ctx);
        this.placement.decode(this.decoder, this.applyMessage);
        this.trades[this.nextTrade].decode(this.decoder, this.applyMessage);
        this.nextTrade = (this.nextTrade + 1) % TRADE_IDS;
    }

    private void apply(final JsonDecoder.JsonObject message) {
        this.events.read(message);
        this.events.apply(this.sink, this.markets, 0);
    }

    public OrderPathTestKit.CountingSink sink() {
        return this.sink;
    }

    private void startOrder() {
        this.ctx.reset();
        this.ctx.clientOidCounter = 7;
        this.ctx.side = Side.Bid;
        this.ctx.originalQty = SIZE;
        this.ctx.leavesQty = SIZE;
    }

    private void useHash() {
        System.arraycopy(this.hashBytes, 0, this.ctx.correlationIdBytes, 0, this.hashBytes.length);
        this.ctx.correlationIdLength = this.hashBytes.length;
        System.arraycopy(this.hashBytes, 0, this.ctx.exchangeOrderIdBytes, 0, this.hashBytes.length);
        this.ctx.exchangeOrderIdLength = this.hashBytes.length;
    }
}
