package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PolymarketUsOutboundWriterTest {

    private static final String HOST = "api.test";
    private static final String SLUG = "tec-mlb-champ-2026-09-27-lad";
    private static final String SESSION = "session-1";
    private static final long NOW_MILLIS = 1_791_470_000_000L;
    private static final PolymarketUsMarketInfo TICK_0_001 =
            new PolymarketUsMarketInfo(Statics.PRICE_SCALING_FACTOR / 1000, Statics.SIZE_SCALING_FACTOR / 100);

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private HTTPClient httpClient;
    private PolymarketUsOutboundWriter writer;
    private long nextCounter = 1;

    @BeforeEach
    void setUp() throws Exception {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        httpClient = mock(HTTPClient.class);
        writer = buildWriter(":long");
    }

    // ========== Submit ==========

    @Test
    void restingOrder_GoesAsGoodTillDateStampedWithItsCounter() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\", \"executions\":[]}");

        publishOrder(Side.Bid, price("0.436"), qty("5"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String stamp = stampText(PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(SESSION), 1));
        assertEquals(
                "{\"marketSlug\":\"" + SLUG + "\",\"type\":\"ORDER_TYPE_LIMIT\",\"price\":{\"value\":\"0.436\","
                        + "\"currency\":\"USD\"},\"quantity\":5,\"tif\":\"TIME_IN_FORCE_GOOD_TILL_DATE\","
                        + "\"goodTillTime\":\"" + stamp + "\",\"intent\":\"ORDER_INTENT_BUY_LONG\","
                        + "\"manualOrderIndicator\":\"MANUAL_ORDER_INDICATOR_AUTOMATIC\","
                        + "\"participateDontInitiate\":false,\"synchronousExecution\":false}",
                capturePostBody());
        assertEquals(stamp, correlationId(drainQueue(newOrderQueue).get(0)));
    }

    @Test
    void accepted_HandsTheVenueIdToTheReader() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\", \"executions\":[]}");

        publishOrder(Side.Bid, price("0.436"), qty("5"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).venueOrderIdNotice);
        assertEquals("CZJSG2CH8Z7Z", exchangeOrderId(reports.get(0)));
    }

    @Test
    void shortListing_BuysNoAtTheComplementInLongTerms() throws Exception {
        writer = buildWriter(":short");
        stubPost(200, "{\"id\":\"A\"}");

        publishOrder(Side.Bid, price("0.55"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String body = capturePostBody();
        assertTrue(body.contains("\"intent\":\"ORDER_INTENT_BUY_SHORT\""), body);
        assertTrue(body.contains("\"price\":{\"value\":\"0.45\""), body);
    }

    @Test
    void shortListingAsk_SellsNoAtTheComplement() throws Exception {
        writer = buildWriter(":short");
        stubPost(200, "{\"id\":\"A\"}");

        publishOrder(Side.Ask, price("0.6"), qty("2.5"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final String body = capturePostBody();
        assertTrue(body.contains("\"intent\":\"ORDER_INTENT_SELL_SHORT\""), body);
        assertTrue(body.contains("\"price\":{\"value\":\"0.4\""), body);
        assertTrue(body.contains("\"quantity\":2.5,"), body);
    }

    @Test
    void ioc_WaitsForItsOutcome_AndCarriesNoStamp() throws Exception {
        stubPost(200, "{\"id\":\"A\",\"executions\":[]}");

        publishOrder(Side.Ask, price("0.435"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();

        final String body = capturePostBody();
        assertTrue(body.contains("\"tif\":\"TIME_IN_FORCE_IMMEDIATE_OR_CANCEL\""), body);
        assertTrue(body.contains("\"synchronousExecution\":true"), body);
        assertTrue(body.contains("\"intent\":\"ORDER_INTENT_SELL_LONG\""), body);
        assertFalse(body.contains("goodTillTime"), body);
        final OrderContext registered = drainQueue(newOrderQueue).get(0);
        assertEquals("ioc-" + PolymarketUsStamps.slot(SESSION) + "-1", correlationId(registered));
        assertEquals(price("0.435"), registered.limitPrice);
    }

    @Test
    void postOnly_ParticipatesWithoutInitiating() throws Exception {
        stubPost(200, "{\"id\":\"A\"}");

        publishOrder(Side.Bid, price("0.4"), qty("1"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, true);
        writer.doWork();

        assertTrue(capturePostBody().contains("\"participateDontInitiate\":true"));
    }

    @Test
    void offTickPrice_IsRefusedWithoutSending() throws Exception {
        publishOrder(Side.Bid, price("0.4365"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyNoInteractions(httpClient);
    }

    @Test
    void marketOrder_IsRefusedWithoutSending() throws Exception {
        publishOrder(Side.Bid, price("0.4"), qty("1"), OrderType.MARKET, TimeInForce.IMMEDIATE_OR_CANCELED, false);
        writer.doWork();

        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyNoInteractions(httpClient);
    }

    @Test
    void fillOrKill_IsRefusedWithoutSending_SinceTheVenueRunsItAsAnIoc() throws Exception {
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.FILL_OR_KILL);
        writer.doWork();

        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyNoInteractions(httpClient);
    }

    @Test
    void clientError_IsRejected() throws Exception {
        stubPost(400, "{\"code\":3,\"message\":\"bad request\"}");

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertSingleReject(RejectReason.EXCHANGE_REJECTED);
    }

    @Test
    void rateLimited_IsRejectedForTheRateLimit_SinceTheEdgeRefusedIt() throws Exception {
        stubPost(429, "");

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertSingleReject(RejectReason.RATE_LIMITED);
        verifyPosts(1);
    }

    @Test
    void lookupRefusedForTheRateLimit_IsAskedAgain() throws Exception {
        stubPost(503, "");
        final String stamp = stampText(PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(SESSION), 1));
        stubGets(response(429, ""), response(200, "{\"orders\":[" + openOrder("FOUND", stamp) + "]}"));

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verifyPosts(1);
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals("FOUND", exchangeOrderId(reports.get(0)));
    }

    @Test
    void unknownOutcome_IsNeverResent_ButLookedUpByStamp() throws Exception {
        stubPost(503, "");
        final String stamp = stampText(PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(SESSION), 1));
        stubGet(
                200,
                "{\"orders\":[" + openOrder("OTHER", "2100-01-01T00:00:00.001Z") + "," + openOrder("FOUND", stamp)
                        + "]}");

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verifyPosts(1);
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).venueOrderIdNotice);
        assertEquals("FOUND", exchangeOrderId(reports.get(0)));
    }

    @Test
    void unknownOutcome_NotYetListed_StaysRegisteredForTheReader() throws Exception {
        stubPost(503, "");
        stubGet(200, "{\"orders\":[]}");

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verifyPosts(1);
        assertTrue(drainQueue(writerReportQueue).isEmpty());
    }

    @Test
    void timedOut_IsNeverResent() throws Exception {
        stubPostThrows();
        stubGet(200, "{\"orders\":[]}");

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verifyPosts(1);
    }

    @Test
    void quantityOffTheLot_IsRefusedWithoutSending() throws Exception {
        writer = buildWriter(":long", new PolymarketUsMarketInfo(Statics.PRICE_SCALING_FACTOR / 1000, qty("1")));

        publishOrder(Side.Bid, price("0.4"), qty("2.5"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyNoInteractions(httpClient);
    }

    @Test
    void iocWithUnknownOutcome_BlocksAnIdenticalOne_ButNotADifferentOne() throws Exception {
        stubPost(503, "");
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();
        verify(httpClient, never())
                .get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();
        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyPosts(1);

        // Any size at the same side and price is a twin: the venue may cut a size, so the reader can't match on it.
        publishOrder(Side.Bid, price("0.4"), qty("2"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();
        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyPosts(1);

        stubPost(200, "{\"id\":\"B\"}");
        publishOrder(Side.Bid, price("0.401"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();
        verifyPosts(2);
    }

    @Test
    void iocWhoseAnswerTimedOut_BlocksAnIdenticalOne() throws Exception {
        stubPostThrows();
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();

        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();

        assertSingleReject(RejectReason.GATEWAY_REJECTED);
        verifyPosts(1);
    }

    // ========== Cancel ==========

    @Test
    void cancel_PostsToTheOrdersCancelPath() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\"}");
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(200, "{}");
        publishCancel(1);
        writer.doWork();

        final ArgumentCaptor<GnomeString> path = ArgumentCaptor.forClass(GnomeString.class);
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(httpClient, times(2))
                .post(
                        any(),
                        anyString(),
                        path.capture(),
                        body.capture(),
                        length.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertEquals("/v1/order/CZJSG2CH8Z7Z/cancel", path.getAllValues().get(1).toString());
        assertEquals(
                "{\"marketSlug\":\"" + SLUG + "\"}",
                new String(body.getAllValues().get(1), 0, length.getAllValues().get(1), StandardCharsets.UTF_8));
        assertTrue(drainQueue(writerReportQueue).isEmpty(), "an accepted cancel waits for the venue's event");
    }

    @Test
    void cancelRefused_IsACancelReject() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\"}");
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(400, "{\"message\":\"order not found\"}");
        publishCancel(1);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.EXCHANGE_REJECTED, reports.get(0).rejectReason);
    }

    @Test
    void cancelRateLimited_IsACancelRejectForTheRateLimit() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\"}");
        publishOrder(Side.Bid, price("0.4"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(429, "");
        publishCancel(1);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.RATE_LIMITED, reports.get(0).rejectReason);
    }

    // ========== Modify ==========

    @Test
    void modify_AmendsInPlace_KeepingTheStampAndPostOnly() throws Exception {
        stubPost(200, "{\"id\":\"CZJSG2CH8Z7Z\"}");
        publishOrder(Side.Bid, price("0.4"), qty("3"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, true);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(200, "{}");
        publishModify(1, price("0.41"), qty("4"), true);
        writer.doWork();

        final ArgumentCaptor<GnomeString> path = ArgumentCaptor.forClass(GnomeString.class);
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(httpClient, times(2))
                .post(
                        any(),
                        anyString(),
                        path.capture(),
                        body.capture(),
                        length.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertEquals("/v1/order/CZJSG2CH8Z7Z/modify", path.getAllValues().get(1).toString());
        final String stamp = stampText(PolymarketUsStamps.stampMillis(PolymarketUsStamps.slot(SESSION), 1));
        assertEquals(
                "{\"marketSlug\":\"" + SLUG + "\",\"price\":{\"value\":\"0.41\",\"currency\":\"USD\"},"
                        + "\"quantity\":4,\"tif\":\"TIME_IN_FORCE_GOOD_TILL_DATE\",\"goodTillTime\":\"" + stamp
                        + "\",\"participateDontInitiate\":true}",
                new String(body.getAllValues().get(1), 0, length.getAllValues().get(1), StandardCharsets.UTF_8));
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).amendAccepted);
        assertEquals(qty("4"), reports.get(0).originalQty);
        assertEquals(OrderContext.QTY_ABSENT, reports.get(0).cumulativeFilledQty, "the reader counts the fills");
    }

    @Test
    void modifyOnShortListing_SendsTheComplement() throws Exception {
        writer = buildWriter(":short");
        stubPost(200, "{\"id\":\"A\"}");
        publishOrder(Side.Bid, price("0.55"), qty("1"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        stubPost(200, "{}");
        publishModify(1, price("0.56"), qty("1"), false);
        writer.doWork();

        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(httpClient, times(2))
                .post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        body.capture(),
                        length.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        final String sent =
                new String(body.getAllValues().get(1), 0, length.getAllValues().get(1), StandardCharsets.UTF_8);
        assertTrue(sent.contains("\"price\":{\"value\":\"0.44\""), sent);
    }

    @Test
    void modifyRefused_IsACancelReject_AndTheOrderKeepsItsTerms() throws Exception {
        stubPost(200, "{\"id\":\"A\"}");
        publishOrder(Side.Bid, price("0.4"), qty("3"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(400, "{\"message\":\"order not found\"}");
        publishModify(1, price("0.41"), qty("4"), false);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.EXCHANGE_REJECTED, reports.get(0).rejectReason);
    }

    @Test
    void modifyRateLimited_IsACancelRejectForTheRateLimit() throws Exception {
        stubPost(200, "{\"id\":\"A\"}");
        publishOrder(Side.Bid, price("0.4"), qty("3"), TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        stubPost(429, "");
        publishModify(1, price("0.41"), qty("4"), false);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        assertEquals(RejectReason.RATE_LIMITED, reports.get(0).rejectReason);
    }

    @Test
    void modifyOffTickOrOfAnIoc_IsRefusedWithoutSending() throws Exception {
        stubPost(200, "{\"id\":\"A\"}");
        publishOrder(Side.Bid, price("0.4"), qty("3"), TimeInForce.GOOD_TILL_CANCELED);
        publishOrder(Side.Bid, price("0.3"), qty("1"), TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();
        drainQueue(writerReportQueue);

        publishModify(1, price("0.4005"), qty("3"), false);
        publishModify(2, price("0.31"), qty("1"), false);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(2, reports.size());
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(0).rejectReason);
        assertEquals(RejectReason.GATEWAY_REJECTED, reports.get(1).rejectReason);
        verifyPosts(2);
    }

    // ========== helpers ==========

    private PolymarketUsOutboundWriter buildWriter(final String suffix) throws Exception {
        return buildWriter(suffix, TICK_0_001);
    }

    private PolymarketUsOutboundWriter buildWriter(final String suffix, final PolymarketUsMarketInfo marketInfo)
            throws Exception {
        final Listing listing = new Listing(
                1,
                new Exchange(2, "POLYMARKET_US", "Polymarket US", "us"),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                SLUG + suffix,
                "TEST");
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate());
        return new PolymarketUsOutboundWriter(
                new NullLogger(),
                orderBuffer,
                newOrderQueue,
                writerReportQueue,
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64),
                httpClient,
                HOST,
                signer,
                marketInfo,
                () -> NOW_MILLIS * 1_000_000L,
                listing,
                SESSION);
    }

    private void stubPost(final int status, final String body) throws Exception {
        final HTTPResponse response = response(status, body);
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
                .thenReturn(response);
    }

    private void stubPostThrows() throws Exception {
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
                .thenThrow(new IOException("timed out"));
    }

    private void stubGet(final int status, final String body) throws Exception {
        final HTTPResponse response = response(status, body);
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
                .thenReturn(response);
    }

    private void stubGets(final HTTPResponse first, final HTTPResponse... rest) throws Exception {
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
                .thenReturn(first, rest);
    }

    private static HTTPResponse response(final int status, final String body) {
        final HTTPResponse response = mock(HTTPResponse.class);
        when(response.isSuccess()).thenReturn(status >= 200 && status < 300);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private void verifyPosts(final int count) throws Exception {
        verify(httpClient, times(count))
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
                        anyString(),
                        anyString());
    }

    private String capturePostBody() throws Exception {
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(httpClient)
                .post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        body.capture(),
                        length.capture(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        return new String(body.getValue(), 0, length.getValue(), StandardCharsets.UTF_8);
    }

    private void assertSingleReject(final RejectReason reason) {
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
        assertEquals(reason, reports.get(0).rejectReason);
    }

    private void publishOrder(final Side side, final long priceVal, final long sizeVal, final TimeInForce tif) {
        publishOrder(side, priceVal, sizeVal, OrderType.LIMIT, tif, false);
    }

    private void publishOrder(
            final Side side,
            final long priceVal,
            final long sizeVal,
            final OrderType orderType,
            final TimeInForce tif,
            final boolean postOnly) {
        final Order order = orderBuffer.claim();
        order.encoder.exchangeId(2);
        order.encoder.securityId(3L);
        order.encoder.price(priceVal);
        order.encoder.size(sizeVal);
        order.encoder.side(side);
        order.encoder.orderType(orderType);
        order.encoder.timeInForce(tif);
        order.encoder.flags().clear();
        order.encoder.flags().postOnly(postOnly);
        order.encodeClientOid(this.nextCounter++, 1);
        orderBuffer.publish();
    }

    private void publishCancel(final long clientOidCounter) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(2);
        cancel.encoder.securityId(3L);
        cancel.encodeClientOid(clientOidCounter, 1);
        orderBuffer.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    private void publishModify(final long clientOidCounter, final long price, final long size, final boolean postOnly) {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder.price(price);
        modify.encoder.size(size);
        modify.encoder.exchangeId(2);
        modify.encoder.securityId(3L);
        modify.encoder.orderType(OrderType.LIMIT);
        modify.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        modify.encoder.flags().clear();
        modify.encoder.flags().postOnly(postOnly);
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

    private static String openOrder(final String id, final String goodTillTime) {
        return "{\"id\":\"" + id + "\",\"marketSlug\":\"" + SLUG + "\",\"quantity\":1,\"cumQuantity\":0,"
                + "\"goodTillTime\":\"" + goodTillTime + "\",\"intent\":\"ORDER_INTENT_BUY_LONG\","
                + "\"state\":\"ORDER_STATE_NEW\"}";
    }

    static String stampText(final long millis) {
        final byte[] text = new byte[PolymarketUsStamps.LENGTH];
        return new String(text, 0, PolymarketUsStamps.write(millis, text, 0), StandardCharsets.US_ASCII);
    }

    private static String correlationId(final OrderContext ctx) {
        return new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
    }

    private static String exchangeOrderId(final OrderContext ctx) {
        return new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.US_ASCII);
    }

    private static long price(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long qty(final String val) {
        return new BigDecimal(val)
                .multiply(BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
