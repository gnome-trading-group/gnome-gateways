package group.gnometrading.gateways.venues.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Side;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Polymarket International's account-wide {@code user} channel: orders on two markets, and catch-up. */
class PolymarketIntlAccountProtocolTest {

    private static final int EXCHANGE_ID = 2;
    private static final long MARKET_A = 3;
    private static final long MARKET_B = 4;
    private static final String ORDER_A = "0x" + "aa".repeat(32);
    private static final String ORDER_B = "0x" + "bb".repeat(32);
    // What the CLOB answered a lookup with under load: too short for the order object the reader expects.
    private static final String UNREADABLE = "{\"id\"";
    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==";

    private final HTTPClient restClient = mock(HTTPClient.class);
    private final Map<String, String> restAnswers = new HashMap<>();
    private final List<String> restPaths = new ArrayList<>();
    private final RecordedReports recorded = new RecordedReports();
    private final OrderReports reports = new OrderReports(64, SystemEpochNanoClock.INSTANCE, this.recorded);
    private PolymarketIntlAccountProtocol protocol;

    @BeforeEach
    void setUp() throws Exception {
        final PolymarketIntlAuthHeaders auth = new PolymarketIntlAuthHeaders("key", SECRET, "pass", "0xabc");
        this.protocol = new PolymarketIntlAccountProtocol(
                new NullLogger(),
                "key",
                SECRET,
                "pass",
                new PolymarketIntlVenueOrderQuery(this.restClient, "clob.test", auth),
                SystemEpochNanoClock.INSTANCE);
        this.protocol.addMarket(EXCHANGE_ID, MARKET_A, "token-a", FeeTimeline.EMPTY);
        this.protocol.addMarket(EXCHANGE_ID, MARKET_B, "token-b", FeeTimeline.EMPTY);
        when(this.restClient.get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
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
                    String body = null;
                    for (final Map.Entry<String, String> answer : this.restAnswers.entrySet()) {
                        if (path.startsWith(answer.getKey())) {
                            body = answer.getValue();
                        }
                    }
                    final HTTPResponse answer = mock(HTTPResponse.class);
                    when(answer.isSuccess()).thenReturn(body != null);
                    when(answer.getStatusCode()).thenReturn(body != null ? 200 : 404);
                    when(answer.getBody())
                            .thenReturn(ByteBuffer.wrap((body != null ? body : "{}").getBytes(StandardCharsets.UTF_8)));
                    return answer;
                });
    }

    @Test
    void eventsOnTwoMarketsAreReportedToTheirOwnOrders() {
        register(7, 1, MARKET_A, ORDER_A, 0);
        register(8, 1, MARKET_B, ORDER_B, 0);
        frame(orderEvent(ORDER_B, "token-b"));
        frame(orderEvent(ORDER_A, "token-a"));
        frame(trade(ORDER_B, "token-b", "3"));

        assertEquals(List.of(ExecType.NEW, ExecType.NEW, ExecType.PARTIAL_FILL), this.recorded.execTypes());
        assertEquals(8, this.recorded.reports.get(0).slot());
        assertEquals(7, this.recorded.reports.get(1).slot());
        assertEquals(8, this.recorded.reports.get(2).slot());
        assertEquals(3_000_000L, this.recorded.reports.get(2).filledQty());
    }

    @Test
    void aCancellationThatMatchedMoreThanTheTradesSeenWaitsForThem() {
        register(7, 1, MARKET_A, ORDER_A, 0);
        frame(orderEvent(ORDER_A, "token-a"));
        // Its trade fell in a socket gap: the cancellation arrives first, saying 3 matched.
        frame(cancellation(ORDER_A, "token-a", "3"));
        assertEquals(List.of(ExecType.NEW), this.recorded.execTypes(), "not ended short of what it matched");

        frame(trade(ORDER_A, "token-a", "3"));
        assertEquals(List.of(ExecType.NEW, ExecType.PARTIAL_FILL, ExecType.CANCEL), this.recorded.execTypes());
        assertEquals(3_000_000L, this.recorded.reports.get(2).cumulativeQty());
        assertEquals(List.of(1L), this.recorded.released, "and released once its trades are in");
    }

    @Test
    void aCancellationWithNothingMoreMatchedEndsAtOnce() {
        register(7, 1, MARKET_A, ORDER_A, 0);
        frame(orderEvent(ORDER_A, "token-a"));
        frame(cancellation(ORDER_A, "token-a", "0"));
        assertEquals(List.of(ExecType.NEW, ExecType.CANCEL), this.recorded.execTypes());
    }

    @Test
    void textPongsAreSkipped() {
        frame("PONG");
        assertTrue(this.recorded.reports.isEmpty());
    }

    @Test
    void catchUpAppliesTheAccountsTradesThenEndsOrdersTheVenueNoLongerLists() throws Exception {
        final long longAgo = System.currentTimeMillis() - 60_000;
        register(7, 1, MARKET_A, ORDER_A, longAgo);
        register(8, 1, MARKET_B, ORDER_B, longAgo);
        this.restAnswers.put(
                "/data/trades",
                "{\"data\":[" + restTrade(ORDER_A, "token-a", "3")
                        + "],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":1}");
        this.restAnswers.put(
                "/data/orders",
                "{\"data\":[" + restOrder(ORDER_A, "LIVE", "3")
                        + "],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":1}");
        this.restAnswers.put("/data/order/" + ORDER_B, restOrder(ORDER_B, "CANCELED", "0"));

        this.protocol.fetchCatchUp(1_800_000_000_000L, this.reports.held());
        this.protocol.applyCatchUp(this.reports);

        assertEquals("/data/trades?after=1799999940", this.restPaths.get(0), "every market's trades");
        assertEquals("/data/orders", this.restPaths.get(1), "every market's open orders");
        assertEquals(List.of(ExecType.NEW, ExecType.PARTIAL_FILL, ExecType.CANCEL), this.recorded.execTypes());
        assertEquals(7, this.recorded.reports.get(1).slot(), "A's fill while the socket was down");
        assertEquals(8, this.recorded.reports.get(2).slot(), "B ended with nothing filled");
    }

    @Test
    void anOrderTooYoungToJudgeAtCatchUpIsLookedUpAgainOnceItIsAndEndedIfItFinished() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        // Cancelled at the venue while the socket was down, seconds after it was placed.
        register(8, 1, MARKET_B, ORDER_B, nowMillis[0] - 2_000);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/order/" + ORDER_B, restOrder(ORDER_B, "CANCELED", "0"));

        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);
        assertFalse(this.recorded.execTypes().contains(ExecType.CANCEL), "too young to judge yet");
        assertFalse(clocked.wantsFollowUp());

        nowMillis[0] += 4_000;
        assertTrue(clocked.wantsFollowUp(), "old enough now");
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertEquals(
                ExecType.CANCEL,
                this.recorded.execTypes().get(this.recorded.execTypes().size() - 1));
        assertEquals(
                8, this.recorded.reports.get(this.recorded.reports.size() - 1).slot());
        assertFalse(clocked.wantsFollowUp(), "looked up once");
    }

    @Test
    void anOrderTooYoungToJudgeThatTurnsOutListedIsLeftOpen() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        register(8, 1, MARKET_B, ORDER_B, nowMillis[0] - 2_000);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);

        // The list had lagged it: by the follow-up it shows.
        this.restAnswers.put(
                "/data/orders",
                "{\"data\":[" + restOrder(ORDER_B, "LIVE", "0")
                        + "],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":1}");
        nowMillis[0] += 4_000;
        assertTrue(clocked.wantsFollowUp());
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertFalse(this.recorded.execTypes().contains(ExecType.CANCEL));
        assertFalse(this.restPaths.contains("/data/order/" + ORDER_B), "no lookup for a listed order");
    }

    @Test
    void anOrderTheVenueAnswersNullForIsLeftOpenAndNotLookedUpAgain() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        register(8, 1, MARKET_B, ORDER_B, nowMillis[0] - 2_000);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/order/" + ORDER_B, "null");
        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);

        nowMillis[0] += 4_000;
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertFalse(this.recorded.execTypes().contains(ExecType.CANCEL), "not known is not ended");
        assertFalse(clocked.wantsFollowUp(), "a lookup that answered is done");
    }

    @Test
    void anUnreadableLookupHoldsUpNoOtherOrderAndIsGivenUpOnAfterThreeTries() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        register(7, 1, MARKET_A, ORDER_A, nowMillis[0] - 2_000);
        register(8, 1, MARKET_B, ORDER_B, nowMillis[0] - 2_000);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/order/" + ORDER_A, UNREADABLE);
        this.restAnswers.put("/data/order/" + ORDER_B, restOrder(ORDER_B, "CANCELED", "0"));
        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);

        nowMillis[0] += 4_000;
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertEquals(List.of(ExecType.CANCEL), this.recorded.execTypes(), "the other order ended all the same");
        assertEquals(8, this.recorded.reports.get(0).slot());
        assertFalse(clocked.wantsFollowUp(), "tried again only after a grace");

        for (int attempt = 2; attempt <= 3; attempt++) {
            nowMillis[0] += 5_000;
            assertTrue(clocked.wantsFollowUp(), "try " + attempt);
            clocked.fetchFollowUp();
        }
        nowMillis[0] += 5_000;
        assertFalse(clocked.wantsFollowUp(), "given up on");
        assertEquals(
                3,
                this.restPaths.stream()
                        .filter(path -> path.equals("/data/order/" + ORDER_A))
                        .count());
    }

    @Test
    void anOrderCatchUpCantReadFailsNoConnectAndIsAskedAboutAgainLater() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        register(7, 1, MARKET_A, ORDER_A, nowMillis[0] - 10_000);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/order/" + ORDER_A, UNREADABLE);

        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);
        assertFalse(clocked.wantsFollowUp(), "asked again only after a grace");

        this.restAnswers.put("/data/order/" + ORDER_A, restOrder(ORDER_A, "CANCELED", "0"));
        nowMillis[0] += 5_000;
        assertTrue(clocked.wantsFollowUp());
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertEquals(List.of(ExecType.CANCEL), this.recorded.execTypes(), "ended once the venue could answer");
    }

    @Test
    void aFollowUpWhoseListingFailsWaitsAGraceBeforeAskingAgain() throws Exception {
        final long[] nowMillis = {System.currentTimeMillis()};
        final PolymarketIntlAccountProtocol clocked = clockedProtocol(nowMillis);
        register(7, 1, MARKET_A, ORDER_A, nowMillis[0]);
        this.restAnswers.put("/data/trades", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        clocked.fetchCatchUp(nowMillis[0] - 10_000, this.reports.held());
        clocked.applyCatchUp(this.reports);

        this.restAnswers.remove("/data/orders");
        nowMillis[0] += 6_000;
        assertTrue(clocked.wantsFollowUp());
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertFalse(clocked.wantsFollowUp(), "not asked about again on the next pass");

        this.restAnswers.put("/data/orders", "{\"data\":[],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":0}");
        this.restAnswers.put("/data/order/" + ORDER_A, restOrder(ORDER_A, "CANCELED", "0"));
        nowMillis[0] += 6_000;
        assertTrue(clocked.wantsFollowUp());
        clocked.fetchFollowUp();
        clocked.applyFollowUp(this.reports);
        assertEquals(List.of(ExecType.CANCEL), this.recorded.execTypes());
    }

    private PolymarketIntlAccountProtocol clockedProtocol(final long[] nowMillis) {
        final PolymarketIntlAccountProtocol clocked = new PolymarketIntlAccountProtocol(
                new NullLogger(),
                "key",
                SECRET,
                "pass",
                new PolymarketIntlVenueOrderQuery(
                        this.restClient, "clob.test", new PolymarketIntlAuthHeaders("key", SECRET, "pass", "0xabc")),
                () -> nowMillis[0] * 1_000_000L);
        clocked.addMarket(EXCHANGE_ID, MARKET_A, "token-a", FeeTimeline.EMPTY);
        clocked.addMarket(EXCHANGE_ID, MARKET_B, "token-b", FeeTimeline.EMPTY);
        return clocked;
    }

    private void register(
            final long slot, final long counter, final long market, final String hash, final long submittedAt) {
        final OrderContext ctx = RecordedReports.order(slot, counter, EXCHANGE_ID, market, hash, 10_000_000L);
        ctx.side = Side.Bid;
        final byte[] id = hash.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, ctx.exchangeOrderIdBytes, 0, id.length);
        ctx.exchangeOrderIdLength = id.length;
        if (submittedAt > 0) {
            ctx.submittedAtMillis = submittedAt;
        }
        this.reports.register(ctx);
    }

    private void frame(final String json) {
        this.reports.receivedAt(System.nanoTime());
        this.protocol.onFrame(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), this.reports);
    }

    private static String orderEvent(final String hash, final String token) {
        return "{\"asset_id\":\"" + token + "\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\""
                + hash + "\",\"market\":\"condition-1\",\"original_size\":\"10\",\"outcome\":\"YES\","
                + "\"owner\":\"key\",\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":\"0\","
                + "\"status\":\"LIVE\",\"timestamp\":\"1700000000000\",\"type\":\"PLACEMENT\"}";
    }

    private static String cancellation(final String hash, final String token, final String sizeMatched) {
        return "{\"asset_id\":\"" + token + "\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\""
                + hash + "\",\"market\":\"condition-1\",\"original_size\":\"10\",\"outcome\":\"YES\","
                + "\"owner\":\"key\",\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":\"" + sizeMatched + "\","
                + "\"status\":\"CANCELED\",\"timestamp\":\"1700000000000\",\"type\":\"CANCELLATION\"}";
    }

    private static String trade(final String takerHash, final String token, final String size) {
        return "{\"asset_id\":\"" + token + "\",\"event_type\":\"trade\",\"fee_rate_bps\":\"0\",\"id\":\"trade-"
                + takerHash.substring(2, 6) + "\",\"maker_orders\":[],\"market\":\"condition-1\",\"outcome\":\"YES\","
                + "\"owner\":\"key\",\"price\":\"0.55\",\"side\":\"BUY\",\"size\":\"" + size + "\","
                + "\"status\":\"MATCHED\",\"taker_order_id\":\"" + takerHash + "\","
                + "\"timestamp\":\"1700000000000\",\"trader_side\":\"TAKER\",\"type\":\"TRADE\"}";
    }

    private static String restTrade(final String takerHash, final String token, final String size) {
        return "{\"id\":\"trade-rest\",\"taker_order_id\":\"" + takerHash + "\",\"market\":\"condition-1\","
                + "\"asset_id\":\"" + token + "\",\"side\":\"BUY\",\"size\":\"" + size + "\",\"fee_rate_bps\":\"0\","
                + "\"price\":\"0.55\",\"status\":\"CONFIRMED\",\"match_time\":\"1700000000\","
                + "\"last_update\":\"1700000005\",\"outcome\":\"YES\",\"maker_address\":\"0xabc\","
                + "\"owner\":\"key\",\"transaction_hash\":\"0x01\",\"bucket_index\":0,\"maker_orders\":[],"
                + "\"trader_side\":\"TAKER\"}";
    }

    private static String restOrder(final String hash, final String status, final String sizeMatched) {
        return "{\"id\":\"" + hash + "\",\"status\":\"" + status + "\",\"owner\":\"key\","
                + "\"maker_address\":\"0xabc\",\"market\":\"condition-1\",\"asset_id\":\"token-a\","
                + "\"side\":\"BUY\",\"original_size\":\"10\",\"size_matched\":\"" + sizeMatched + "\","
                + "\"price\":\"0.55\",\"outcome\":\"YES\",\"created_at\":1699999000,\"order_type\":\"GTC\"}";
    }
}
