package group.gnometrading.gateways.exchanges.kalshi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundSocketReader;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundReader;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KalshiOutboundReaderTest {

    private static final String ORDER_ID = "kalshi-order-uuid-abc123";
    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final long ORIG_QTY = qty("10.0");
    private static final long EVENT_MS = 1_700_000_000_000L;
    private static final long SUBMITTED_AT_MS = 1_791_398_000_000L;
    private static final long SENTINEL_ORDER_ID = 99L;

    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            TEST_PRIVATE_KEY =
                    KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private WebSocketClient client;
    private HTTPClient restClient;
    private final List<String> restPaths = new ArrayList<>();
    private WebSocketResponse response;
    private KalshiOutboundReader reader;
    private List<OrderExecutionReport> captured;
    private Side nextOrderSide = Side.Bid;

    @BeforeEach
    void setUp() throws Exception {
        captured = new CopyOnWriteArrayList<>();
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        execReportBuffer.handleEventsWith((globalSeq, templateId, buffer, length) -> {
            final OrderExecutionReport copy = new OrderExecutionReport();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });
        execReportBuffer.start();

        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        releasedOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        client = mock(WebSocketClient.class);
        restClient = mock(HTTPClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        final Listing listing = new Listing(
                7,
                new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "KALSHI-MARKET:yes",
                "KALSHI-YES");

        reader = new KalshiOutboundReader(
                new NullLogger(),
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                new KalshiAuthSigner("test-api-key", TEST_PRIVATE_KEY),
                restClient,
                "api.test",
                0.07,
                0.0175);
        reader.pauseControl.release();
    }

    @AfterEach
    void tearDown() {
        execReportBuffer.shutdown();
    }

    // ========== Connecting ==========

    @Test
    void connect_SignsTheTradeApiSocketPath() throws Exception {
        // user_orders is a channel on Kalshi's one authenticated socket; there is no /user_orders endpoint.
        final KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        final KalshiOutboundReader signed = readerFor("KALSHI-MARKET:yes", keys.getPrivate());

        final Method beforeConnect = KalshiOutboundReader.class.getDeclaredMethod("beforeConnect");
        beforeConnect.setAccessible(true);
        beforeConnect.invoke(signed);

        final ArgumentCaptor<String> timestamp = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> signature = ArgumentCaptor.forClass(String.class);
        verify(client).setHeader(eq("KALSHI-ACCESS-TIMESTAMP"), timestamp.capture());
        verify(client).setHeader(eq("KALSHI-ACCESS-SIGNATURE"), signature.capture());
        final Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(keys.getPublic());
        verifier.update((timestamp.getValue() + "GET/trade-api/ws/v2").getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature.getValue())));
    }

    @Test
    void connect_SubscribesToUserOrdersForTheListingsMarket() throws Exception {
        final List<String> sent = new ArrayList<>();
        doAnswer(inv -> {
                    final ByteBuffer payload = inv.getArgument(1);
                    sent.add(StandardCharsets.UTF_8.decode(payload.duplicate()).toString());
                    return null;
                })
                .when(client)
                .writeMessage(eq(Opcode.TEXT), any(ByteBuffer.class));
        final KalshiOutboundReader noListing = readerFor("KALSHI-MARKET:no", TEST_PRIVATE_KEY);

        final Method subscribe = KalshiOutboundReader.class.getDeclaredMethod("subscribe");
        subscribe.setAccessible(true);
        subscribe.invoke(noListing);

        assertEquals(
                List.of("{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\"],"
                        + "\"market_tickers\":[\"KALSHI-MARKET\"]}}"),
                sent);
    }

    private KalshiOutboundReader readerFor(final String exchangeSecurityId, final PrivateKey key) throws Exception {
        return new KalshiOutboundReader(
                new NullLogger(),
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                () -> FIXED_NANO,
                new Listing(
                        8,
                        new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                        new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                        exchangeSecurityId,
                        exchangeSecurityId),
                client,
                new JsonDecoder(),
                new KalshiAuthSigner("test-api-key", key),
                restClient,
                "api.test",
                0.07,
                0.0175);
    }

    // ========== Resting / NEW ==========

    @Test
    void resting_WithNoPriorFills_EmitsNewExecReport() throws Exception {
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertNoFurtherReports(1);
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(OrderStatus.NEW, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(ORIG_QTY, report.decoder.leavesQty());
    }

    @Test
    void resting_AfterPartialFill_IsNoOp() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);

        // First: partial fill
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        // Second: another resting event with same fill count — should be a no-op
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS + 1));
        assertNoFurtherReports(1); // still just the one partial fill
    }

    // ========== Partial fill ==========

    @Test
    void partialFill_EmitsPartialFillReport() throws Exception {
        enqueueLiveOrder(ORDER_ID, qty("20.0"), 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertNoFurtherReports(1);
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.PARTIAL_FILL, report.decoder.execType());
        assertEquals(OrderStatus.PARTIALLY_FILLED, report.decoder.orderStatus());
        assertEquals(qty("5.0"), report.decoder.filledQty());
        assertEquals(qty("5.0"), report.decoder.cumulativeQty());
        assertEquals(qty("15.0"), report.decoder.leavesQty());
    }

    @Test
    void partialFill_FillPriceComputedFromCostDelta() throws Exception {
        // fillCount=5, takerCost=$2.80 → fillPrice = 2_800_000_000 * 1_000_000 / 5_000_000 = 560_000_000 (56¢)
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(price("0.56"), captured.get(0).decoder.fillPrice());
    }

    @Test
    void multipleFills_CostDeltaComputedCorrectly() throws Exception {
        // Fill 1: 5 contracts at cost $2.80 total → 56¢ each
        // Fill 2: 3 more contracts at additional cost $1.50 → 50¢ each
        enqueueLiveOrder(ORDER_ID, qty("20.0"), 0);

        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        process(userOrderEvent(ORDER_ID, "resting", "8.00", "12.00", "4.3000", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        assertNoFurtherReports(2);
        final OrderExecutionReport second = captured.get(1);
        assertEquals(ExecType.PARTIAL_FILL, second.decoder.execType());
        assertEquals(qty("3.0"), second.decoder.filledQty());
        assertEquals(qty("8.0"), second.decoder.cumulativeQty());
        assertEquals(qty("12.0"), second.decoder.leavesQty());
        // costDelta = 4_300_000_000 - 2_800_000_000 = 1_500_000_000; fillDelta = 3_000_000
        // fillPrice = 1_500_000_000 * 1_000_000 / 3_000_000 = 500_000_000 (50¢)
        assertEquals(price("0.50"), second.decoder.fillPrice());
    }

    @Test
    void largeFill_CostBeyondLongProductRange_ReportsExactFillPrice() throws Exception {
        // $12,000 at 1e9 times the 1e6 size scale overflows a long before the divide.
        enqueueLiveOrder(ORDER_ID, qty("20000.0"), 0);
        process(userOrderEvent(ORDER_ID, "executed", "20000.00", "0.00", "12000.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertNoFurtherReports(1);
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(qty("20000.0"), report.decoder.filledQty());
        assertEquals(600_000_000L, report.decoder.fillPrice());
    }

    // ========== Full fill ==========

    @Test
    void executed_Status_EmitsFillReport() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertNoFurtherReports(1);
        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(OrderStatus.FILLED, report.decoder.orderStatus());
        assertEquals(qty("10.0"), report.decoder.filledQty());
        assertEquals(qty("10.0"), report.decoder.cumulativeQty());
        assertEquals(0, report.decoder.leavesQty());
    }

    @Test
    void fillCountIncrease_WithZeroRemaining_EmitsFillReport() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        // remaining_count_fp=0 means fully filled even if status is still "resting"
        process(userOrderEvent(ORDER_ID, "resting", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());
        assertEquals(OrderStatus.FILLED, captured.get(0).decoder.orderStatus());
    }

    @Test
    void fullFill_ReleasesContext_SubsequentMessageIgnored() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());

        // Context released — subsequent message is silently ignored
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS + 1));
        assertNoFurtherReports(1);
    }

    @Test
    void fullFill_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 42L);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(42L, completions.get(0));
    }

    // ========== Cancel ==========

    @Test
    void canceled_Status_EmitsCancelReport() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "canceled", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(OrderStatus.CANCELED, report.decoder.orderStatus());
        assertEquals(0, report.decoder.leavesQty());
    }

    @Test
    void canceled_AfterPartialFill_CumulativeQtyPreserved() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        process(userOrderEvent(ORDER_ID, "canceled", "3.00", "0.00", "1.8000", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        final OrderExecutionReport cancel = captured.get(1);
        assertEquals(ExecType.CANCEL, cancel.decoder.execType());
        assertEquals(qty("3.0"), cancel.decoder.cumulativeQty());
        assertEquals(0, cancel.decoder.leavesQty());
    }

    @Test
    void canceled_EnqueuesCompletion() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 55L);
        process(userOrderEvent(ORDER_ID, "canceled", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final List<Long> completions = drainCompletionQueue();
        assertEquals(1, completions.size());
        assertEquals(55L, completions.get(0));
    }

    // ========== Fee computation ==========

    // ========== Fees ==========

    @Test
    void takerFill_NoVenueFees_FallsBackToTakerRate() throws Exception {
        // 5 contracts @ $0.56, all taker cost, takerFeeRate=0.07
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        final long expectedFee = (long) (5.0 * 0.07 * 0.56 * 0.44 * Statics.PRICE_SCALING_FACTOR);
        assertEquals(expectedFee, captured.get(0).decoder.fee());
        assertEquals(Liquidity.TAKER, captured.get(0).decoder.liquidity());
    }

    @Test
    void makerFill_NoVenueFees_FallsBackToMakerRate() throws Exception {
        // Same fill, but booked as maker cost: makerFeeRate=0.0175, not the taker 0.07
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "0.0000", "2.8000", EVENT_MS));
        waitForReports(1);

        final long expectedFee = (long) (5.0 * 0.0175 * 0.56 * 0.44 * Statics.PRICE_SCALING_FACTOR);
        assertEquals(expectedFee, captured.get(0).decoder.fee());
        assertEquals(Liquidity.MAKER, captured.get(0).decoder.liquidity());
    }

    @Test
    void makerFill_VenueReportsFees_UsesVenueFeeNotRateModel() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEventWithFees(
                ORDER_ID, "resting", "5.00", "5.00", "0.0000", "2.8000", "0.000000", "0.012300", EVENT_MS));
        waitForReports(1);

        assertEquals(price("0.0123"), captured.get(0).decoder.fee());
        assertEquals(Liquidity.MAKER, captured.get(0).decoder.liquidity());
    }

    @Test
    void takerFill_VenueReportsFees_UsesVenueFee() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEventWithFees(
                ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", "0.090000", "0.000000", EVENT_MS));
        waitForReports(1);

        assertEquals(price("0.09"), captured.get(0).decoder.fee());
        assertEquals(Liquidity.TAKER, captured.get(0).decoder.liquidity());
    }

    @Test
    void venueFees_AreCumulative_ReportedAsPerFillDeltas() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEventWithFees(
                ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", "0.050000", "0.000000", EVENT_MS));
        waitForReports(1);
        process(userOrderEventWithFees(
                ORDER_ID, "resting", "8.00", "2.00", "4.5000", "0.0000", "0.090000", "0.000000", EVENT_MS + 1));
        waitForReports(2);

        assertEquals(price("0.05"), captured.get(0).decoder.fee());
        assertEquals(price("0.04"), captured.get(1).decoder.fee());
    }

    @Test
    void takerThenMakerFills_EachClassifiedOnItsOwnCostDelta() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);
        process(userOrderEvent(ORDER_ID, "resting", "8.00", "2.00", "2.8000", "1.6800", EVENT_MS + 1));
        waitForReports(2);

        assertEquals(Liquidity.TAKER, captured.get(0).decoder.liquidity());
        assertEquals(Liquidity.MAKER, captured.get(1).decoder.liquidity());
        final long expectedMakerFee = (long) (3.0 * 0.0175 * 0.56 * 0.44 * Statics.PRICE_SCALING_FACTOR);
        assertEquals(expectedMakerFee, captured.get(1).decoder.fee());
    }

    @Test
    void singleUpdateMixingTakerAndMakerCost_ReportsUnknownLiquidity() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "8.00", "2.00", "2.8000", "1.6800", EVENT_MS));
        waitForReports(1);

        assertEquals(Liquidity.NULL_VAL, captured.get(0).decoder.liquidity());
    }

    @Test
    void cancelAfterMakerFill_DoesNotInheritLiquidityFromReusedFlyweight() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "0.0000", "2.8000", EVENT_MS));
        waitForReports(1);
        process(userOrderEvent(ORDER_ID, "canceled", "5.00", "0.00", "0.0000", "2.8000", EVENT_MS + 1));
        waitForReports(2);

        assertEquals(Liquidity.MAKER, captured.get(0).decoder.liquidity());
        assertEquals(ExecType.CANCEL, captured.get(1).decoder.execType());
        assertEquals(Liquidity.NULL_VAL, captured.get(1).decoder.liquidity());
    }

    // ========== Cancel carrying fills ==========

    @Test
    void cancelUpdateCarryingFills_ReportsTheFillThenTheCancel() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "canceled", "4.00", "0.00", "2.2400", "0.0000", EVENT_MS));
        waitForReports(2);

        final OrderExecutionReport fill = captured.get(0);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(qty("4.0"), fill.decoder.filledQty());
        assertEquals(qty("4.0"), fill.decoder.cumulativeQty());
        // Still working until the cancel: the OMS removes this from position leaves on the CANCEL.
        assertEquals(qty("6.0"), fill.decoder.leavesQty());
        assertEquals(price("0.56"), fill.decoder.fillPrice());

        final OrderExecutionReport cancel = captured.get(1);
        assertEquals(ExecType.CANCEL, cancel.decoder.execType());
        assertEquals(qty("4.0"), cancel.decoder.cumulativeQty());
        assertEquals(0, cancel.decoder.leavesQty());
        assertNoFurtherReports(2);
    }

    @Test
    void iocPartlyFilled_ReportsFillAndCancelWithoutAcknowledgingIt() throws Exception {
        // The IOC never rested, so a NEW would describe an order that was never working.
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "canceled", "4.00", "0.00", "2.2400", "0.0000", EVENT_MS));
        waitForReports(2);

        assertEquals(ExecType.PARTIAL_FILL, captured.get(0).decoder.execType());
        assertEquals(ExecType.CANCEL, captured.get(1).decoder.execType());
        assertNoFurtherReports(2);
    }

    @Test
    void cancelUpdateWhoseFillsCompleteTheOrder_ReportsOnlyTheFill() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "canceled", "10.00", "0.00", "5.6000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());
        assertEquals(0, captured.get(0).decoder.leavesQty());
        assertNoFurtherReports(1);
    }

    @Test
    void cancelUpdateAfterAnEarlierPartialFill_ReportsOnlyTheNewFills() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.5000", "0.0000", EVENT_MS));
        process(userOrderEvent(ORDER_ID, "canceled", "5.00", "0.00", "2.5400", "0.0000", EVENT_MS + 1));
        waitForReports(3);

        final OrderExecutionReport fill = captured.get(1);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(qty("2.0"), fill.decoder.filledQty());
        assertEquals(qty("5.0"), fill.decoder.cumulativeQty());
        assertEquals(qty("5.0"), fill.decoder.leavesQty());
        assertEquals(ExecType.CANCEL, captured.get(2).decoder.execType());
        assertEquals(qty("5.0"), captured.get(2).decoder.cumulativeQty());
        assertNoFurtherReports(3);
    }

    // ========== Acknowledgement ==========

    @Test
    void submittedOrder_RepeatedRestingUpdates_AcknowledgedOnce() throws Exception {
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS + 1));
        waitForReports(1);

        assertNoFurtherReports(1);
        assertEquals(ExecType.NEW, captured.get(0).decoder.execType());
    }

    @Test
    void submittedOrder_PartialFillOnArrival_AcknowledgedBeforeTheFill() throws Exception {
        // Without the NEW the OMS slot would stay PENDING_NEW for as long as the order works.
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "resting", "4.00", "6.00", "2.0000", "0.0000", EVENT_MS));
        waitForReports(2);

        assertNoFurtherReports(2);
        assertEquals(ExecType.NEW, captured.get(0).decoder.execType());
        assertEquals(0, captured.get(0).decoder.cumulativeQty());
        assertEquals(ORIG_QTY, captured.get(0).decoder.leavesQty());
        assertEquals(ExecType.PARTIAL_FILL, captured.get(1).decoder.execType());
        assertEquals(qty("6.0"), captured.get(1).decoder.leavesQty());
    }

    @Test
    void submittedOrder_FullFillOnArrival_ReportsOnlyTheFill() throws Exception {
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertNoFurtherReports(1);
        assertEquals(ExecType.FILL, captured.get(0).decoder.execType());
    }

    // ========== Amend acknowledgement ==========

    @Test
    void amendNotice_AfterPartialFill_EmitsSingleNewWithVenueRemaining() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.5000", "0.0000", EVENT_MS));
        waitForReports(1);

        enqueueAmendNotice(ORDER_ID, qty("5.0"), qty("3.0"), qty("2.0"));
        drainQueues();
        waitForReports(2);

        assertNoFurtherReports(2);
        assertEquals(ExecType.NEW, captured.get(1).decoder.execType());
        assertEquals(qty("3.0"), captured.get(1).decoder.cumulativeQty());
        assertEquals(qty("2.0"), captured.get(1).decoder.leavesQty());
    }

    @Test
    void amendNotice_ArrivingAfterAPostAmendFill_NetsThatFillOut() throws Exception {
        // Venue snapshot at amend: 3 filled, 7 remaining. A further 2 fill and reach us first.
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.5000", "0.0000", EVENT_MS));
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "5.00", "2.5400", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        enqueueAmendNotice(ORDER_ID, ORIG_QTY, qty("3.0"), qty("7.0"));
        drainQueues();
        waitForReports(3);

        final OrderExecutionReport ack = captured.get(2);
        assertEquals(ExecType.NEW, ack.decoder.execType());
        assertEquals(qty("5.0"), ack.decoder.cumulativeQty());
        assertEquals(qty("5.0"), ack.decoder.leavesQty()); // the venue's 7, less the 2 we already saw
    }

    @Test
    void amendNotice_BeforeAnInFlightPreAmendFill_KeepsTheAcknowledgedRemaining() throws Exception {
        // We have seen 1 fill; the venue had 3 when it took the amend down to 8, leaving 5.
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "1.00", "9.00", "0.5000", "0.0000", EVENT_MS));
        waitForReports(1);

        enqueueAmendNotice(ORDER_ID, qty("8.0"), qty("3.0"), qty("5.0"));
        drainQueues();
        waitForReports(2);
        assertEquals(qty("5.0"), captured.get(1).decoder.leavesQty());

        // The pre-amend fill to 3 lands now, still reporting the pre-amend remaining of 7.
        process(userOrderEvent(ORDER_ID, "resting", "3.00", "7.00", "1.5000", "0.0000", EVENT_MS + 1));
        waitForReports(3);
        assertEquals(ExecType.PARTIAL_FILL, captured.get(2).decoder.execType());
        assertEquals(qty("5.0"), captured.get(2).decoder.leavesQty());

        // A post-amend fill reports post-amend numbers, which are used as given.
        process(userOrderEvent(ORDER_ID, "resting", "4.00", "4.00", "2.0000", "0.0000", EVENT_MS + 2));
        waitForReports(4);
        assertEquals(qty("4.0"), captured.get(3).decoder.leavesQty());
    }

    @Test
    void amendNotice_ThenRestingUpdate_DoesNotEmitASecondNew() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        enqueueAmendNotice(ORDER_ID, qty("6.0"), qty("0.0"), qty("6.0"));
        drainQueues();
        waitForReports(1);

        process(userOrderEvent(ORDER_ID, "resting", "0.00", "6.00", "0.0000", "0.0000", EVENT_MS));

        assertNoFurtherReports(1);
        assertEquals(qty("6.0"), captured.get(0).decoder.leavesQty());
    }

    @Test
    void amendNotice_ForAnOrderThatHasAlreadyFinished_EmitsNothing() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "canceled", "0.00", "0.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        enqueueAmendNotice(ORDER_ID, qty("5.0"), qty("0.0"), qty("5.0"));
        drainQueues();

        assertNoFurtherReports(1);
        assertEquals(ExecType.CANCEL, captured.get(0).decoder.execType());
    }

    @Test
    void amendNotice_WithoutVenueCounts_FallsBackToNewSizeLessKnownFills() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "2.00", "8.00", "1.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        enqueueAmendNotice(ORDER_ID, qty("6.0"), OrderContext.QTY_ABSENT, OrderContext.QTY_ABSENT);
        drainQueues();
        waitForReports(2);

        assertEquals(qty("2.0"), captured.get(1).decoder.cumulativeQty());
        assertEquals(qty("4.0"), captured.get(1).decoder.leavesQty());
    }

    // ========== Edge cases ==========

    @Test
    void unknownOrderId_IsIgnored() throws Exception {
        // No context enqueued — reader silently skips
        process(userOrderEvent("unknown-uuid-xyz", "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        assertNoFurtherReports(0);
    }

    @Test
    void partialFill_DoesNotEnqueueCompletion() throws Exception {
        enqueueLiveOrder(ORDER_ID, qty("20.0"), 0);
        process(userOrderEvent(ORDER_ID, "resting", "5.00", "15.00", "2.8000", "0.0000", EVENT_MS));
        waitForReports(1);

        assertEquals(0, drainCompletionQueue().size());
    }

    @Test
    void rejectQueue_PublishesExecReport() throws Exception {
        enqueueReject(ExecType.REJECT, OrderStatus.REJECTED);
        process("");
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(OrderStatus.REJECTED, report.decoder.orderStatus());
    }

    @Test
    void execReport_TimestampsPopulated() throws Exception {
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(EVENT_MS * 1_000_000L, report.decoder.timestampEvent());
        assertEquals(FIXED_NANO, report.decoder.timestampRecv());
    }

    @Test
    void execReport_HeaderFieldsMatchContext() throws Exception {
        enqueueContextWithOrderId(ORDER_ID, ORIG_QTY, 0, 42L, false);
        process(userOrderEvent(ORDER_ID, "resting", "0.00", "10.00", "0.0000", "0.0000", EVENT_MS));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(2, report.decoder.exchangeId());
        assertEquals(3L, report.decoder.securityId());
        assertEquals(42L, report.getClientOidCounter());
        assertEquals(ORDER_ID, report.decoder.exchangeOrderId());
    }

    // ========== Catching up after a reconnect ==========
    // Orders as Kalshi's REST order list returns them; the reader takes them through the same handling as its
    // user_order updates, before anything queued on the new socket.

    @Test
    void reconnect_FillWhileDisconnected_IsReportedOnce() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        respondRest(restPage(restOrder(ORDER_ID, "resting", "4.00", "6.00", "2.000000")));
        reader.recvTimestamp = (SUBMITTED_AT_MS + 5_000) * 1_000_000L;

        catchUp();
        waitForReports(1);

        final OrderExecutionReport fill = captured.get(0);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(qty("4.0"), fill.decoder.filledQty());
        assertEquals(qty("6.0"), fill.decoder.leavesQty());
        assertEquals(1_791_396_100_250_000_000L, fill.decoder.timestampEvent(), "from the order's last_update_time");
        assertTrue(
                restPaths.get(0).contains("&min_ts=" + (SUBMITTED_AT_MS / 1000 - 60)),
                "orders since just before the oldest one held: " + restPaths);

        // The same update arriving late on the new socket adds nothing.
        process(userOrderEvent(ORDER_ID, "resting", "4.00", "6.00", "2.0000", "0.0000", EVENT_MS));
        assertNoFurtherReports(1);
    }

    @Test
    void reconnect_FilledThenCanceledWhileDisconnected_ReportsBothAndReleasesTheOrder() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        drainCompletionQueue();
        respondRest(restPage(restOrder(ORDER_ID, "canceled", "1.00", "0.00", "0.500000")));

        catchUp();
        waitForReports(2);

        assertEquals(ExecType.PARTIAL_FILL, captured.get(0).decoder.execType());
        assertEquals(qty("1.0"), captured.get(0).decoder.filledQty());
        assertEquals(ExecType.CANCEL, captured.get(1).decoder.execType());
        assertEquals(qty("1.0"), captured.get(1).decoder.cumulativeQty());
        assertEquals(List.of(1L), drainCompletionQueue(), "its slots come back, so a lost cancel event leaks nothing");
    }

    @Test
    void reconnect_OrdersWeDontHoldAreSkipped_AndOursNotListedIsLeftAlone() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        respondRest(restPage(restOrder("someone-elses-order", "executed", "10.00", "0.00", "5.000000")));

        catchUp();
        assertNoFurtherReports(0);

        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.0000", "0.0000", EVENT_MS));
        waitForReports(2);
        assertEquals(ExecType.FILL, captured.get(1).decoder.execType(), "still tracked, after the sentinel");
    }

    @Test
    void reconnect_FollowsEveryPage() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        respondRest(
                "{\"cursor\":\"c2\",\"orders\":[" + restOrder("other", "resting", "0.00", "1.00", "0") + "]}",
                restPage(restOrder(ORDER_ID, "resting", "2.00", "8.00", "1.000000")));

        catchUp();
        waitForReports(1);

        assertEquals(qty("2.0"), captured.get(0).decoder.filledQty());
        assertTrue(restPaths.get(1).contains("&cursor=c2"), restPaths.toString());
    }

    @Test
    void reconnect_HoldingNoOrders_FetchesSinceTheSocketLastHeardAnything() throws Exception {
        // The writer keeps sending while the socket is down; those orders wait in the queue, not among those held.
        respondRest(restPage());
        reader.recvTimestamp = SUBMITTED_AT_MS * 1_000_000L;

        catchUp();

        assertTrue(restPaths.get(0).contains("&min_ts=" + (SUBMITTED_AT_MS / 1000 - 60)), restPaths.toString());
        assertNoFurtherReports(0);
    }

    @Test
    void reconnect_NeverConnectedBefore_FetchesSinceTheReaderWasMade() throws Exception {
        respondRest(restPage());

        catchUp();

        assertTrue(restPaths.get(0).contains("&min_ts=" + (FIXED_NANO / 1_000_000_000L - 60)), restPaths.toString());
    }

    @Test
    void reconnect_AnOrderSentWhileTheSocketWasDown_IsCaughtUp() throws Exception {
        reader.recvTimestamp = SUBMITTED_AT_MS * 1_000_000L;
        enqueueSubmittedOrder(ORDER_ID, ORIG_QTY);
        respondRest(restPage(restOrder(ORDER_ID, "executed", "10.00", "0.00", "5.000000")));

        findMethod("fetchVenueState").invoke(reader);
        final Field pending = OutboundSocketReader.class.getDeclaredField("venueStatePending");
        pending.setAccessible(true);
        pending.setBoolean(reader, true);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");

        waitForReports(1);
        assertEquals(ExecType.FILL, captured.get(captured.size() - 1).decoder.execType(), captured.toString());
        assertEquals(qty("10.0"), captured.get(captured.size() - 1).decoder.cumulativeQty());
    }

    @Test
    void reconnect_OrderListFails_TheConnectFails() throws Exception {
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process("{\"type\":\"subscribed\",\"id\":1,\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        final HTTPResponse failed = mock(HTTPResponse.class);
        when(failed.isSuccess()).thenReturn(false);
        when(failed.getStatusCode()).thenReturn(500);
        when(restClient.get(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(failed);

        final Method fetch = findMethod("fetchVenueState");
        final Exception thrown = assertThrows(Exception.class, () -> fetch.invoke(reader));
        assertInstanceOf(IOException.class, thrown.getCause(), "so the reader is never resumed without catching up");
    }

    /** What a reconnect does: fetch on the supervisor's side, then apply on the reader's next pass. */
    private void catchUp() throws Exception {
        findMethod("fetchVenueState").invoke(reader);
        findMethod("applyVenueState").invoke(reader);
    }

    private static Method findMethod(final String name) throws NoSuchMethodException {
        Class<?> type = KalshiOutboundReader.class;
        while (type != null) {
            try {
                final Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (final NoSuchMethodException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void respondRest(final String... pages) throws Exception {
        final Deque<HTTPResponse> responses = new ArrayDeque<>();
        for (final String page : pages) {
            final HTTPResponse response = mock(HTTPResponse.class);
            when(response.isSuccess()).thenReturn(true);
            when(response.getStatusCode()).thenReturn(200);
            when(response.getBody()).thenReturn(ByteBuffer.wrap(page.getBytes(StandardCharsets.UTF_8)));
            responses.add(response);
        }
        when(restClient.get(
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
                    restPaths.add(call.getArgument(2));
                    return responses.size() > 1 ? responses.poll() : responses.peek();
                });
    }

    private static String restPage(final String... orders) {
        return "{\"cursor\":\"\",\"orders\":[" + String.join(",", orders) + "]}";
    }

    private static String restOrder(
            final String clientOrderId,
            final String status,
            final String fillCount,
            final String remainingCount,
            final String makerFillCost) {
        return "{\"action\":\"sell\",\"book_side\":\"bid\",\"client_order_id\":\"" + clientOrderId + "\","
                + "\"created_time\":\"2026-10-07T18:00:00.000000Z\",\"exchange_index\":0,"
                + "\"fill_count_fp\":\"" + fillCount + "\",\"initial_count_fp\":\"10.00\","
                + "\"last_update_time\":\"2026-10-07T18:01:40.25Z\",\"maker_fees_dollars\":\"0.000000\","
                + "\"maker_fill_cost_dollars\":\"" + makerFillCost + "\",\"order_id\":\"venue-" + clientOrderId
                + "\",\"outcome_side\":\"yes\",\"remaining_count_fp\":\"" + remainingCount + "\","
                + "\"status\":\"" + status + "\",\"taker_fees_dollars\":\"0.000000\","
                + "\"taker_fill_cost_dollars\":\"0.000000\",\"ticker\":\"KALSHI-MARKET\",\"type\":\"limit\","
                + "\"yes_price_dollars\":\"0.5000\"}";
    }

    // ========== Updates that leave out fields or carry no new fill ==========

    @Test
    void fillWithoutRemainingCount_IsAPartialFill() throws Exception {
        // Read as 0, a missing remaining count would close the order and drop every later fill.
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(updateWithoutRemaining(ORDER_ID, "resting", "4.00", "2.0000"));
        waitForReports(1);

        final OrderExecutionReport partial = captured.get(0);
        assertEquals(ExecType.PARTIAL_FILL, partial.decoder.execType());
        assertEquals(qty("4.0"), partial.decoder.filledQty());
        assertEquals(qty("6.0"), partial.decoder.leavesQty(), "what was working less the fill");
        assertTrue(drainCompletionQueue().isEmpty(), "the order is still working");

        process(userOrderEvent(ORDER_ID, "executed", "10.00", "0.00", "5.0000", "0.0000", EVENT_MS));
        waitForReports(2);
        assertEquals(ExecType.FILL, captured.get(1).decoder.execType());
        assertEquals(qty("6.0"), captured.get(1).decoder.filledQty(), "the later fill still matches the order");
    }

    @Test
    void executedWithNoNewFill_EndsTheOrder() throws Exception {
        // e.g. amended down to what had already filled: Kalshi reports it executed, with nothing more filled.
        enqueueLiveOrder(ORDER_ID, ORIG_QTY, 0);
        process(userOrderEvent(ORDER_ID, "resting", "4.00", "6.00", "2.0000", "0.0000", EVENT_MS));
        waitForReports(1);
        drainCompletionQueue();

        process(userOrderEvent(ORDER_ID, "executed", "4.00", "0.00", "2.0000", "0.0000", EVENT_MS + 1));
        waitForReports(2);

        final OrderExecutionReport done = captured.get(1);
        assertEquals(ExecType.CANCEL, done.decoder.execType(), "nothing more will fill");
        assertEquals(qty("4.0"), done.decoder.cumulativeQty());
        assertEquals(0, done.decoder.leavesQty());
        assertEquals(List.of(1L), drainCompletionQueue(), "the order's slots are released");
    }

    private static String updateWithoutRemaining(
            final String orderId, final String status, final String fillCountFp, final String takerFillCost) {
        return "{\"type\":\"user_order\",\"msg\":{\"order_id\":\"venue-" + orderId + "\",\"client_order_id\":\""
                + orderId + "\",\"status\":\"" + status + "\",\"fill_count_fp\":\"" + fillCountFp + "\","
                + "\"taker_fill_cost_dollars\":\"" + takerFillCost + "\",\"maker_fill_cost_dollars\":\"0.0000\","
                + "\"last_updated_ts_ms\":" + EVENT_MS + "}}";
    }

    // ========== Fill price in the listing's terms ==========
    // Kalshi reports a fill's cost as what the side it bought cost: a bid on either listing buys that listing's own
    // side, an ask buys the other one. Fixtures are user_order updates captured from the demo exchange on 2026-10-07.

    @Test
    void fill_YesListingAsk_IsPricedOnTheYesListing() throws Exception {
        // Sold YES into a 0.31 YES bid: Kalshi reports the 0.69 the NO side cost.
        assertFillPrice("KALSHI-MARKET:yes", Side.Ask, "0.690000", "0.015000", price("0.31"));
    }

    @Test
    void fill_YesListingBid_IsPricedOnTheYesListing() throws Exception {
        assertFillPrice("KALSHI-MARKET:yes", Side.Bid, "0.450000", "0.017400", price("0.45"));
    }

    @Test
    void fill_NoListingBid_IsPricedOnTheNoListing() throws Exception {
        // Bought NO (sent as a YES ask) into a 0.33 YES bid: Kalshi reports the 0.67 NO cost, already NO terms.
        assertFillPrice("KALSHI-MARKET:no", Side.Bid, "0.670000", "0.015500", price("0.67"));
    }

    @Test
    void fill_NoListingAsk_IsPricedOnTheNoListing() throws Exception {
        // Sold NO (sent as a YES bid) into a 0.45 YES ask: Kalshi reports the 0.45 YES cost; NO traded at 0.55.
        assertFillPrice("KALSHI-MARKET:no", Side.Ask, "0.450000", "0.017400", price("0.55"));
    }

    @Test
    void fill_CarriesNoRejectReason() throws Exception {
        enqueueSubmittedOrderOnSide(ORDER_ID, qty("1.0"), Side.Bid);
        process(capturedFill(ORDER_ID, "0.450000", "0.017400"));
        waitForReports(1);

        assertEquals(RejectReason.NULL_VAL, captured.get(0).decoder.rejectReason());
    }

    private void assertFillPrice(
            final String exchangeSecurityId,
            final Side side,
            final String takerFillCost,
            final String takerFees,
            final long expectedPrice)
            throws Exception {
        reader = readerFor(exchangeSecurityId, TEST_PRIVATE_KEY);
        reader.pauseControl.release();
        enqueueSubmittedOrderOnSide(ORDER_ID, qty("1.0"), side);

        process(capturedFill(ORDER_ID, takerFillCost, takerFees));
        waitForReports(1);

        final OrderExecutionReport report = captured.get(0);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(qty("1.0"), report.decoder.filledQty());
        assertEquals(expectedPrice, report.decoder.fillPrice());
        assertEquals(price(takerFees.substring(0, 6)), report.decoder.fee(), "Kalshi's own fee, unchanged");
    }

    /** A 1-contract IOC that filled at once as a taker, as Kalshi's user_orders channel reported it. */
    private static String capturedFill(final String orderId, final String takerFillCost, final String takerFees) {
        return "{\"type\":\"user_order\",\"sid\":1,\"msg\":{\"order_id\":\"01a11706-99d8-7ad3-83b2-5cd731cc7296\","
                + "\"ticker\":\"KALSHI-MARKET\",\"exchange_index\":0,\"status\":\"executed\",\"side\":\"yes\","
                + "\"yes_price_dollars\":\"0.0100\",\"fill_count_fp\":\"1.00\",\"remaining_count_fp\":\"0.00\","
                + "\"initial_count_fp\":\"1.00\",\"taker_fill_cost_dollars\":\"" + takerFillCost + "\","
                + "\"maker_fill_cost_dollars\":\"0.000000\",\"taker_fees_dollars\":\"" + takerFees + "\","
                + "\"maker_fees_dollars\":\"0.000000\",\"client_order_id\":\"" + orderId + "\","
                + "\"created_ts_ms\":1791387671380,\"last_updated_ts_ms\":1791387671380,\"subaccount_number\":0},"
                + "\"sending_ts_ms\":1791387671382}";
    }

    private void enqueueSubmittedOrderOnSide(final String orderId, final long originalQty, final Side side) {
        nextOrderSide = side;
        enqueueSubmittedOrder(orderId, originalQty);
    }

    // ========== Helpers ==========

    private void process(final String json) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
    }

    private void waitForReports(final int count) {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (captured.size() < count && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    /** An order already acknowledged to the OMS: the precondition of every fill and cancel test. */
    private void enqueueLiveOrder(final String orderId, final long originalQty, final long cumulativeFilledQty) {
        enqueueContextWithOrderId(orderId, originalQty, cumulativeFilledQty, 1L, true);
    }

    /** An order the venue has not acknowledged yet, as the writer hands it over after a submit. */
    private void enqueueSubmittedOrder(final String orderId, final long originalQty) {
        enqueueContextWithOrderId(orderId, originalQty, 0, 1L, false);
    }

    private void enqueueContextWithOrderId(
            final String orderId, final long originalQty, final long cumulativeFilledQty, final long internalOrderId) {
        enqueueContextWithOrderId(orderId, originalQty, cumulativeFilledQty, internalOrderId, true);
    }

    private void enqueueContextWithOrderId(
            final String orderId,
            final long originalQty,
            final long cumulativeFilledQty,
            final long internalOrderId,
            final boolean acked) {
        final int idx = newOrderQueue.tryClaim();
        final OrderContext ctx = newOrderQueue.indexAt(idx);
        ctx.reset();
        ctx.acked = acked;
        ctx.side = nextOrderSide;
        ctx.submittedAtMillis = SUBMITTED_AT_MS;
        ctx.clientOidCounter = internalOrderId;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.originalQty = originalQty;
        ctx.cumulativeFilledQty = cumulativeFilledQty;
        ctx.leavesQty = originalQty - cumulativeFilledQty;
        final byte[] idBytes = orderId.getBytes(StandardCharsets.UTF_8);
        ctx.correlationIdLength = Math.min(idBytes.length, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        System.arraycopy(idBytes, 0, ctx.correlationIdBytes, 0, ctx.correlationIdLength);
        newOrderQueue.commit(idx);
    }

    /** What the writer sends after Kalshi accepts an amend. */
    private void enqueueAmendNotice(
            final String orderId, final long newSize, final long venueFillCount, final long venueRemaining) {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext ctx = writerReportQueue.indexAt(idx);
        ctx.reset();
        ctx.amendAccepted = true;
        ctx.clientOidCounter = 1L;
        ctx.originalQty = newSize;
        ctx.cumulativeFilledQty = venueFillCount;
        ctx.leavesQty = venueRemaining;
        final byte[] idBytes = orderId.getBytes(StandardCharsets.UTF_8);
        ctx.correlationIdLength = idBytes.length;
        System.arraycopy(idBytes, 0, ctx.correlationIdBytes, 0, idBytes.length);
        writerReportQueue.commit(idx);
    }

    /**
     * Asserts exactly {@code expected} reports were published. Capture runs on another thread, so a
     * plain size check can pass before a stray report arrives. This publishes a sentinel and waits
     * for it instead: delivery is ordered, so the sentinel landing at {@code expected} proves nothing
     * else was emitted ahead of it.
     */
    private void assertNoFurtherReports(final int expected) throws Exception {
        enqueueReject(ExecType.REJECT, OrderStatus.REJECTED);
        drainQueues();
        waitForReports(expected + 1);
        assertEquals(expected + 1, captured.size());
        assertEquals(SENTINEL_ORDER_ID, captured.get(expected).getClientOidCounter());
    }

    /** Runs one reader cycle with nothing on the socket, so only the handoff queues are drained. */
    private void drainQueues() throws Exception {
        final WebSocketResponse nothing = mock(WebSocketResponse.class);
        when(client.read()).thenReturn(nothing);
        reader.doWork();
    }

    private void enqueueReject(final ExecType execType, final OrderStatus orderStatus) {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext ctx = writerReportQueue.indexAt(idx);
        ctx.reset();
        ctx.execType = execType;
        ctx.orderStatus = orderStatus;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.clientOidCounter = SENTINEL_ORDER_ID;
        writerReportQueue.commit(idx);
    }

    private List<Long> drainCompletionQueue() {
        final List<Long> result = new ArrayList<>();
        releasedOrderQueue.read(ctx -> result.add(ctx.clientOidCounter), Integer.MAX_VALUE);
        return result;
    }

    private static String userOrderEvent(
            final String orderId,
            final String status,
            final String fillCountFp,
            final String remainingCountFp,
            final String takerFillCost,
            final String makerFillCost,
            final long timestampMs) {
        return "{\"type\":\"user_order\",\"msg\":{"
                + "\"order_id\":\"venue-" + orderId + "\","
                + "\"client_order_id\":\"" + orderId + "\","
                + "\"status\":\"" + status + "\","
                + "\"fill_count_fp\":\"" + fillCountFp + "\","
                + "\"remaining_count_fp\":\"" + remainingCountFp + "\","
                + "\"taker_fill_cost_dollars\":\"" + takerFillCost + "\","
                + "\"maker_fill_cost_dollars\":\"" + makerFillCost + "\","
                + "\"last_updated_ts_ms\":" + timestampMs
                + "}}";
    }

    private static String userOrderEventWithFees(
            final String orderId,
            final String status,
            final String fillCountFp,
            final String remainingCountFp,
            final String takerFillCost,
            final String makerFillCost,
            final String takerFees,
            final String makerFees,
            final long timestampMs) {
        return "{\"type\":\"user_order\",\"msg\":{"
                + "\"order_id\":\"venue-" + orderId + "\","
                + "\"client_order_id\":\"" + orderId + "\","
                + "\"status\":\"" + status + "\","
                + "\"fill_count_fp\":\"" + fillCountFp + "\","
                + "\"remaining_count_fp\":\"" + remainingCountFp + "\","
                + "\"taker_fill_cost_dollars\":\"" + takerFillCost + "\","
                + "\"maker_fill_cost_dollars\":\"" + makerFillCost + "\","
                + "\"taker_fees_dollars\":\"" + takerFees + "\","
                + "\"maker_fees_dollars\":\"" + makerFees + "\","
                + "\"last_updated_ts_ms\":" + timestampMs
                + "}}";
    }

    private static long price(final String val) {
        return (long) (Double.parseDouble(val) * Statics.PRICE_SCALING_FACTOR);
    }

    private static long qty(final String val) {
        return (long) (Double.parseDouble(val) * Statics.SIZE_SCALING_FACTOR);
    }
}
