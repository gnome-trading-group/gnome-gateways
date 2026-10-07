package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundWriter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strings.GnomeString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KalshiOutboundWriterTest {

    private static final String MARKET_TICKER = "KALSHI-MARKET";
    private static final String API_HOST = "external-api.kalshi.com";
    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final String SESSION_TAG = "4071";
    private static final long LISTING_ID = 1;
    private static final String ORDER_PATH = "/trade-api/v2/portfolio/events/orders";
    private static final String ORDERS_LOOKUP_PATH = "/trade-api/v2/portfolio/orders";
    private static final String MARKET_PATH = "/trade-api/v2/markets/";
    private static final String STATUS_PATH = "/trade-api/v2/exchange/status";

    private static final KeyPair TEST_KEYS;
    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            TEST_KEYS = gen.generateKeyPair();
            TEST_PRIVATE_KEY = TEST_KEYS.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private HTTPClient httpClient;
    private HTTPResponse httpResponse;
    private Logger logger;
    private KalshiOutboundWriter writer;
    // GETs answered by path prefix; the last response for a prefix repeats.
    private final Map<String, Deque<HTTPResponse>> getRoutes = new LinkedHashMap<>();
    private final List<String> getPaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        httpClient = mock(HTTPClient.class);
        httpResponse = mock(HTTPResponse.class);
        logger = mock(Logger.class);
        when(httpClient.get(
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
                    getPaths.add(path);
                    for (final Map.Entry<String, Deque<HTTPResponse>> route : getRoutes.entrySet()) {
                        if (path.startsWith(route.getKey())) {
                            final Deque<HTTPResponse> queue = route.getValue();
                            return queue.size() > 1 ? queue.poll() : queue.peek();
                        }
                    }
                    return response(404, "{}");
                });

        writer = makeWriter(MARKET_TICKER + ":yes", orderBuffer, releasedOrderQueue);
    }

    // ========== Orders whose submit had no clear answer ==========

    @Test
    void lookup_FollowsTheCursorUntilItFindsTheOrder() throws Exception {
        mockPostStatuses(0, 409);
        mockGetOrders(
                "{\"orders\":[{\"order_id\":\"someone-else\",\"client_order_id\":\"other\"}],\"cursor\":\"c2\"}",
                "{\"orders\":[{\"order_id\":\"kalshi-order-found\",\"client_order_id\":\"" + SESSION_TAG
                        + "-1\"}],\"cursor\":\"\"}");

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size(), "found on the second page, so not rejected");
        assertTrue(getPaths.get(1).contains("&cursor=c2"), getPaths.toString());
        assertTrue(getPaths.get(0).contains("&limit=200"), getPaths.toString());
    }

    @Test
    void cancel_VenueIdNotYetKnown_LooksTheOrderUpFirst() throws Exception {
        mockPostStatuses(0, 0, 0);
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockGetOrders("{\"orders\":[{\"order_id\":\"kalshi-order-late\",\"client_order_id\":\"" + SESSION_TAG
                + "-1\"}],\"cursor\":\"\"}");
        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();

        assertTrue(lastDeletePath().startsWith(ORDER_PATH + "/kalshi-order-late?"), lastDeletePath());
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void cancel_VenueIdUnknownAndNotFound_IsRefusedWithoutSendingAnEmptyId() throws Exception {
        mockPostStatuses(0, 0, 0);
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockGetOrders("{\"orders\":[],\"cursor\":\"\"}");
        publishCancel(clientOidCounter);
        writer.doWork();

        verify(httpClient, never())
                .delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.UNKNOWN, reports.get(0).rejectReason, "the venue was never asked");
    }

    @Test
    void amend_VenueIdUnknownAndNotFound_IsRefusedWithoutSendingAnEmptyId() throws Exception {
        mockPostStatuses(0, 0, 0);
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockGetOrders("{\"orders\":[],\"cursor\":\"\"}");
        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        assertFalse(captureLastPostBody().contains("0.6000"), "no amend was sent");
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.UNKNOWN, reports.get(0).rejectReason);
    }

    @Test
    void submit_503WhileTheShardIsHalted_IsRejected() throws Exception {
        startOnShard(2);
        route(
                STATUS_PATH,
                200,
                "{\"exchange_active\":true,\"exchange_index_statuses\":["
                        + "{\"exchange_active\":true,\"exchange_index\":0,\"trading_active\":true},"
                        + "{\"exchange_active\":true,\"exchange_index\":2,\"trading_active\":false}],"
                        + "\"trading_active\":true}");
        mockPostStatuses(503);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size(), "Kalshi didn't take it, so it is not left in limbo");
        assertEquals(ExecType.REJECT, reports.get(0).execType);
    }

    @Test
    void submit_503WhileTheWholeExchangeIsDown_IsRejected() throws Exception {
        // As demo answered during an outage on 2026-10-07: the status endpoint itself is a 503, with no shard list.
        startOnShard(0);
        route(STATUS_PATH, 503, "{\"exchange_active\":false,\"trading_active\":false}");
        mockPostStatuses(503);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size(), "Kalshi says nothing is trading, so the order isn't left in limbo");
        assertEquals(ExecType.REJECT, reports.get(0).execType);
    }

    @Test
    void submit_503AndTheStatusCantBeRead_IsStillUnknown() throws Exception {
        startOnShard(0);
        route(STATUS_PATH, 502, "<html>bad gateway</html>");
        mockPostStatuses(503, 503, 503);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size(), "it may be live");
    }

    @Test
    void submit_503WhileTheShardIsTrading_IsStillUnknown() throws Exception {
        startOnShard(2);
        route(
                STATUS_PATH,
                200,
                "{\"exchange_active\":true,\"exchange_index_statuses\":["
                        + "{\"exchange_active\":true,\"exchange_index\":2,\"trading_active\":true}],"
                        + "\"trading_active\":true}");
        mockPostStatuses(503, 503, 503);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size(), "it may be live");
    }

    // ========== Exchange shards ==========

    @Test
    void submitAndAmend_CarryTheMarketsShard() throws Exception {
        startOnShard(2);
        mockPostSuccess(orderResponse("kalshi-order-shard"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        assertTrue(captureLastPostBody().contains("\"exchange_index\":2"), captureLastPostBody());
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(amendResponse("kalshi-order-shard", "0.00", "5.00"));
        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final String amend = captureLastPostBody();
        assertTrue(amend.contains("0.6000") && amend.contains("\"exchange_index\":2"), amend);
    }

    @Test
    void cancel_RoutesByTickerAndShard_AndSignsThePathWithoutItsQuery() throws Exception {
        startOnShard(2);
        mockPostSuccess(orderResponse("kalshi-order-route"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();

        final String basePath = ORDER_PATH + "/kalshi-order-route";
        assertEquals(basePath + "?market_ticker=" + MARKET_TICKER + "&exchange_index=2", lastDeletePath());
        final ArgumentCaptor<String> headers = ArgumentCaptor.forClass(String.class);
        verify(httpClient)
                .delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        headers.capture(),
                        headers.capture(),
                        headers.capture(),
                        headers.capture(),
                        headers.capture(),
                        headers.capture());
        final List<String> values = headers.getAllValues();
        final Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(TEST_KEYS.getPublic());
        verifier.update((values.get(3) + "DELETE" + basePath).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(values.get(5))), "Kalshi signs the path, not the query");
    }

    @Test
    void marketLookupFails_OrdersRouteByTickerAlone() throws Exception {
        route(MARKET_PATH + MARKET_TICKER, 500, "{}");
        writer.onStart();
        mockPostSuccess(orderResponse("kalshi-order-auto"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        assertFalse(captureLastPostBody().contains("exchange_index"), captureLastPostBody());
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();

        assertEquals(ORDER_PATH + "/kalshi-order-auto?market_ticker=" + MARKET_TICKER, lastDeletePath());
    }

    // ========== Rejections ==========

    @Test
    void rejection_IsLoggedWithKalshisReason() throws Exception {
        mockPostFailure();
        when(httpResponse.getStatusCode()).thenReturn(400);
        when(httpResponse.getBody())
                .thenReturn(ByteBuffer.wrap(
                        "{\"error\":{\"code\":\"insufficient_balance\"}}".getBytes(StandardCharsets.UTF_8)));

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final Object[] call = mockingDetails(logger).getInvocations().stream()
                .filter(invocation -> invocation.getArgument(0) == LogMessage.ORDER_REJECTED_BY_VENUE)
                .findFirst()
                .orElseThrow()
                .getRawArguments();
        final String line = String.format((String) call[1], (Object[]) call[2]);
        assertTrue(line.contains("400") && line.contains("insufficient_balance"), line);
    }

    // ========== Submit order ==========

    @Test
    void submitOrder_RegistersClientOrderIdBeforeSending_AndSendsIt() throws Exception {
        mockPostSuccess(orderResponse("kalshi-order-uuid-001"));

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final OrderContext registered = drainQueue(newOrderQueue).get(0);
        // Session 4071, the OMS's first order.
        final String clientOrderId = SESSION_TAG + "-1";
        assertEquals(clientOrderId, correlationId(registered));
        assertTrue(captureLastPostBody().contains("\"client_order_id\":\"" + clientOrderId + "\""));
    }

    @Test
    void clientOrderIds_CarryTheWholeCounter() throws Exception {
        mockPostSuccess(orderResponse("kalshi-order-uuid-003"));

        publishTo(
                orderBuffer,
                Side.Bid,
                price("0.50"),
                qty("10.0"),
                OrderType.LIMIT,
                TimeInForce.GOOD_TILL_CANCELED,
                9_040_000_000_123L);
        writer.doWork();

        assertEquals(
                SESSION_TAG + "-9040000000123",
                correlationId(drainQueue(newOrderQueue).get(0)));
    }

    @Test
    void clientOrderIds_DifferAcrossSessions() throws Exception {
        final SequencedRingBuffer<Order> laterBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        final KalshiOutboundWriter laterRun = makeWriter(
                MARKET_TICKER + ":yes",
                laterBuffer,
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64),
                "4072");
        mockPostSuccess(orderResponse("kalshi-order-uuid-002"));

        publishTo(laterBuffer, Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        laterRun.doWork();

        final String laterId = correlationId(drainQueue(newOrderQueue).get(0));
        assertEquals("4072-1", laterId, "same counter, new session");
    }

    @Test
    void lostResponse_DuplicateRefusal_FoundInOpenOrders_IsAcceptedWithVenueId() throws Exception {
        mockPostStatuses(0, 409);
        mockGetOrders("{\"orders\":[{\"order_id\":\"someone-else\",\"client_order_id\":\"other\"},"
                + "{\"order_id\":\"kalshi-order-found\",\"client_order_id\":\"" + SESSION_TAG + "-1\"}],"
                + "\"cursor\":\"\"}");
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size());
        final ArgumentCaptor<String> lookup = ArgumentCaptor.forClass(String.class);
        verify(httpClient)
                .get(
                        any(),
                        anyString(),
                        lookup.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertTrue(lookup.getValue().startsWith("/trade-api/v2/portfolio/orders?ticker=" + MARKET_TICKER + "&min_ts="));
        final ArgumentCaptor<GnomeString> cancelPath = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient)
                .delete(
                        any(),
                        anyString(),
                        cancelPath.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertTrue(cancelPath.getValue().toString().contains("kalshi-order-found"));
    }

    @Test
    void lostResponse_DuplicateRefusalButNotListedYet_StaysUnknown() throws Exception {
        // 409 on the identical resend: Kalshi has it, even if its order list doesn't show it yet.
        mockPostStatuses(0, 409);
        mockGetOrders("{\"orders\":[],\"cursor\":\"\"}");

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size(), "a live order is never reported rejected");
    }

    @Test
    void lookup_EncodesTheCursor() throws Exception {
        mockPostStatuses(0, 409);
        mockGetOrders(
                "{\"orders\":[],\"cursor\":\"a+b/c=\"}",
                "{\"orders\":[{\"order_id\":\"kalshi-order-found\",\"client_order_id\":\"" + SESSION_TAG
                        + "-1\"}],\"cursor\":\"\"}");

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertTrue(getPaths.get(1).endsWith("&cursor=a%2Bb%2Fc%3D"), getPaths.toString());
    }

    @Test
    void lostResponse_RefusalAndNotInOpenOrders_IsRejected() throws Exception {
        mockPostStatuses(502, 400);
        mockGetOrders("{\"orders\":[],\"cursor\":\"\"}");

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
    }

    @Test
    void submitOrder_ClientError_RegisteredThenRejected() throws Exception {
        mockPostFailure();
        when(httpResponse.getStatusCode()).thenReturn(400);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertEquals(1, drainQueue(newOrderQueue).size());
        final List<OrderContext> rejects = drainQueue(writerReportQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, rejects.get(0).rejectReason);
    }

    @Test
    void submitOrder_SendsCorrectHeaderNames() throws Exception {
        mockPostSuccess(orderResponse("order-header-test"));

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verify(httpClient)
                .post(
                        eq(HTTPProtocol.HTTPS),
                        eq(API_HOST),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        eq("KALSHI-ACCESS-KEY"),
                        anyString(),
                        eq("KALSHI-ACCESS-TIMESTAMP"),
                        anyString(),
                        eq("KALSHI-ACCESS-SIGNATURE"),
                        anyString(),
                        eq("Content-Type"),
                        eq("application/json"));
    }

    @Test
    void submitOrder_YesListing_BidSide_JsonBodyCorrect() throws Exception {
        mockPostSuccess(orderResponse("order-yes-bid"));

        publishOrder(Side.Bid, price("0.56"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String json = captureLastPostBody();
        assertTrue(json.contains("\"ticker\":\"" + MARKET_TICKER + "\""), json);
        assertTrue(json.contains("\"side\":\"bid\""), json);
        assertTrue(json.contains("\"price\":\"0.5600\""), json);
        assertTrue(json.contains("\"count\":\"10.00\""), json);
        assertTrue(json.contains("\"time_in_force\":\"good_till_canceled\""), json);
        assertTrue(json.contains("\"self_trade_prevention_type\":\"maker\""), json);
    }

    @Test
    void submitOrder_NoListing_BidFlipsToAsk_PriceInverted() throws Exception {
        final SequencedRingBuffer<Order> noBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        final ManyToOneRingBuffer<OrderContext> noCompletion =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final KalshiOutboundWriter noWriter = makeWriter(MARKET_TICKER + ":no", noBuffer, noCompletion);

        mockPostSuccess(orderResponse("order-no-bid"));

        publishTo(noBuffer, Side.Bid, price("0.60"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        noWriter.doWork();

        final String json = captureLastPostBody();
        assertTrue(json.contains("\"side\":\"ask\""), json);
        // 1.0 - 0.60 = 0.40
        assertTrue(json.contains("\"price\":\"0.4000\""), json);
        assertTrue(json.contains("\"ticker\":\"" + MARKET_TICKER + "\""), json);
    }

    @Test
    void submitOrder_NoListing_AskFlipsToBid_PriceInverted() throws Exception {
        final SequencedRingBuffer<Order> noBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        final ManyToOneRingBuffer<OrderContext> noCompletion =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final KalshiOutboundWriter noWriter = makeWriter(MARKET_TICKER + ":no", noBuffer, noCompletion);

        mockPostSuccess(orderResponse("order-no-ask"));

        publishTo(noBuffer, Side.Ask, price("0.40"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        noWriter.doWork();

        final String json = captureLastPostBody();
        assertTrue(json.contains("\"side\":\"bid\""), json);
        // 1.0 - 0.40 = 0.60
        assertTrue(json.contains("\"price\":\"0.6000\""), json);
    }

    @Test
    void submitOrder_MarketOrder_UsesFillOrKill() throws Exception {
        mockPostSuccess(orderResponse("order-fok"));

        publishOrder(Side.Bid, price("0.50"), qty("5.0"), OrderType.MARKET, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertTrue(captureLastPostBody().contains("\"time_in_force\":\"fill_or_kill\""));
    }

    @Test
    void submitOrder_LimitIoc_UsesImmediateOrCancel() throws Exception {
        mockPostSuccess(orderResponse("order-ioc"));

        publishOrder(Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();

        assertTrue(captureLastPostBody().contains("\"time_in_force\":\"immediate_or_cancel\""));
    }

    @Test
    void submitOrder_LimitGtc_UsesGoodTillCanceled() throws Exception {
        mockPostSuccess(orderResponse("order-gtc"));

        publishOrder(Side.Ask, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertTrue(captureLastPostBody().contains("\"time_in_force\":\"good_till_canceled\""));
    }

    @Test
    void submitOrder_PriceFormatting_FourDecimalPlaces() throws Exception {
        mockPostSuccess(orderResponse("order-price-fmt"));

        publishOrder(Side.Bid, price("0.05"), qty("1.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertTrue(captureLastPostBody().contains("\"price\":\"0.0500\""));
    }

    @Test
    void submitOrder_SizeFormatting_TwoDecimalPlaces() throws Exception {
        mockPostSuccess(orderResponse("order-size-fmt"));

        publishOrder(Side.Bid, price("0.50"), qty("0.05"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertTrue(captureLastPostBody().contains("\"count\":\"0.05\""));
    }

    // ========== Cancel order ==========

    @Test
    void cancelOrder_Success_CallsDeleteWithExchangeOrderIdInPath() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-abc"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();

        final ArgumentCaptor<GnomeString> pathCaptor = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient)
                .delete(
                        eq(HTTPProtocol.HTTPS),
                        eq(API_HOST),
                        pathCaptor.capture(),
                        eq("KALSHI-ACCESS-KEY"),
                        anyString(),
                        eq("KALSHI-ACCESS-TIMESTAMP"),
                        anyString(),
                        eq("KALSHI-ACCESS-SIGNATURE"),
                        anyString());
        assertTrue(pathCaptor.getValue().toString().contains("exchange-order-abc"));
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void cancelOrder_Failure_EnqueuesCancelReject() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-xyz"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockDeleteFailure();
        publishCancel(clientOidCounter);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(writerReportQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.CANCEL_REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.CANCELED, rejects.get(0).orderStatus);
    }

    @Test
    void cancelOrder_UnknownOrderId_IsNoOp() throws Exception {
        publishCancel(999L);
        writer.doWork();

        verifyNoInteractions(httpClient);
    }

    // ========== Amend order (native) ==========

    @Test
    void amendOrder_Success_SendsAmendNoticeAndOrderStaysActive() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-for-amend"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(amendResponse("exchange-order-for-amend", "0.00", "5.00"));
        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        assertEquals(0, drainQueue(newOrderQueue).size());
        final List<OrderContext> notices = drainQueue(writerReportQueue);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).amendAccepted);

        // Order still active — cancel routes to exchange
        mockDeleteSuccess();
        publishCancel(clientOidCounter);
        writer.doWork();
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void amendOrder_SendsPriceAndCountAsStrings() throws Exception {
        // Kalshi refuses numbers here: "cannot unmarshal number into Go struct field .price of type string".
        mockPostSuccess(orderResponse("amend-body"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final String json = captureLastPostBody();
        assertTrue(json.contains("\"price\":\"0.6000\""), json);
        assertTrue(json.contains("\"count\":\"5.00\""), json);
    }

    @Test
    void amendOrder_SendsJsonContentType() throws Exception {
        // Kalshi refuses a JSON body without it: 400 invalid_content_type.
        mockPostSuccess(orderResponse("amend-content-type"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        verify(httpClient, times(2))
                .post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("Content-Type"),
                        eq("application/json"));
    }

    @Test
    void amendOrder_PostsToAmendPath_ContainingExchangeOrderId() throws Exception {
        mockPostSuccess(orderResponse("amend-path-order"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(orderResponse("not-used"));
        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final ArgumentCaptor<GnomeString> pathCaptor = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient, times(2))
                .post(
                        any(),
                        anyString(),
                        pathCaptor.capture(),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        final String amendPath = pathCaptor.getAllValues().get(1).toString();
        assertTrue(amendPath.contains("amend-path-order"), amendPath);
        assertTrue(amendPath.endsWith("/amend"), amendPath);
    }

    @Test
    void amendOrder_Failure_EnqueuesCancelReject() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-amend-fail"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        final HTTPResponse amendFailure = mock(HTTPResponse.class);
        when(amendFailure.isSuccess()).thenReturn(false);
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(amendFailure);

        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(writerReportQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.CANCEL_REJECT, rejects.get(0).execType);
    }

    @Test
    void amendOrder_NoticeCarriesVenueCountsAndOrderIdentity() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-partial"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final OrderContext submitted = drainQueue(newOrderQueue).get(0);

        mockPostSuccess(amendResponse("exchange-order-partial", "3.00", "2.00"));
        publishModify(submitted.clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final List<OrderContext> notices = drainQueue(writerReportQueue);
        assertEquals(1, notices.size());
        final OrderContext notice = notices.get(0);
        assertTrue(notice.amendAccepted);
        assertEquals(qty("5.0"), notice.originalQty);
        assertEquals(qty("3.0"), notice.cumulativeFilledQty);
        assertEquals(qty("2.0"), notice.leavesQty);
        assertEquals(submitted.clientOidCounter, notice.clientOidCounter);
        assertEquals(correlationId(submitted), correlationId(notice), "the reader finds the order by this id");
    }

    // Kalshi's amend fill_count is only what the amend itself filled by crossing; the order's fills so far are the
    // amended count less what still rests. Responses as the demo exchange returned them on 2026-10-07.

    @Test
    void amendOrder_DownToWhatHasFilled_NoticeCountsTheEarlierFills() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-down"));
        publishOrder(Side.Ask, price("0.90"), qty("3.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(amendResponse("exchange-order-down", "0.00", "0.00"));
        publishModify(clientOidCounter, price("0.85"), qty("2.0"));
        writer.doWork();

        final OrderContext notice = drainQueue(writerReportQueue).get(0);
        assertEquals(qty("2.0"), notice.cumulativeFilledQty, "2 had filled before the amend, not 0");
        assertEquals(0, notice.leavesQty);
    }

    @Test
    void amendOrder_AfterAPartialFill_NoticeCountsTheEarlierFill() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-part"));
        publishOrder(Side.Ask, price("0.90"), qty("3.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(amendResponse("exchange-order-part", "0.00", "2.00"));
        publishModify(clientOidCounter, price("0.85"), qty("3.0"));
        writer.doWork();

        final OrderContext notice = drainQueue(writerReportQueue).get(0);
        assertEquals(qty("1.0"), notice.cumulativeFilledQty);
        assertEquals(qty("2.0"), notice.leavesQty);
    }

    @Test
    void amendOrder_ResponseWithoutCounts_NoticeMarksThemAbsent() throws Exception {
        mockPostSuccess(orderResponse("exchange-order-nofill"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long clientOidCounter = drainQueue(newOrderQueue).get(0).clientOidCounter;

        mockPostSuccess(orderResponse("exchange-order-nofill"));
        publishModify(clientOidCounter, price("0.60"), qty("5.0"));
        writer.doWork();

        final OrderContext notice = drainQueue(writerReportQueue).get(0);
        assertTrue(notice.amendAccepted);
        assertEquals(OrderContext.QTY_ABSENT, notice.cumulativeFilledQty);
        assertEquals(OrderContext.QTY_ABSENT, notice.leavesQty);
    }

    @Test
    void amendOrder_UnknownOrderId_IsNoOp() throws Exception {
        publishModify(999L, price("0.60"), qty("5.0"));
        writer.doWork();

        verifyNoInteractions(httpClient);
    }

    // ========== POST_ONLY flag ==========

    @Test
    void submitOrder_PostOnlyFlagAppearsInJson() throws Exception {
        mockPostSuccess(orderResponse("post-only-order-id"));

        publishOrderWithPostOnly(Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String body = captureLastPostBody();
        assertTrue(body.contains("\"post_only\":true"), "Expected post_only:true in: " + body);
    }

    @Test
    void submitOrder_NoPostOnlyFlag_NotInJson() throws Exception {
        mockPostSuccess(orderResponse("normal-order-id"));

        publishOrder(Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String body = captureLastPostBody();
        assertFalse(body.contains("post_only"), "Expected no post_only in: " + body);
    }

    // ========== Helpers ==========

    private KalshiOutboundWriter makeWriter(
            final String exchangeSecurityId,
            final SequencedRingBuffer<Order> buf,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue)
            throws Exception {
        return makeWriter(exchangeSecurityId, buf, releasedOrderQueue, SESSION_TAG);
    }

    private KalshiOutboundWriter makeWriter(
            final String exchangeSecurityId,
            final SequencedRingBuffer<Order> buf,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final String sessionTag)
            throws Exception {
        final KalshiAuthSigner signer = new KalshiAuthSigner("test-api-key", TEST_PRIVATE_KEY);
        final Listing listing = new Listing(
                (int) LISTING_ID,
                new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                "KALSHI-TEST");
        return new KalshiOutboundWriter(
                logger,
                buf,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                httpClient,
                API_HOST,
                signer,
                () -> FIXED_NANO,
                listing,
                sessionTag);
    }

    private void mockPostSuccess(final ByteBuffer body) throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(body);
    }

    /** Each POST answers with the next status; a non-2xx status is a failed response. */
    private void mockPostStatuses(final int... statuses) throws Exception {
        mockPostFailure();
        final Integer[] rest = new Integer[statuses.length - 1];
        for (int i = 1; i < statuses.length; i++) {
            rest[i - 1] = statuses[i];
        }
        when(httpResponse.getStatusCode()).thenReturn(statuses[0], rest);
    }

    private void mockGetOrders(final String... pages) {
        route(ORDERS_LOOKUP_PATH, 200, pages);
    }

    private void route(final String pathPrefix, final int status, final String... bodies) {
        final Deque<HTTPResponse> queue = new ArrayDeque<>();
        for (final String body : bodies) {
            queue.add(response(status, body));
        }
        getRoutes.put(pathPrefix, queue);
    }

    private static HTTPResponse response(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private void startOnShard(final int exchangeIndex) {
        route(
                MARKET_PATH + MARKET_TICKER,
                200,
                "{\"market\":{\"ticker\":\"" + MARKET_TICKER + "\",\"status\":\"active\",\"exchange_index\":"
                        + exchangeIndex + "}}");
        writer.onStart();
    }

    private String lastDeletePath() throws Exception {
        final ArgumentCaptor<GnomeString> path = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient, atLeastOnce())
                .delete(
                        any(),
                        anyString(),
                        path.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        return path.getValue().toString();
    }

    private static String correlationId(final OrderContext ctx) {
        return new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.UTF_8);
    }

    private void mockPostFailure() throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(false);
    }

    private void mockDeleteSuccess() throws Exception {
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
    }

    private void mockDeleteFailure() throws Exception {
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(false);
    }

    private String captureLastPostBody() throws Exception {
        final ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> lenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(httpClient, atLeastOnce())
                .post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        bodyCaptor.capture(),
                        lenCaptor.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        final List<byte[]> bodies = bodyCaptor.getAllValues();
        final List<Integer> lengths = lenCaptor.getAllValues();
        final int last = bodies.size() - 1;
        return new String(bodies.get(last), 0, lengths.get(last), StandardCharsets.UTF_8);
    }

    private void publishOrder(
            final Side side,
            final long priceVal,
            final long sizeVal,
            final OrderType orderType,
            final TimeInForce tif) {
        publishTo(orderBuffer, side, priceVal, sizeVal, orderType, tif);
    }

    private static void publishTo(
            final SequencedRingBuffer<Order> buf,
            final Side side,
            final long priceVal,
            final long sizeVal,
            final OrderType orderType,
            final TimeInForce tif) {
        publishTo(buf, side, priceVal, sizeVal, orderType, tif, 1L);
    }

    private static void publishTo(
            final SequencedRingBuffer<Order> buf,
            final Side side,
            final long priceVal,
            final long sizeVal,
            final OrderType orderType,
            final TimeInForce tif,
            final long clientOidCounter) {
        final Order order = buf.claim();
        order.encoder.exchangeId(2);
        order.encoder.securityId(3L);
        order.encoder.price(priceVal);
        order.encoder.size(sizeVal);
        order.encoder.side(side);
        order.encoder.orderType(orderType);
        order.encoder.timeInForce(tif);
        order.encoder.flags().clear();
        order.encodeClientOid(clientOidCounter, 1);
        buf.publish();
    }

    private void publishOrderWithPostOnly(
            final Side side,
            final long priceVal,
            final long sizeVal,
            final OrderType orderType,
            final TimeInForce tif) {
        final Order order = orderBuffer.claim();
        order.encoder.exchangeId(2);
        order.encoder.securityId(3L);
        order.encoder.price(priceVal);
        order.encoder.size(sizeVal);
        order.encoder.side(side);
        order.encoder.orderType(orderType);
        order.encoder.timeInForce(tif);
        order.encoder.flags().clear();
        order.encoder.flags().postOnly(true);
        order.encodeClientOid(1L, 1);
        orderBuffer.publish();
    }

    private void publishCancel(final long clientOidCounter) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(2);
        cancel.encoder.securityId(3L);
        cancel.encodeClientOid(clientOidCounter, 1);
        orderBuffer.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    private void publishModify(final long clientOidCounter, final long priceVal, final long sizeVal) {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder.price(priceVal);
        modify.encoder.size(sizeVal);
        modify.encoder.exchangeId(2);
        modify.encoder.securityId(3L);
        modify.encoder.orderType(group.gnometrading.schemas.OrderType.LIMIT);
        modify.encoder.timeInForce(group.gnometrading.schemas.TimeInForce.GOOD_TILL_CANCELED);
        modify.encodeClientOid(clientOidCounter, 1);
        orderBuffer.publishRaw(modify.buffer, ModifyOrderDecoder.TEMPLATE_ID, modify.totalMessageSize());
    }

    private static List<OrderContext> drainQueue(final ManyToOneRingBuffer<OrderContext> queue) {
        final List<OrderContext> result = new ArrayList<>();
        queue.read(
                ctx -> {
                    final OrderContext copy = new OrderContext();
                    copy.copyFrom(ctx);
                    result.add(copy);
                },
                Integer.MAX_VALUE);
        return result;
    }

    private static ByteBuffer amendResponse(final String orderId, final String fillCount, final String remainingCount) {
        final String json = "{\"order_id\":\"" + orderId + "\",\"ts_ms\":1700000000000,\"fill_count\":\"" + fillCount
                + "\",\"remaining_count\":\"" + remainingCount + "\"}";
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuffer orderResponse(final String orderId) {
        final String json = "{\"order\":{\"order_id\":\"" + orderId + "\",\"status\":\"resting\"}}";
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }
}
