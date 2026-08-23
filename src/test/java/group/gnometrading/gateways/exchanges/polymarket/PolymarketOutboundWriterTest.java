package group.gnometrading.gateways.exchanges.polymarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOutboundWriter;
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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PolymarketOutboundWriterTest {

    private static final String CONDITION_ID = "0xcondition";
    // Use a realistic 77-digit Polymarket token ID (uint256) to verify no overflow
    private static final String TOKEN_ID_STR =
            "21742633143463906290569050155826241533067272736897614950488156847949938836455";
    private static final BigInteger TOKEN_ID = new BigInteger(TOKEN_ID_STR);
    private static final String EXCHANGE_SECURITY_ID = CONDITION_ID + ":" + TOKEN_ID_STR;

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> contextQueue;
    private ManyToOneRingBuffer<OrderContext> rejectQueue;
    private HTTPClient httpClient;
    private PolymarketOrderSigner orderSigner;
    private PolymarketAuthHeaders authHeaders;
    private HTTPResponse httpResponse;
    private PolymarketOutboundWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        contextQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        rejectQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        final ManyToOneRingBuffer<OrderContext> completionQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        httpClient = mock(HTTPClient.class);
        orderSigner = mock(PolymarketOrderSigner.class);
        authHeaders = mock(PolymarketAuthHeaders.class);
        httpResponse = mock(HTTPResponse.class);

        when(authHeaders.apiKey()).thenReturn("test-api-key");
        when(authHeaders.signature()).thenReturn("test-sig");
        when(authHeaders.timestamp()).thenReturn("1700000000");
        when(authHeaders.passphrase()).thenReturn("test-passphrase");
        when(authHeaders.address()).thenReturn("0xTestAddress");

        final PolymarketOrderSigner.SignedOrder signedOrder = new PolymarketOrderSigner.SignedOrder(
                1L, "0xmaker", "0xsigner", TOKEN_ID, 500_000L, 1_000_000L, 0L, 0, BigInteger.ONE, BigInteger.TWO, (byte)
                        27);
        when(orderSigner.signOrder(any(BigInteger.class), anyLong(), anyLong(), anyInt(), anyLong()))
                .thenReturn(signedOrder);

        final Listing listing = new Listing(
                1,
                new Exchange(2, "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                EXCHANGE_SECURITY_ID,
                "TEST-YES");

        writer = new PolymarketOutboundWriter(
                orderBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                httpClient,
                "clob.polymarket.com",
                orderSigner,
                authHeaders,
                listing);
    }

    @Test
    void submitOrderBuyCallsHttpPost() throws Exception {
        when(httpClient.post(
                        eq(HTTPProtocol.HTTPS),
                        eq("clob.polymarket.com"),
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
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xorderhash001"));

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verify(httpClient)
                .post(
                        eq(HTTPProtocol.HTTPS),
                        eq("clob.polymarket.com"),
                        any(GnomeString.class),
                        any(byte[].class),
                        anyInt(),
                        eq("POLY_API_KEY"),
                        eq("test-api-key"),
                        eq("POLY_SIGNATURE"),
                        eq("test-sig"),
                        eq("POLY_TIMESTAMP"),
                        eq("1700000000"),
                        eq("POLY_PASSPHRASE"),
                        eq("test-passphrase"),
                        eq("POLY_ADDRESS"),
                        eq("0xTestAddress"));
    }

    @Test
    void submitOrderSuccessEnqueuesContext() throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xorderhash001"));

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
        final OrderContext ctx = contexts.get(0);
        assertEquals(1L, ctx.orderId);
        assertNotNull(ctx.exchangeOrderIdBytes);
        assertTrue(ctx.exchangeOrderIdLength > 0);
        assertEquals(
                "0xorderhash001",
                new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.UTF_8));
    }

    @Test
    void submitOrderHttpFailureEnqueuesReject() throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(false);

        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);

        // No context enqueued on failure
        assertEquals(0, drainQueue(contextQueue).size());
    }

    @Test
    void submitOrderMarketOrderUsesFok() throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xfok"));

        publishOrder(Side.Bid, price("0.50"), qty("5.0"), OrderType.MARKET, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        // Verify order JSON contains "FOK" orderType — inspect via sign call
        final ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> lenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(authHeaders).sign(eq("POST"), eq("/order"), bodyCaptor.capture(), eq(0), lenCaptor.capture());
        final String signedBody = new String(bodyCaptor.getValue(), 0, lenCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(signedBody.contains("\"orderType\":\"FOK\""));
    }

    @Test
    void submitOrderIocUsesFak() throws Exception {
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xfak"));

        publishOrder(Side.Bid, price("0.50"), qty("5.0"), OrderType.LIMIT, TimeInForce.IMMEDIATE_OR_CANCELED);
        writer.doWork();

        final ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> lenCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(authHeaders).sign(eq("POST"), eq("/order"), bodyCaptor.capture(), eq(0), lenCaptor.capture());
        final String signedBody = new String(bodyCaptor.getValue(), 0, lenCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(signedBody.contains("\"orderType\":\"FAK\""));
    }

    @Test
    void cancelOrderActiveOrder_Success_HttpDeleteCalled() throws Exception {
        // Submit order to add it to activeOrders
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xhashforcancel"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Now cancel
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        publishCancel(orderId);
        writer.doWork();

        verify(httpClient)
                .delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        assertEquals(0, drainQueue(rejectQueue).size());
    }

    @Test
    void cancelOrderActiveOrder_Failure_EnqueuesCancelReject() throws Exception {
        // Submit order
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xhashforcancelreject"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Cancel fails
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(false);
        publishCancel(orderId);
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.CANCEL_REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.CANCELED, rejects.get(0).orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, rejects.get(0).rejectReason);
    }

    @Test
    void modifyOrder_CancelSucceeds_NewSubmitSucceeds() throws Exception {
        // Submit original order
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xoriginal"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Modify: cancel succeeds, new submit succeeds
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xmodified"));
        publishModify(orderId, price("0.60"), qty("5.0"));
        writer.doWork();

        verify(httpClient)
                .delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        // New context enqueued for the replacement order
        final List<OrderContext> contexts = drainQueue(contextQueue);
        assertEquals(1, contexts.size());
    }

    @Test
    void modifyOrder_CancelFails_EnqueuesCancelReject() throws Exception {
        // Submit original order
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xoriginal2"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Modify: cancel fails
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(false);
        publishModify(orderId, price("0.60"), qty("5.0"));
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.CANCEL_REJECT, rejects.get(0).execType);
    }

    @Test
    void modifyOrder_CancelSucceeds_SubmitFails_RejectEnqueued() throws Exception {
        // Submit original order
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xoriginal3"));
        publishOrder(Side.Bid, price("0.50"), qty("10.0"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Modify: cancel DELETE succeeds, replacement POST fails
        final HTTPResponse deleteSuccess = mock(HTTPResponse.class);
        when(deleteSuccess.isSuccess()).thenReturn(true);
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(deleteSuccess);

        final HTTPResponse postFailure = mock(HTTPResponse.class);
        when(postFailure.isSuccess()).thenReturn(false);
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(postFailure);

        publishModify(orderId, price("0.60"), qty("5.0"));
        writer.doWork();

        final List<OrderContext> rejects = drainQueue(rejectQueue);
        assertEquals(1, rejects.size());
        assertEquals(ExecType.REJECT, rejects.get(0).execType);
        assertEquals(OrderStatus.REJECTED, rejects.get(0).orderStatus);
        assertEquals(RejectReason.EXCHANGE_REJECTED, rejects.get(0).rejectReason);
        assertEquals(0, drainQueue(contextQueue).size());
    }

    @Test
    void modifyOrder_SellSide_UsesCorrectMakerTakerAndSide() throws Exception {
        final long priceVal = price("0.60");
        final long sizeVal = qty("10.0");
        // For SELL: makerAmount = size (tokens), takerAmount = price * size / SCALING (USDC)
        final long expectedMakerAmount = sizeVal;
        final long expectedTakerAmount = priceVal * sizeVal / Statics.PRICE_SCALING_FACTOR;

        // Submit original sell-side order
        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xsell-original"));
        publishOrder(Side.Ask, priceVal, sizeVal, OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();
        final long orderId = drainQueue(contextQueue).get(0).orderId;

        // Modify: cancel succeeds, replacement submitted
        final HTTPResponse deleteSuccess = mock(HTTPResponse.class);
        when(deleteSuccess.isSuccess()).thenReturn(true);
        when(httpClient.delete(
                        any(),
                        anyString(),
                        any(GnomeString.class),
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
                .thenReturn(deleteSuccess);
        when(httpResponse.getBody()).thenReturn(successResponse("0xsell-modified"));
        publishModify(orderId, priceVal, sizeVal);
        writer.doWork();

        // Verify signOrder called with SELL_SIDE=1 and swapped maker/taker amounts —
        // twice: once for the initial submit, once for the replacement (same price/size/side)
        verify(orderSigner, times(2))
                .signOrder(eq(TOKEN_ID), eq(expectedMakerAmount), eq(expectedTakerAmount), eq(1), eq(0L));
    }

    @Test
    void cancelOrderUnknownIdIsNoOp() throws Exception {
        // Publish a cancel for an orderId that was never submitted
        publishCancel(999L);
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
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
    }

    @Test
    void buyOrderBuildsMakerAmountFromPriceTimesSize() throws Exception {
        final long priceVal = price("0.60");
        final long sizeVal = qty("10.0");
        // For BUY: makerAmount = price * size / PRICE_SCALING_FACTOR (USDC)
        //          takerAmount = size (tokens)
        final long expectedMakerAmount = priceVal * sizeVal / Statics.PRICE_SCALING_FACTOR;
        final long expectedTakerAmount = sizeVal;

        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xhash"));

        publishOrder(Side.Bid, priceVal, sizeVal, OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verify(orderSigner).signOrder(eq(TOKEN_ID), eq(expectedMakerAmount), eq(expectedTakerAmount), eq(0), eq(0L));
    }

    @Test
    void sellOrderBuildsMakerAmountAsSize() throws Exception {
        final long priceVal = price("0.60");
        final long sizeVal = qty("10.0");
        // For SELL: makerAmount = size (tokens), takerAmount = price * size / PRICE_SCALING_FACTOR (USDC)
        final long expectedMakerAmount = sizeVal;
        final long expectedTakerAmount = priceVal * sizeVal / Statics.PRICE_SCALING_FACTOR;

        when(httpClient.post(
                        any(),
                        anyString(),
                        any(GnomeString.class),
                        any(),
                        anyInt(),
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
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(successResponse("0xhash"));

        publishOrder(Side.Ask, priceVal, sizeVal, OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED);
        writer.doWork();

        verify(orderSigner).signOrder(eq(TOKEN_ID), eq(expectedMakerAmount), eq(expectedTakerAmount), eq(1), eq(0L));
    }

    // --- helpers ---

    private void publishOrder(
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
        order.encodeClientOid(1L, 1);
        orderBuffer.publish();
    }

    private void publishCancel(final long orderId) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.orderId(orderId);
        cancel.encoder.exchangeId(2);
        cancel.encoder.securityId(3L);
        cancel.encodeClientOid(1L, 1);
        orderBuffer.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    private void publishModify(final long orderId, final long price, final long size) {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder.orderId(orderId);
        modify.encoder.price(price);
        modify.encoder.size(size);
        modify.encoder.exchangeId(2);
        modify.encoder.securityId(3L);
        modify.encodeClientOid(2L, 1);
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

    private static ByteBuffer successResponse(final String orderHash) {
        final String json = "{\"success\":true,\"orderID\":\"" + orderHash + "\"}";
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }
}
