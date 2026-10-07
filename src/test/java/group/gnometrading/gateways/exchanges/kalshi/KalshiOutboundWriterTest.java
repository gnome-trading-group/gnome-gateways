package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundWriter;
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
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
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

    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            TEST_PRIVATE_KEY = gen.generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private HTTPClient httpClient;
    private HTTPResponse httpResponse;
    private KalshiOutboundWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        httpClient = mock(HTTPClient.class);
        httpResponse = mock(HTTPResponse.class);

        writer = makeWriter(MARKET_TICKER + ":yes", orderBuffer, releasedOrderQueue);
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

    private void mockGetOrders(final String json) throws Exception {
        final HTTPResponse lookup = mock(HTTPResponse.class);
        when(lookup.isSuccess()).thenReturn(true);
        when(lookup.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
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
                .thenReturn(lookup);
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
