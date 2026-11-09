package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Polymarket US's account-wide socket: orders on two markets, one stream of events, and catch-up. */
class PolymarketUsAccountProtocolTest {

    private static final long SESSION = 100_042;
    private static final int SLOT = PolymarketUsStamps.slot(SESSION);
    private static final int EXCHANGE_ID = 2;
    private static final String SLUG_A = "tec-mlb-champ-2026-09-27-lad";
    private static final String SLUG_B = "tec-mlb-champ-2026-09-27-nyy";
    private static final long SECURITY_A = 3;
    private static final long SECURITY_B = 4;

    private final HTTPClient restClient = mock(HTTPClient.class);
    private final Map<String, String> restAnswers = new HashMap<>();
    private final List<String> restPaths = Collections.synchronizedList(new ArrayList<>());
    private final RecordedReports recorded = new RecordedReports();
    private final OrderReports reports = new OrderReports(64, SystemEpochNanoClock.INSTANCE, this.recorded);
    private PolymarketUsAccountProtocol protocol;
    private PolymarketUsAuthSigner signer;
    // Set to slow every answer, as a venue a long way off would be.
    private volatile long answerDelayMillis;

    @BeforeEach
    void setUp() throws Exception {
        this.signer = new PolymarketUsAuthSigner(
                "key", PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(new byte[32])));
        this.protocol = new PolymarketUsAccountProtocol(
                new NullLogger(),
                signer,
                List.of(new PolymarketUsVenueOrderQuery(
                        this.restClient, "api.test", signer, System::currentTimeMillis)),
                SystemEpochNanoClock.INSTANCE,
                64);
        this.protocol.addMarket(EXCHANGE_ID, SECURITY_A, listing(SECURITY_A, SLUG_A));
        this.protocol.addMarket(EXCHANGE_ID, SECURITY_B, listing(SECURITY_B, SLUG_B));
        this.reports.venueIdListener(this.protocol::onVenueOrderId);
        stubRest();
    }

    @Test
    void restingOrdersOnTwoMarketsAreEachMatchedByTheirStamp() {
        resting(1, SECURITY_A, "3");
        resting(2, SECURITY_B, "3");
        frame(execution(
                "EXECUTION_TYPE_NEW", order("V2", SLUG_B, "ORDER_STATE_NEW", "0.55", 3, 0, "0", stamp(2), false)));
        frame(execution(
                "EXECUTION_TYPE_NEW", order("V1", SLUG_A, "ORDER_STATE_NEW", "0.55", 3, 0, "0", stamp(1), false)));
        frame(fill(
                order("V2", SLUG_B, "ORDER_STATE_PARTIALLY_FILLED", "0.55", 3, 1, "0.5500", stamp(2), false),
                "1.0000"));

        assertEquals(List.of(ExecType.NEW, ExecType.NEW, ExecType.PARTIAL_FILL), this.recorded.execTypes());
        assertEquals(List.of("V2", "V1"), this.recorded.venueIds, "each id handed to the lane as it is matched");
        assertEquals(2, this.recorded.reports.get(0).counter());
        assertEquals(SECURITY_B, this.recorded.reports.get(0).securityId());
        assertEquals(1, this.recorded.reports.get(1).counter());
        assertEquals(2, this.recorded.reports.get(2).counter());
        assertEquals(SESSION, this.recorded.reports.get(2).slot());
    }

    @Test
    void iocsWithTheSameTermsOnTwoMarketsAreMatchedByMarket() {
        immediate(1, SECURITY_A);
        immediate(2, SECURITY_B);
        // The older IOC is on the other market: matched by terms alone, it would take this fill.
        frame(fill(order("I2", SLUG_B, "ORDER_STATE_FILLED", "0.55", 1, 1, "0.5500", null, true), "1.0000"));

        assertEquals(List.of(ExecType.NEW, ExecType.FILL), this.recorded.execTypes(), "acknowledged, then filled");
        assertEquals(2, this.recorded.reports.get(0).counter());
        assertEquals(2, this.recorded.reports.get(1).counter());
        assertEquals(List.of(2L), this.recorded.released);
        assertEquals(1, this.reports.size(), "the IOC on the other market still waits for its own events");
    }

    @Test
    void catchUpListsTheAccountsOpenOrdersAndLooksUpHeldOrdersNoLongerListed() throws Exception {
        resting(1, SECURITY_A, "3");
        resting(2, SECURITY_B, "3");
        venueId(1, "V1");
        venueId(2, "V2");
        this.restAnswers.put(
                "/v1/orders/open",
                "{\"orders\":[" + order("V1", SLUG_A, "ORDER_STATE_NEW", "0.55", 3, 0, "0", stamp(1), false) + "]}");
        this.restAnswers.put(
                "/v1/order/V2",
                "{\"order\":" + order("V2", SLUG_B, "ORDER_STATE_CANCELED", "0.55", 3, 0, "0", stamp(2), false) + "}");

        this.protocol.fetchCatchUp(0, this.reports.held());
        this.protocol.applyCatchUp(this.reports);

        assertEquals("/v1/orders/open", this.restPaths.get(0), "every market's open orders, unfiltered");
        assertEquals(List.of(ExecType.NEW, ExecType.CANCEL), this.recorded.execTypes());
        assertFalse(this.protocol.wantsFollowUp(), "both held orders were covered");
    }

    @Test
    void ordersTheOpenListingNoLongerShowsAreLookedUpSeveralAtATime() throws Exception {
        final PolymarketUsAccountProtocol parallel = new PolymarketUsAccountProtocol(
                new NullLogger(), this.signer, List.of(query(), query(), query()), SystemEpochNanoClock.INSTANCE, 64);
        parallel.addMarket(EXCHANGE_ID, SECURITY_A, listing(SECURITY_A, SLUG_A));
        this.reports.venueIdListener(parallel::onVenueOrderId);
        this.restAnswers.put("/v1/orders/open", "{\"orders\":[]}");
        for (int counter = 1; counter <= 9; counter++) {
            resting(counter, SECURITY_A, "3");
            venueId(counter, "V" + counter);
            this.restAnswers.put(
                    "/v1/order/V" + counter,
                    "{\"order\":"
                            + order(
                                    "V" + counter,
                                    SLUG_A,
                                    "ORDER_STATE_CANCELED",
                                    "0.55",
                                    3,
                                    0,
                                    "0",
                                    stamp(counter),
                                    false)
                            + "}");
        }
        this.answerDelayMillis = 200;

        final long start = System.nanoTime();
        parallel.fetchCatchUp(0, this.reports.held());
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        parallel.applyCatchUp(this.reports);

        assertEquals(10, this.restPaths.size(), "the open listing, then each of the nine");
        assertTrue(elapsedMillis < 9 * 200, "three at a time, not one after another: " + elapsedMillis + " ms");
        assertEquals(
                9,
                this.recorded.execTypes().stream()
                        .filter(t -> t == ExecType.CANCEL)
                        .count());
        assertFalse(parallel.wantsFollowUp(), "every one was caught up");
    }

    /** A query on a client and signer of its own, as each catch-up thread has in a gateway. */
    private PolymarketUsVenueOrderQuery query() throws Exception {
        final HTTPClient client = mock(HTTPClient.class);
        stubRest(client);
        final PolymarketUsAuthSigner ownSigner = new PolymarketUsAuthSigner(
                "key", PolymarketUsAuthSigner.parseSecretKey(Base64.getEncoder().encodeToString(new byte[32])));
        return new PolymarketUsVenueOrderQuery(client, "api.test", ownSigner, System::currentTimeMillis);
    }

    @Test
    void anOrderSentWhileTheSocketWasDownIsLookedUpInAFollowUp() throws Exception {
        this.restAnswers.put("/v1/orders/open", "{\"orders\":[]}");
        this.protocol.fetchCatchUp(0, this.reports.held());
        // Drained by the reader only after the fetch, with the id the lane learned from its answer.
        resting(3, SECURITY_A, "3");
        venueId(3, "V3");
        this.protocol.applyCatchUp(this.reports);
        assertTrue(this.protocol.wantsFollowUp());
        assertTrue(this.recorded.reports.isEmpty());

        this.restAnswers.put(
                "/v1/order/V3",
                "{\"order\":" + order("V3", SLUG_A, "ORDER_STATE_FILLED", "0.55", 3, 3, "0.5500", stamp(3), false)
                        + "}");
        this.protocol.fetchFollowUp();
        assertFalse(this.protocol.wantsFollowUp());
        this.protocol.applyFollowUp(this.reports);
        assertEquals(List.of(ExecType.NEW, ExecType.FILL), this.recorded.execTypes());
        assertEquals(List.of(3L), this.recorded.released);
    }

    private void resting(final long counter, final long securityId, final String size) {
        final OrderContext ctx =
                RecordedReports.order(SESSION, counter, EXCHANGE_ID, securityId, stamp(counter), qty(size));
        ctx.side = Side.Bid;
        ctx.venuePrice = price("0.55");
        this.reports.register(ctx);
    }

    private void immediate(final long counter, final long securityId) {
        final OrderContext ctx = RecordedReports.order(
                SESSION, counter, EXCHANGE_ID, securityId, "ioc-" + SLOT + "-" + counter, qty("1"));
        ctx.side = Side.Bid;
        ctx.venuePrice = price("0.55");
        this.reports.register(ctx);
    }

    /** The lane's notice of the venue's id, from the submit's answer. */
    private void venueId(final long counter, final String id) {
        final ManyToOneRingBuffer<OrderContext> queue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 4);
        final OrderContext ctx = RecordedReports.order(SESSION, counter, EXCHANGE_ID, 0, stamp(counter), 0);
        final byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, ctx.exchangeOrderIdBytes, 0, bytes.length);
        ctx.exchangeOrderIdLength = bytes.length;
        new ReaderNotices(queue).venueOrderId(ctx);
        queue.read(this.reports::onNotice, 1);
    }

    private void frame(final String json) {
        this.reports.receivedAt(System.nanoTime());
        this.protocol.onFrame(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), this.reports);
    }

    private static String stamp(final long counter) {
        return PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(SLOT, counter));
    }

    private static Listing listing(final long securityId, final String slug) {
        return new Listing(
                (int) securityId,
                new Exchange(EXCHANGE_ID, "POLYMARKET_US", "Polymarket US", "us"),
                new Security((int) securityId, slug, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                slug + ":long",
                slug);
    }

    private static String execution(final String type, final String order) {
        return "{\"requestId\":\"orders\", \"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\", "
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\", \"order\":" + order
                + ", \"lastShares\":\"0.0000\", \"lastPx\":{\"value\":\"0.0000\", \"currency\":\"USD\"}, \"type\":\""
                + type + "\", \"text\":\"\", \"tradeId\":\"\", \"aggressor\":false, "
                + "\"transactTime\":\"2026-10-08T14:33:25.874628732Z\"}}}";
    }

    private static String fill(final String order, final String lastShares) {
        return "{\"requestId\":\"orders\", \"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\", "
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\", \"order\":" + order
                + ", \"lastShares\":\"" + lastShares + "\", \"lastPx\":{\"value\":\"0.5500\", \"currency\":\"USD\"}, "
                + "\"type\":\"EXECUTION_TYPE_FILL\", \"tradeId\":\"T\", \"aggressor\":false, "
                + "\"transactTime\":\"2026-10-08T14:37:56.769167653Z\"}}}";
    }

    private static String order(
            final String id,
            final String slug,
            final String state,
            final String price,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime,
            final boolean immediate) {
        return "{\"id\":\"" + id + "\", \"marketSlug\":\"" + slug + "\", \"side\":\"ORDER_SIDE_BUY\", "
                + "\"type\":\"ORDER_TYPE_LIMIT\", \"price\":{\"value\":\"" + price + "\", \"currency\":\"USD\"}, "
                + "\"quantity\":" + quantity + ", \"cumQuantity\":" + cumQuantity + ", \"leavesQuantity\":"
                + (quantity - cumQuantity) + ", \"tif\":\""
                + (immediate ? "TIME_IN_FORCE_IMMEDIATE_OR_CANCEL" : "TIME_IN_FORCE_GOOD_TILL_DATE")
                + "\", \"goodTillTime\":" + (goodTillTime == null ? "null" : "\"" + goodTillTime + "\"")
                + ", \"intent\":\"ORDER_INTENT_BUY_LONG\", \"state\":\"" + state + "\", "
                + "\"commissionNotionalTotalCollected\":{\"value\":\"0.0000\", \"currency\":\"USD\"}, "
                + "\"avgPx\":{\"value\":\"" + avgPx + "\", \"currency\":\"USD\"}, "
                + "\"createTime\":\"2026-10-08T14:33:25.872677584Z\"}";
    }

    private void stubRest() throws Exception {
        stubRest(this.restClient);
    }

    private void stubRest(final HTTPClient client) throws Exception {
        when(client.get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(call -> {
                    final String path = call.getArgument(2);
                    this.restPaths.add(path);
                    if (this.answerDelayMillis > 0) {
                        Thread.sleep(this.answerDelayMillis);
                    }
                    final String body = this.restAnswers.get(path);
                    final HTTPResponse answer = mock(HTTPResponse.class);
                    when(answer.isSuccess()).thenReturn(body != null);
                    when(answer.getStatusCode()).thenReturn(body != null ? 200 : 404);
                    when(answer.getBody())
                            .thenReturn(ByteBuffer.wrap((body != null ? body : "{}").getBytes(StandardCharsets.UTF_8)));
                    return answer;
                });
    }

    private static long qty(final String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long price(final String value) {
        return new java.math.BigDecimal(value)
                .multiply(java.math.BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }
}
