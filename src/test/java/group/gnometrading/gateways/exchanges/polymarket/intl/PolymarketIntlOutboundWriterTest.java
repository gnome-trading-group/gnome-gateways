package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSignerAccess;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundWriter;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class PolymarketIntlOutboundWriterTest {

    // Inputs shared with scripts/pm_v2_vectors.py, whose output the reference bodies below are.
    private static final byte[] PRIVATE_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final String TOKEN_ID =
            "71321045679252212594626385532706912750332728571942532289631379312455583992563";
    private static final long SALT = 1234567890123L;
    private static final long TIMESTAMP_MILLIS = 1759350000000L;
    private static final String API_KEY = "00000000-1111-2222-3333-444444444444";

    private static final String HOST = "clob.polymarket.com";
    // The hash the writer's first order (a 10-share bid at 0.55) signs to; the venue echoes it as orderID.
    private static final String ORDER_HASH = hashOf(Side.Bid, "0.55", "10", 0);
    private static final long TICK = Statics.PRICE_SCALING_FACTOR / 100;
    private static final long MIN_SIZE = 5 * Statics.SIZE_SCALING_FACTOR;
    private static final PolymarketIntlMarketInfo STANDARD_MARKET =
            new PolymarketIntlMarketInfo(TICK, MIN_SIZE, false, 0.05, 1.0, true, false, 0);

    private SequencedRingBuffer<Order> orderBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private HTTPClient httpClient;
    private PolymarketIntlAuthHeaders authHeaders;
    private HTTPResponse httpResponse;
    private PolymarketIntlOutboundWriter writer;

    static Stream<Arguments> referenceBodies() {
        return Stream.of(
                Arguments.of(
                        false,
                        Side.Bid,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"5500000\",\"takerAmount\":\"10000000\",\"side\":\"BUY\",\"expiration\":\"0\",\"signatureType\":0,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0x153806a1f446e4deeaaee1413656868b8d276a54b0515129d1a6a1028864073d33ca07a983f9c6ededd6f3e2631784be9ac910707e307903523364f2432df40e1c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0xa8e3849c1d6063743907190a82b8fa4ce2db03b0d8fce4f5c83420e21a4f20df"),
                Arguments.of(
                        false,
                        Side.Bid,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0x1111111111111111111111111111111111111111\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"5500000\",\"takerAmount\":\"10000000\",\"side\":\"BUY\",\"expiration\":\"0\",\"signatureType\":2,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0x14e3dde0919d350a7a2b9cb5e31bb4af178d76f56f86ab375127265ae58ecbc76adc8f6b91840eca829379c92fb95009ca82749224bbbf80c953fdc83a0e0cd21c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0x009d43373ebe77f22a0b52baa29512be85ed84975873cf996abbd682a6b0110f"),
                Arguments.of(
                        false,
                        Side.Ask,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"10000000\",\"takerAmount\":\"5500000\",\"side\":\"SELL\",\"expiration\":\"0\",\"signatureType\":0,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0x4866643ad89eb9d523285e65753d251f92f76b84bc9c0f1a28465e5d85f7768223e3113cdc802cb367d18c5b8379308f57d91e20c68b64812b13758ae0dd469f1c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0xbf8dc320c5ab1ea65631b42b5c64f4171ae465b19a6d9b83ca116b62aa1fe2cf"),
                Arguments.of(
                        false,
                        Side.Ask,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0x1111111111111111111111111111111111111111\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"10000000\",\"takerAmount\":\"5500000\",\"side\":\"SELL\",\"expiration\":\"0\",\"signatureType\":2,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0x83d2ab8cea798beedcd73fa62003f0c095a4839b100900c08314016c2c3f27390430a0758abbc454fea9bb577a78c52815b32d77f5c2260ea8ae809ca734eacb1c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0x46e7c3050082c80781d98c039d4e9777241121e01f152d0b68d770bc35e27ba2"),
                Arguments.of(
                        true,
                        Side.Bid,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"5500000\",\"takerAmount\":\"10000000\",\"side\":\"BUY\",\"expiration\":\"0\",\"signatureType\":0,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0xf9d65c02a719aa4de9c68d6e635b59d02bac0e5510ad8901be237847cd9f96fa725837cc1819b210b4fe202e2e6122580fede0ab43372e2071812af6a83797a01c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0x18ba148d9459257e099b9917f4b511882dd65935d00c9b62654683abee984da2"),
                Arguments.of(
                        true,
                        Side.Bid,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0x1111111111111111111111111111111111111111\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"5500000\",\"takerAmount\":\"10000000\",\"side\":\"BUY\",\"expiration\":\"0\",\"signatureType\":2,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0xefd9a90d17bf8b5f330645f7b09d6e70b8ddbff2b579fea4db57a7678d0b786915270b086dfe50b78c9f9588dca0453f77e0a174bd85470d3fd2d621d39cc1eb1b\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0xc16f8df0e1e15a49f9abccad43a953f23b933839c895b6e4d824ce576c03977b"),
                Arguments.of(
                        true,
                        Side.Ask,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"10000000\",\"takerAmount\":\"5500000\",\"side\":\"SELL\",\"expiration\":\"0\",\"signatureType\":0,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0x7f51f47b5bd317b6e6dabebb5d9766391b309811be18a08f460ca3d4234f63701c13c32143fbf58786c3f3fc20de9e24390a89db34ba65e911bc79cc46d7776c1b\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0x24ea501d582d11c25b8dfd769354a5b01751eefd7f90d5a9c5cb4b019c8f17b1"),
                Arguments.of(
                        true,
                        Side.Ask,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        "{\"order\":{\"salt\":1234567890123,\"maker\":\"0x1111111111111111111111111111111111111111\",\"signer\":\"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\",\"tokenId\":\"71321045679252212594626385532706912750332728571942532289631379312455583992563\",\"makerAmount\":\"10000000\",\"takerAmount\":\"5500000\",\"side\":\"SELL\",\"expiration\":\"0\",\"signatureType\":2,\"timestamp\":\"1759350000000\",\"metadata\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"builder\":\"0x0000000000000000000000000000000000000000000000000000000000000000\",\"signature\":\"0xdef59fe69c6a5f6aac4a98bf6d4ef604bdbfbd0341dfc6eeb34057bf3345039f512dc7274f5f32cd6f9c23f9b569142ea59ef856bc33264b66bfb9c9ddf292f91c\"},\"owner\":\"00000000-1111-2222-3333-444444444444\",\"orderType\":\"GTC\",\"deferExec\":false,\"postOnly\":false}",
                        "0xa1cdea334ac7b9174013f6d135d38ded8caba0dd2263fc778e0155abc984e91c"));
    }

    @BeforeEach
    void setUp() {
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        httpClient = mock(HTTPClient.class);
        authHeaders = mock(PolymarketIntlAuthHeaders.class);
        httpResponse = mock(HTTPResponse.class);

        when(authHeaders.apiKey()).thenReturn(API_KEY);
        when(authHeaders.signature()).thenReturn("test-sig");
        when(authHeaders.timestamp()).thenReturn("1700000000");
        when(authHeaders.passphrase()).thenReturn("test-passphrase");
        when(authHeaders.address()).thenReturn(EOA);

        writer = buildWriter(EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, STANDARD_MARKET);
    }

    // ========== Submit ==========

    @ParameterizedTest
    @MethodSource("referenceBodies")
    void submitOrder_BodyMatchesReferenceClient(
            boolean negRisk, Side side, int signatureType, String maker, String expectedBody, String orderHash)
            throws Exception {
        writer = buildWriter(
                maker, signatureType, new PolymarketIntlMarketInfo(TICK, MIN_SIZE, negRisk, 0.05, 1.0, true, false, 0));
        stubPost(successResponse(orderHash));

        publishOrder(side, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        assertEquals(expectedBody, capturePostBody());
    }

    @Test
    void submitOrder_PostsToOrderPathWithAuthHeaders() throws Exception {
        stubPost(successResponse(ORDER_HASH));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        final ArgumentCaptor<GnomeString> path = ArgumentCaptor.forClass(GnomeString.class);
        verify(httpClient)
                .post(
                        eq(HTTPProtocol.HTTPS),
                        eq(HOST),
                        path.capture(),
                        any(byte[].class),
                        anyInt(),
                        eq("POLY_API_KEY"),
                        eq(API_KEY),
                        eq("POLY_SIGNATURE"),
                        eq("test-sig"),
                        eq("POLY_TIMESTAMP"),
                        eq("1700000000"),
                        eq("POLY_PASSPHRASE"),
                        eq("test-passphrase"),
                        eq("POLY_ADDRESS"),
                        eq(EOA));
        assertEquals("/order", path.getValue().toString());
        verify(authHeaders).sign(eq("POST"), eq("/order"), any(byte[].class), eq(0), anyInt());
    }

    @Test
    void submitOrder_SuccessRegistersVenueOrderId() throws Exception {
        stubPost(successResponse(ORDER_HASH));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        final List<OrderContext> contexts = drainQueue(newOrderQueue);
        assertEquals(1, contexts.size());
        assertEquals(ORDER_HASH, exchangeOrderId(contexts.get(0)));
    }

    @Test
    void submitOrder_ClientErrorRejects() throws Exception {
        stubPost(null);
        when(httpResponse.isSuccess()).thenReturn(false);
        when(httpResponse.getStatusCode()).thenReturn(400);

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        assertSingleReject();
    }

    @Test
    void submitOrder_VenueRefusalRejects() throws Exception {
        stubPost(ByteBuffer.wrap("{\"success\":false,\"errorMsg\":\"order_version_mismatch\",\"orderID\":\"\"}"
                .getBytes(StandardCharsets.UTF_8)));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        assertSingleReject();
    }

    @Test
    void submitOrder_RegistersTheOrderHashBeforeSending() throws Exception {
        stubPost(successResponse(ORDER_HASH));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        final OrderContext registered = drainQueue(newOrderQueue).get(0);
        assertEquals(
                ORDER_HASH,
                new String(registered.correlationIdBytes, 0, registered.correlationIdLength, StandardCharsets.UTF_8));
        assertEquals(ORDER_HASH, exchangeOrderId(registered));
    }

    @Test
    void submitOrder_NoAnswerThenDuplicateRefusal_FoundOnTheVenue_IsAccepted() throws Exception {
        stubPost(null);
        when(httpResponse.isSuccess()).thenReturn(false);
        when(httpResponse.getStatusCode()).thenReturn(0, 400);
        final HTTPResponse lookup = mock(HTTPResponse.class);
        when(lookup.isSuccess()).thenReturn(true);
        when(lookup.getStatusCode()).thenReturn(200);
        when(httpClient.get(
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
                .thenReturn(lookup);

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        assertEquals(0, drainQueue(writerReportQueue).size());
        final ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(httpClient)
                .get(
                        any(),
                        anyString(),
                        path.capture(),
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
        assertEquals("/data/order/" + ORDER_HASH, path.getValue());
    }

    @Test
    void submitOrder_NoAnswerThenNotFoundOnTheVenue_IsRejected() throws Exception {
        stubPost(null);
        when(httpResponse.isSuccess()).thenReturn(false);
        when(httpResponse.getStatusCode()).thenReturn(503, 400);
        final HTTPResponse lookup = mock(HTTPResponse.class);
        when(lookup.isSuccess()).thenReturn(false);
        when(lookup.getStatusCode()).thenReturn(404);
        when(httpClient.get(
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
                .thenReturn(lookup);

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        assertSingleReject();
    }

    @Test
    void submitOrder_VenueOrderIdDifferentFromOurHash_FailsLoudly() throws Exception {
        stubPost(successResponse("0x" + "ab".repeat(32)));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);

        assertThrows(IllegalStateException.class, () -> writer.doWork());
    }

    @Test
    void submitOrder_MarketOrderRejectsWithoutCallingTheVenue() throws Exception {
        publishOrder(
                Side.Bid,
                group.gnometrading.schemas.OrderDecoder.priceNullValue(),
                qty("10"),
                OrderType.MARKET,
                TimeInForce.IMMEDIATE_OR_CANCELED,
                false);
        writer.doWork();

        assertSingleReject();
        verifyNoInteractions(httpClient);
    }

    @Test
    void submitOrder_TimeInForceMapsToVenueOrderType() throws Exception {
        stubPost(successResponse(ORDER_HASH));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.IMMEDIATE_OR_CANCELED, false);
        writer.doWork();
        assertTrue(capturePostBody().contains("\"orderType\":\"FAK\""));

        clearInvocations(httpClient);
        stubPost(successResponse(hashOf(Side.Bid, "0.55", "10", 1)));
        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.FILL_OR_KILL, false);
        writer.doWork();
        assertTrue(capturePostBody().contains("\"orderType\":\"FOK\""));
    }

    @Test
    void submitOrder_PostOnlyFlagCarriedInBody() throws Exception {
        stubPost(successResponse(ORDER_HASH));

        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, true);
        writer.doWork();

        assertTrue(capturePostBody().endsWith("\"deferExec\":false,\"postOnly\":true}"));
    }

    @Test
    void submitOrder_AmountsAreExactAtTheLargestOrderSize() throws Exception {
        stubPost(successResponse(hashOf(Side.Bid, "0.99", "2000", 0)));

        publishOrder(Side.Bid, price("0.99"), qty("2000"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();

        final String body = capturePostBody();
        assertTrue(body.contains("\"makerAmount\":\"1980000000\""), body);
        assertTrue(body.contains("\"takerAmount\":\"2000000000\""), body);
    }

    // ========== Cancel ==========

    @Test
    void cancelOrder_DeletesWithOrderIdInSignedBody() throws Exception {
        final long clientOid = submitWorkingOrder();
        stubDelete(cancelResponse("[\"" + ORDER_HASH + "\"]", "{}"));

        publishCancel(clientOid);
        writer.doWork();

        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        final ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(authHeaders).sign(eq("DELETE"), eq("/order"), body.capture(), eq(0), length.capture());
        assertEquals(
                "{\"orderID\":\"" + ORDER_HASH + "\"}",
                new String(body.getValue(), 0, length.getValue(), StandardCharsets.UTF_8));
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    @Test
    void cancelOrder_ListedAsNotCanceledIsACancelReject() throws Exception {
        final long clientOid = submitWorkingOrder();
        stubDelete(cancelResponse("[]", "{\"" + ORDER_HASH + "\":\"order can't be canceled\"}"));

        publishCancel(clientOid);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
    }

    @Test
    void cancelOrder_HttpFailureIsACancelReject() throws Exception {
        final long clientOid = submitWorkingOrder();
        stubDelete(null);
        when(httpResponse.isSuccess()).thenReturn(false);

        publishCancel(clientOid);
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
    }

    @Test
    void cancelOrder_UnknownOrderIsANoOp() throws Exception {
        publishCancel(999L);
        writer.doWork();

        verifyNoInteractions(httpClient);
        assertEquals(0, drainQueue(writerReportQueue).size());
    }

    // ========== Modify ==========

    @Test
    void modifyOrder_IsRefusedWithoutTouchingTheVenue() throws Exception {
        // Polymarket orders are signed and immutable; the OMS cancels and resubmits instead of modifying.
        final long clientOid = submitWorkingOrder();
        clearInvocations(httpClient);

        publishModify(clientOid, price("0.60"), qty("5"));
        writer.doWork();

        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).execType);
        verifyNoInteractions(httpClient);
    }

    // ========== helpers ==========

    private PolymarketIntlOutboundWriter buildWriter(
            final String maker, final int signatureType, final PolymarketIntlMarketInfo marketInfo) {
        final PolymarketIntlOrderSigner signer =
                PolymarketIntlOrderSignerAccess.withSalt(PRIVATE_KEY, EOA, maker, signatureType, SALT);
        final Listing listing = new Listing(
                1,
                new Exchange(2, "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", 3),
                "0xcondition:" + TOKEN_ID,
                "TEST-YES");
        return new PolymarketIntlOutboundWriter(
                orderBuffer,
                newOrderQueue,
                writerReportQueue,
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64),
                httpClient,
                HOST,
                signer,
                authHeaders,
                marketInfo,
                () -> TIMESTAMP_MILLIS * 1_000_000L,
                listing);
    }

    private long submitWorkingOrder() throws Exception {
        stubPost(successResponse(ORDER_HASH));
        publishOrder(Side.Bid, price("0.55"), qty("10"), OrderType.LIMIT, TimeInForce.GOOD_TILL_CANCELED, false);
        writer.doWork();
        return drainQueue(newOrderQueue).get(0).clientOidCounter;
    }

    private void stubPost(final ByteBuffer body) throws Exception {
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
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(body);
    }

    private void stubDelete(final ByteBuffer body) throws Exception {
        when(httpClient.delete(
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
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(httpResponse);
        when(httpResponse.isSuccess()).thenReturn(true);
        when(httpResponse.getBody()).thenReturn(body);
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
                        anyString(),
                        anyString(),
                        anyString());
        return new String(body.getValue(), 0, length.getValue(), StandardCharsets.UTF_8);
    }

    private void assertSingleReject() {
        final List<OrderContext> reports = drainQueue(writerReportQueue);
        assertEquals(1, reports.size());
        assertEquals(ExecType.REJECT, reports.get(0).execType);
        assertEquals(OrderStatus.REJECTED, reports.get(0).orderStatus);
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

    private void publishModify(final long clientOidCounter, final long price, final long size) {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder.price(price);
        modify.encoder.size(size);
        modify.encoder.exchangeId(2);
        modify.encoder.securityId(3L);
        modify.encoder.orderType(OrderType.LIMIT);
        modify.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
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

    private static String exchangeOrderId(final OrderContext ctx) {
        return new String(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength, StandardCharsets.UTF_8);
    }

    private static ByteBuffer successResponse(final String orderHash) {
        return ByteBuffer.wrap(
                ("{\"errorMsg\":\"\",\"orderID\":\"" + orderHash + "\",\"status\":\"live\",\"success\":true}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuffer cancelResponse(final String canceled, final String notCanceled) {
        return ByteBuffer.wrap(("{\"canceled\":" + canceled + ",\"not_canceled\":" + notCanceled + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The order hash a fresh writer signs for its {@code nth} order, i.e. the orderID the venue returns. */
    private static String hashOf(final Side side, final String priceVal, final String sizeVal, final int nth) {
        final long size = qty(sizeVal);
        final long notional = size * price(priceVal) / Statics.PRICE_SCALING_FACTOR;
        final boolean buy = side == Side.Bid;
        final PolymarketIntlOrderSigner.SignedOrder order = PolymarketIntlOrderSignerAccess.withSalt(
                        PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, SALT + nth)
                .signOrder(
                        new java.math.BigInteger(TOKEN_ID),
                        buy ? notional : size,
                        buy ? size : notional,
                        buy ? 0 : 1,
                        TIMESTAMP_MILLIS,
                        false);
        final byte[] hex = new byte[66];
        order.writeOrderHashHex(hex, 0);
        return new String(hex, StandardCharsets.US_ASCII);
    }

    private static long price(final String val) {
        return new java.math.BigDecimal(val)
                .multiply(java.math.BigDecimal.valueOf(Statics.PRICE_SCALING_FACTOR))
                .longValueExact();
    }

    private static long qty(final String val) {
        return new java.math.BigDecimal(val)
                .multiply(java.math.BigDecimal.valueOf(Statics.SIZE_SCALING_FACTOR))
                .longValueExact();
    }
}
