package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlVenueOrderQuery;
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
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Payloads follow the user-channel shapes in Polymarket's docs (order and trade events). */
class PolymarketIntlOutboundReaderTest {

    private static final String ORDER_A = "0x" + "aa".repeat(32);
    private static final String ORDER_B = "0x" + "bb".repeat(32);
    private static final String SOMEONE_ELSE = "0x" + "ee".repeat(32);
    private static final String SENTINEL = "0x" + "99".repeat(32);
    private static final long SENTINEL_ORDER_ID = 99L;

    private static final long FIXED_NANO = 1_700_000_000_000_000_000L;
    private static final long ORIG_QTY = qty("10");
    private static final PolymarketIntlMarketInfo TAKER_ONLY_FEES = new PolymarketIntlMarketInfo(
            Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.05, 1.0, true, false, 0);

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private SequencedPoller poller;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private WebSocketClient client;
    private WebSocketResponse response;
    private PolymarketIntlOutboundReader reader;
    private List<OrderExecutionReport> captured;
    private HTTPClient restClient;
    private final List<String> restPaths = new ArrayList<>();
    // Answers to the catch-up's GETs, by path prefix; the longest matching prefix wins.
    private final Map<String, String> restAnswers = new HashMap<>();

    @BeforeEach
    void setUp() {
        captured = new CopyOnWriteArrayList<>();
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        poller = execReportBuffer.createPoller((globalSeq, templateId, buffer, length) -> {
            final OrderExecutionReport copy = new OrderExecutionReport();
            copy.buffer.putBytes(0, buffer, 0, length);
            copy.wrap(copy.buffer);
            captured.add(copy);
        });

        newOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        writerReportQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);
        releasedOrderQueue = new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 64);

        client = mock(WebSocketClient.class);
        response = mock(WebSocketResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.isClosed()).thenReturn(false);
        when(response.getOpcode()).thenReturn(Opcode.TEXT);

        restClient = mock(HTTPClient.class);
        reader = buildReader(TAKER_ONLY_FEES);
    }

    // ========== Order events ==========

    @Test
    void placementLive_PublishesNew() throws Exception {
        enqueueOrder(ORDER_A, 1L);

        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(OrderStatus.NEW, report.decoder.orderStatus());
        assertEquals(0, report.decoder.cumulativeQty());
        assertEquals(ORIG_QTY, report.decoder.leavesQty());
        assertEquals(1L, report.getClientOidCounter());
        assertEquals(FIXED_NANO, report.decoder.timestampRecv());
    }

    @Test
    void repeatedPlacement_IsNotAcknowledgedTwice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        awaitReport(0);

        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        assertNoFurtherReports();
    }

    @Test
    void cancellation_PublishesCancelWithFillsSoFar_AndReleasesTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("MATCHED", ORDER_A, "0.55", "3", "[]"));

        process(orderEvent("CANCELLATION", "CANCELED", ORDER_A));

        final OrderExecutionReport cancel = awaitReport(2);
        assertEquals(ExecType.CANCEL, cancel.decoder.execType());
        assertEquals(qty("3"), cancel.decoder.cumulativeQty());
        assertEquals(0, cancel.decoder.leavesQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void eventsForOrdersWeDoNotHaveAreIgnored() throws Exception {
        process(orderEvent("PLACEMENT", "LIVE", SOMEONE_ELSE));
        process(trade("MATCHED", SOMEONE_ELSE, "0.55", "3", "[]"));

        assertNoFurtherReports();
    }

    // ========== Trades ==========

    @Test
    void takerFill_UsesTheTradeSizeAndItsLegPrice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.55", "4", makerLeg(SOMEONE_ELSE, "4", "0.55")));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(OrderStatus.PARTIALLY_FILLED, fill.decoder.orderStatus());
        assertEquals(qty("4"), fill.decoder.filledQty());
        assertEquals(price("0.55"), fill.decoder.fillPrice());
        assertEquals(qty("4"), fill.decoder.cumulativeQty());
        assertEquals(qty("6"), fill.decoder.leavesQty());
        assertEquals(Liquidity.TAKER, fill.decoder.liquidity());
        assertEquals(RejectReason.NULL_VAL, fill.decoder.rejectReason());
    }

    @Test
    void takerFillAcrossLevels_IsPricedAndChargedFromItsLegs() throws Exception {
        // Seen live: $1.10 bought 117.352 YES from four resting orders, three of them NO buyers matched by minting.
        // The trade's own price (0.009) is only the best of them.
        reader = buildReader(new PolymarketIntlMarketInfo(
                Statics.PRICE_SCALING_FACTOR / 1000, qty("5"), false, 0.04, 1.0, true, false, 0));
        enqueueOrder(ORDER_A, 1L, 0L, qty("110"), false);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade(
                "MATCHED",
                ORDER_A,
                "0.009",
                "117.352",
                otherOutcomeLeg(SOMEONE_ELSE, "50", "0.991") + "," + otherOutcomeLeg(SOMEONE_ELSE, "5", "0.991") + ","
                        + makerLeg(SOMEONE_ELSE, "18.52", "0.009") + ","
                        + otherOutcomeLeg(SOMEONE_ELSE, "43.832", "0.99")));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(qty("117.352"), fill.decoder.filledQty());
        assertEquals(price("0.009373509"), fill.decoder.fillPrice(), "$1.10 over 117.352 shares");
        assertEquals(
                price("0.04359"), fill.decoder.fee(), "each leg's fee at its own price; the venue charged 0.04358");
    }

    @Test
    void makerFill_UsesOurLegNotTheTakersSizeAndPrice() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade(
                "MATCHED",
                SOMEONE_ELSE,
                "0.57",
                "10",
                makerLeg(SOMEONE_ELSE, "6", "0.57") + "," + makerLeg(ORDER_A, "4", "0.55")));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(qty("4"), fill.decoder.filledQty());
        assertEquals(price("0.55"), fill.decoder.fillPrice());
        assertEquals(Liquidity.MAKER, fill.decoder.liquidity());
        assertEquals(0L, fill.decoder.fee(), "makers pay no fee on a taker-only market");
        assertNoFurtherReports();
    }

    @Test
    void oneTradeFillingTwoOfOurOrders_ReportsEach() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        enqueueOrder(ORDER_B, 2L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(orderEvent("PLACEMENT", "LIVE", ORDER_B));

        process(trade(
                "MATCHED",
                SOMEONE_ELSE,
                "0.55",
                "13",
                makerLeg(ORDER_A, "10", "0.55") + "," + makerLeg(ORDER_B, "3", "0.55")));

        final OrderExecutionReport first = awaitReport(2);
        final OrderExecutionReport second = awaitReport(3);
        assertEquals(1L, first.getClientOidCounter());
        assertEquals(ExecType.FILL, first.decoder.execType());
        assertEquals(2L, second.getClientOidCounter());
        assertEquals(ExecType.PARTIAL_FILL, second.decoder.execType());
        assertEquals(qty("3"), second.decoder.filledQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void redeliveredMatchedTrade_IsReportedOnce() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));
        assertEquals(qty("4"), awaitReport(1).decoder.cumulativeQty());

        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));
        assertNoFurtherReports();

        process(trade("trade-2", "MATCHED", ORDER_A, "0.55", "1", "[]"));
        final OrderExecutionReport next = awaitReport(3);
        assertEquals(qty("1"), next.decoder.filledQty());
        assertEquals(qty("5"), next.decoder.cumulativeQty(), "the redelivered trade must not be counted");
    }

    @Test
    void distinctMatchedTrades_AreEachReported() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));
        process(trade("trade-2", "MATCHED", ORDER_A, "0.56", "3", "[]"));

        assertEquals(qty("4"), awaitReport(1).decoder.cumulativeQty());
        final OrderExecutionReport second = awaitReport(2);
        assertEquals(qty("3"), second.decoder.filledQty());
        assertEquals(qty("7"), second.decoder.cumulativeQty());
        assertNoFurtherReports();
    }

    @Test
    void tradeEvictedFromTheDedupWindow_IsAcceptedAgain() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("trade-first", "MATCHED", ORDER_A, "0.55", "1", "[]"));
        awaitReport(1);

        final int dedupWindow = 4096;
        for (int i = 0; i < dedupWindow; i++) {
            process(trade("other-" + i, "MATCHED", SOMEONE_ELSE, "0.55", "1", "[]"));
        }

        process(trade("trade-first", "MATCHED", ORDER_A, "0.55", "1", "[]"));
        assertEquals(qty("2"), awaitReport(2).decoder.cumulativeQty());
    }

    @Test
    void fullFill_PublishesFillAndReleasesTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.55", "10", "[]"));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.FILL, fill.decoder.execType());
        assertEquals(OrderStatus.FILLED, fill.decoder.orderStatus());
        assertEquals(0, fill.decoder.leavesQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void fillBeforeAnyPlacement_IsAcknowledgedFirst() throws Exception {
        enqueueOrder(ORDER_A, 1L);

        process(trade("MATCHED", ORDER_A, "0.55", "10", "[]"));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        assertEquals(ExecType.FILL, awaitReport(1).decoder.execType());
    }

    @Test
    void settlementStages_AreNotFillsAgain() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("MATCHED", ORDER_A, "0.55", "4", "[]"));
        awaitReport(1);

        process(trade("MINED", ORDER_A, "0.55", "4", "[]"));
        process(trade("CONFIRMED", ORDER_A, "0.55", "4", "[]"));

        assertNoFurtherReports();
    }

    @Test
    void tradeFirstSeenAfterMatching_IsStillAFill_Once() throws Exception {
        // After a reconnect the first word of a trade can be its MINED or CONFIRMED update.
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        process(trade("MINED", ORDER_A, "0.55", "4", "[]"));
        assertEquals(qty("4"), awaitReport(1).decoder.filledQty());

        process(trade("CONFIRMED", ORDER_A, "0.55", "4", "[]"));
        assertNoFurtherReports();
    }

    // ========== IOC and FOK: closed only once the venue's total has filled ==========

    @Test
    void iocOverfill_SplitAcrossTrades_EveryShareIsReportedBeforeTheOrderCloses() throws Exception {
        enqueueOrder(ORDER_A, 1L, 0L, qty("5"), true);
        finalFillNotice(ORDER_A, 1L, qty("5.135136"));
        process(trade("trade-1", "MATCHED", ORDER_A, "0.37", "5", "[]"));
        process(trade("trade-2", "MATCHED", ORDER_A, "0.37", "0.135136", "[]"));

        final OrderExecutionReport first = awaitReport(1);
        assertEquals(ExecType.PARTIAL_FILL, first.decoder.execType(), "the order size isn't the end of it");
        final OrderExecutionReport last = awaitReport(2);
        assertEquals(ExecType.FILL, last.decoder.execType());
        assertEquals(qty("5.135136"), last.decoder.cumulativeQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void iocFilledBeforeTheNoticeArrives_TheNoticeClosesItAsFilled() throws Exception {
        enqueueOrder(ORDER_A, 1L, 0L, qty("5"), true);
        process(trade("trade-1", "MATCHED", ORDER_A, "0.37", "5.135136", "[]"));
        assertEquals(ExecType.PARTIAL_FILL, awaitReport(1).decoder.execType(), "it may not be the last of it");
        assertEquals(List.of(), drainReleased());

        finalFillNotice(ORDER_A, 1L, qty("5.135136"));
        process("PONG");

        // Seen live: the trade beat the submit's answer. A FILL adding nothing ends it as filled in the OMS.
        final OrderExecutionReport closed = awaitReport(2);
        assertEquals(ExecType.FILL, closed.decoder.execType());
        assertEquals(0, closed.decoder.filledQty(), "nothing new to book");
        assertEquals(qty("5.135136"), closed.decoder.cumulativeQty(), "every share stays booked");
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void iocPartlyFilled_TheRestIsCanceled() throws Exception {
        enqueueOrder(ORDER_A, 1L, 0L, qty("10"), true);
        finalFillNotice(ORDER_A, 1L, qty("4"));
        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));

        assertEquals(ExecType.PARTIAL_FILL, awaitReport(1).decoder.execType());
        final OrderExecutionReport closed = awaitReport(2);
        assertEquals(ExecType.CANCEL, closed.decoder.execType());
        assertEquals(qty("4"), closed.decoder.cumulativeQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void iocRemainderCanceledBeforeItsTrades_TheTradesStillCount() throws Exception {
        enqueueOrder(ORDER_A, 1L, 0L, qty("10"), true);
        finalFillNotice(ORDER_A, 1L, qty("4"));
        process(orderEvent("CANCELLATION", "CANCELED", ORDER_A));
        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        assertEquals(qty("4"), awaitReport(1).decoder.filledQty(), "the trade, not an early cancel");
        assertEquals(ExecType.CANCEL, awaitReport(2).decoder.execType());
        assertEquals(qty("4"), awaitReport(2).decoder.cumulativeQty());
    }

    @Test
    void iocWhoseTotalTheVenueDidntGive_EndsAtItsSizeLikeAnyOrder() throws Exception {
        // A delayed order's answer has no amounts: no total to wait for, so the order can't be left open forever.
        enqueueOrder(ORDER_A, 1L, 0L, qty("5"), true);
        finalFillNotice(ORDER_A, 1L, OrderContext.QTY_ABSENT);
        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "5", "[]"));

        assertEquals(ExecType.FILL, awaitReport(1).decoder.execType());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void iocWhoseTotalTheVenueDidntGive_ItsCancellationEndsIt() throws Exception {
        enqueueOrder(ORDER_A, 1L, 0L, qty("10"), true);
        finalFillNotice(ORDER_A, 1L, OrderContext.QTY_ABSENT);
        process(trade("trade-1", "MATCHED", ORDER_A, "0.55", "4", "[]"));
        process(orderEvent("CANCELLATION", "CANCELED", ORDER_A));

        assertEquals(ExecType.CANCEL, awaitReport(2).decoder.execType());
        assertEquals(qty("4"), awaitReport(2).decoder.cumulativeQty());
    }

    private void finalFillNotice(final String orderHash, final long clientOidCounter, final long finalFillQty) {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext notice = writerReportQueue.indexAt(idx);
        notice.reset();
        notice.finalFillNotice = true;
        notice.finalFillQty = finalFillQty;
        notice.clientOidCounter = clientOidCounter;
        final byte[] hashBytes = orderHash.getBytes(StandardCharsets.US_ASCII);
        notice.correlationIdLength = hashBytes.length;
        System.arraycopy(hashBytes, 0, notice.correlationIdBytes, 0, hashBytes.length);
        writerReportQueue.commit(idx);
    }

    // ========== Catching up after a reconnect ==========
    // What the CLOB's REST API says of the market while the socket was down, applied before the new socket.

    private static final long NOW_MS = FIXED_NANO / 1_000_000L;
    private static final long LONG_AGO_MS = NOW_MS - 60_000;

    @Test
    void reconnect_FillWhileDisconnected_IsReportedOnce() throws Exception {
        enqueueOrder(ORDER_A, 1L, LONG_AGO_MS);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        reader.recvTimestamp = (NOW_MS - 10_000) * 1_000_000L;
        restAnswers.put("/data/trades", tradePage(restTrade("trade-9", ORDER_A, "0.55", "4")));
        restAnswers.put("/data/orders", openOrders(ORDER_A));

        catchUp();

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(qty("4"), fill.decoder.filledQty());
        assertEquals(price("0.55"), fill.decoder.fillPrice());
        assertEquals(1_700_000_000L * 1_000_000_000L, fill.decoder.timestampEvent(), "from the trade's match_time");
        assertTrue(
                restPaths.get(0).startsWith("/data/trades?market=condition-1&after=" + (LONG_AGO_MS / 1000 - 60)),
                restPaths.toString());

        // The same trade arriving late on the new socket adds nothing.
        process(trade("trade-9", "MATCHED", ORDER_A, "0.55", "4", "[]"));
        assertNoFurtherReports();
    }

    @Test
    void reconnect_CanceledWhileDisconnected_ReportsTheCancelAndReleasesTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L, LONG_AGO_MS);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        drainReleased();
        restAnswers.put("/data/trades", tradePage());
        restAnswers.put("/data/orders", openOrders());
        restAnswers.put("/data/order/" + ORDER_A, restOrder(ORDER_A, "CANCELED", "0"));

        catchUp();

        assertEquals(ExecType.CANCEL, awaitReport(1).decoder.execType());
        assertEquals(List.of(1L), drainReleased(), "its slots come back");
    }

    @Test
    void reconnect_EndedWithFillsNotListedYet_IsLeftAlone() throws Exception {
        // Ended, but more matched than any trade we've seen: cancelling now would cut off fills still to come.
        enqueueOrder(ORDER_A, 1L, LONG_AGO_MS);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        restAnswers.put("/data/trades", tradePage());
        restAnswers.put("/data/orders", openOrders());
        restAnswers.put("/data/order/" + ORDER_A, restOrder(ORDER_A, "MATCHED", "10"));

        catchUp();

        assertNoFurtherReports();
        process(trade("MATCHED", ORDER_A, "0.55", "10", "[]"));
        assertEquals(ExecType.FILL, awaitReport(2).decoder.execType(), "still tracked");
    }

    @Test
    void reconnect_StillOpen_IsNotLookedUp() throws Exception {
        enqueueOrder(ORDER_A, 1L, LONG_AGO_MS);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        restAnswers.put("/data/trades", tradePage());
        restAnswers.put("/data/orders", openOrders(ORDER_A));

        catchUp();

        assertNoFurtherReports();
        assertEquals(2, restPaths.size(), restPaths.toString());
    }

    @Test
    void reconnect_JustSubmitted_IsNotTakenForEnded() throws Exception {
        // The order list can lag an order placed a moment ago.
        enqueueOrder(ORDER_A, 1L, NOW_MS - 1_000);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        restAnswers.put("/data/trades", tradePage());
        restAnswers.put("/data/orders", openOrders());
        restAnswers.put("/data/order/" + ORDER_A, restOrder(ORDER_A, "CANCELED", "0"));

        catchUp();

        assertNoFurtherReports();
    }

    @Test
    void reconnect_HoldingNothing_FetchesSinceTheSocketLastHeardAnything() throws Exception {
        reader.recvTimestamp = (NOW_MS - 10_000) * 1_000_000L;
        restAnswers.put("/data/trades", tradePage());
        restAnswers.put("/data/orders", openOrders());

        catchUp();

        assertTrue(restPaths.get(0).contains("&after=" + ((NOW_MS - 10_000) / 1000 - 60)), restPaths.toString());
    }

    @Test
    void reconnect_TradeListFails_TheConnectFails() throws Exception {
        restAnswers.put("/data/orders", openOrders());
        routeRest();

        final InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> findMethod("fetchVenueState")
                        .invoke(reader));

        assertInstanceOf(IOException.class, thrown.getCause(), "so the reader is never resumed without catching up");
    }

    /** What a reconnect does: fetch on the supervisor's side, then apply on the reader's next pass. */
    private void catchUp() throws Exception {
        routeRest();
        findMethod("fetchVenueState").invoke(reader);
        findMethod("applyVenueState").invoke(reader);
    }

    private static Method findMethod(final String name) throws NoSuchMethodException {
        Class<?> type = PolymarketIntlOutboundReader.class;
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

    private void routeRest() throws Exception {
        when(restClient.get(
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
                    restPaths.add(path);
                    String best = null;
                    for (final String prefix : restAnswers.keySet()) {
                        if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                            best = prefix;
                        }
                    }
                    final HTTPResponse answer = mock(HTTPResponse.class);
                    final boolean found = best != null;
                    when(answer.isSuccess()).thenReturn(found);
                    when(answer.getStatusCode()).thenReturn(found ? 200 : 500);
                    when(answer.getBody())
                            .thenReturn(ByteBuffer.wrap(
                                    (found ? restAnswers.get(best) : "{}").getBytes(StandardCharsets.UTF_8)));
                    return answer;
                });
    }

    private static String tradePage(final String... trades) {
        return "{\"data\":[" + String.join(",", trades) + "],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":"
                + trades.length + "}";
    }

    private static String restTrade(
            final String tradeId, final String takerOrderId, final String price, final String size) {
        return "{\"id\":\"" + tradeId + "\",\"taker_order_id\":\"" + takerOrderId + "\",\"market\":\"condition-1\","
                + "\"asset_id\":\"token-yes\",\"side\":\"BUY\",\"size\":\"" + size + "\",\"fee_rate_bps\":\"0\","
                + "\"price\":\"" + price + "\",\"status\":\"CONFIRMED\",\"match_time\":\"1700000000\","
                + "\"last_update\":\"1700000005\",\"outcome\":\"YES\",\"maker_address\":\"0xabc\","
                + "\"owner\":\"test-key\",\"transaction_hash\":\"0x01\",\"bucket_index\":0,\"maker_orders\":[],"
                + "\"trader_side\":\"TAKER\"}";
    }

    private static String openOrders(final String... hashes) {
        final List<String> orders = new ArrayList<>();
        for (final String hash : hashes) {
            orders.add(restOrder(hash, "LIVE", "0"));
        }
        return "{\"data\":[" + String.join(",", orders) + "],\"next_cursor\":\"LTE=\",\"limit\":100,\"count\":"
                + hashes.length + "}";
    }

    private static String restOrder(final String hash, final String status, final String sizeMatched) {
        return "{\"id\":\"" + hash + "\",\"status\":\"" + status + "\",\"owner\":\"test-key\","
                + "\"maker_address\":\"0xabc\",\"market\":\"condition-1\",\"asset_id\":\"token-yes\","
                + "\"side\":\"BUY\",\"original_size\":\"10\",\"size_matched\":\"" + sizeMatched + "\","
                + "\"price\":\"0.55\",\"outcome\":\"YES\",\"created_at\":1699999000,\"order_type\":\"GTC\"}";
    }

    // ========== Fields the venue sends as null ==========

    @Test
    void nullMakerOrders_TakerFillStillReported() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.55", "4", "[]").replace("\"maker_orders\":[]", "\"maker_orders\":null"));

        assertEquals(qty("4"), awaitReport(1).decoder.filledQty());
    }

    @Test
    void nullTakerOrderId_DoesNotMisreadTheFieldsAfterIt() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", SOMEONE_ELSE, "0.55", "4", makerLeg(ORDER_A, "3", "0.55"))
                .replace("\"taker_order_id\":\"" + SOMEONE_ELSE + "\"", "\"taker_order_id\":null"));

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(qty("3"), fill.decoder.filledQty());
        assertEquals(1_700_000_000_000L * 1_000_000L, fill.decoder.timestampEvent(), "the field after it still read");
    }

    @Test
    void nullFieldsInAMakerLeg_AreSkipped() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade(
                "MATCHED",
                SOMEONE_ELSE,
                "0.55",
                "4",
                makerLeg(ORDER_A, "3", "0.55").replace("\"outcome\":\"YES\"", "\"outcome\":null")));

        assertEquals(qty("3"), awaitReport(1).decoder.filledQty());
    }

    @Test
    void failedSettlement_DoesNotCancelTheOrder() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));
        awaitReport(0);

        process(trade("FAILED", ORDER_A, "0.55", "4", "[]"));

        assertNoFurtherReports();
        assertEquals(List.of(), drainReleased());
    }

    // ========== Fees ==========

    @Test
    void takerFee_FollowsTheMarketsCurve() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.50", "10", "[]"));

        // 10 × 0.05 × (0.5 × 0.5) = 0.125
        assertEquals(125_000_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_AppliesTheExponent() throws Exception {
        reader = buildReader(new PolymarketIntlMarketInfo(
                Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.02, 2.0, true, false, 0));
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.50", "10", "[]"));

        // 10 × 0.02 × (0.5 × 0.5)^2 = 0.0125
        assertEquals(12_500_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void makerFee_ChargedWhenTheMarketIsNotTakerOnly() throws Exception {
        reader = buildReader(new PolymarketIntlMarketInfo(
                Statics.PRICE_SCALING_FACTOR / 100, qty("5"), false, 0.05, 1.0, false, false, 0));
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", SOMEONE_ELSE, "0.50", "10", makerLeg(ORDER_A, "10", "0.50")));

        assertEquals(125_000_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_RoundsToTheVenuesFiveDecimals() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.97", "1.37", "[]"));

        // 1.37 × 0.05 × (0.97 × 0.03) = 0.00199335 → 0.00199
        assertEquals(1_990_000L, awaitReport(1).decoder.fee());
    }

    @Test
    void takerFee_BelowTheSmallestUnitIsFree() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        process(orderEvent("PLACEMENT", "LIVE", ORDER_A));

        process(trade("MATCHED", ORDER_A, "0.999", "0.01", "[]"));

        // 0.01 × 0.05 × (0.999 × 0.001) ≈ 0.0000005 → rounds to zero
        assertEquals(0L, awaitReport(1).decoder.fee());
    }

    // ========== Framing ==========

    @Test
    void batchedFrame_HandlesEveryEvent() throws Exception {
        enqueueOrder(ORDER_A, 1L);
        enqueueOrder(ORDER_B, 2L);

        process("[" + orderEvent("PLACEMENT", "LIVE", ORDER_A) + "," + orderEvent("PLACEMENT", "LIVE", ORDER_B) + "]");

        assertEquals(1L, awaitReport(0).getClientOidCounter());
        assertEquals(2L, awaitReport(1).getClientOidCounter());
    }

    @Test
    void pong_IsIgnored() throws Exception {
        process("PONG");

        assertNoFurtherReports();
    }

    @Test
    void writerReject_IsPublished() throws Exception {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext ctx = writerReportQueue.indexAt(idx);
        ctx.reset();
        ctx.execType = ExecType.REJECT;
        ctx.orderStatus = OrderStatus.REJECTED;
        ctx.rejectReason = RejectReason.EXCHANGE_REJECTED;
        ctx.clientOidCounter = 5L;
        writerReportQueue.commit(idx);

        process("PONG");

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(RejectReason.EXCHANGE_REJECTED, report.decoder.rejectReason());
    }

    // ========== helpers ==========

    private PolymarketIntlOutboundReader buildReader(final PolymarketIntlMarketInfo marketInfo) {
        final Listing listing = new Listing(
                7,
                new Exchange(2, "POLYMARKET", "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "condition-1:token-yes",
                "TEST-YES");
        final PolymarketIntlOutboundReader built = new PolymarketIntlOutboundReader(
                new NullLogger(),
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                "test-key",
                "test-secret",
                "test-passphrase",
                marketInfo,
                new PolymarketIntlVenueOrderQuery(
                        restClient,
                        "clob.test",
                        new PolymarketIntlAuthHeaders("test-key", "c2VjcmV0", "test-passphrase", "0xabc")));
        built.pauseControl.release();
        return built;
    }

    /** Publishes a sentinel order's NEW and checks it is the only report after the ones already seen. */
    private void assertNoFurtherReports() throws Exception {
        poller.poll();
        final int seen = captured.size();
        enqueueOrder(SENTINEL, SENTINEL_ORDER_ID);
        process(orderEvent("PLACEMENT", "LIVE", SENTINEL));
        assertEquals(SENTINEL_ORDER_ID, awaitReport(seen).getClientOidCounter());
        assertEquals(seen + 1, captured.size());
    }

    private void process(final String json) throws Exception {
        when(client.read()).thenReturn(response);
        when(response.getBody()).thenReturn(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        reader.doWork();
    }

    private OrderExecutionReport awaitReport(final int index) throws Exception {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (captured.size() <= index && System.currentTimeMillis() < deadline) {
            poller.poll();
        }
        assertTrue(captured.size() > index, "expected report #" + index + ", got " + captured.size());
        return captured.get(index);
    }

    private void enqueueOrder(final String orderHash, final long clientOidCounter) {
        enqueueOrder(orderHash, clientOidCounter, 0L);
    }

    private void enqueueOrder(final String orderHash, final long clientOidCounter, final long submittedAtMillis) {
        enqueueOrder(orderHash, clientOidCounter, submittedAtMillis, ORIG_QTY, false);
    }

    private void enqueueOrder(
            final String orderHash,
            final long clientOidCounter,
            final long submittedAtMillis,
            final long originalQty,
            final boolean awaitsFinalFill) {
        final int idx = newOrderQueue.tryClaim();
        final OrderContext ctx = newOrderQueue.indexAt(idx);
        ctx.reset();
        ctx.clientOidCounter = clientOidCounter;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.originalQty = originalQty;
        ctx.leavesQty = originalQty;
        ctx.submittedAtMillis = submittedAtMillis;
        ctx.awaitsFinalFill = awaitsFinalFill;
        final byte[] hashBytes = orderHash.getBytes(StandardCharsets.US_ASCII);
        ctx.exchangeOrderIdLength = hashBytes.length;
        System.arraycopy(hashBytes, 0, ctx.exchangeOrderIdBytes, 0, hashBytes.length);
        ctx.correlationIdLength = hashBytes.length;
        System.arraycopy(hashBytes, 0, ctx.correlationIdBytes, 0, hashBytes.length);
        newOrderQueue.commit(idx);
    }

    private List<Long> drainReleased() {
        final List<Long> result = new ArrayList<>();
        releasedOrderQueue.read(ctx -> result.add(ctx.clientOidCounter), Integer.MAX_VALUE);
        return result;
    }

    private static String orderEvent(final String type, final String status, final String orderHash) {
        return "{\"asset_id\":\"token-yes\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\""
                + orderHash + "\",\"market\":\"condition-1\",\"original_size\":\"10\",\"outcome\":\"YES\","
                + "\"owner\":\"test-key\",\"price\":\"0.55\",\"side\":\"BUY\",\"size_matched\":\"0\","
                + "\"status\":\"" + status + "\",\"timestamp\":\"1700000000000\",\"type\":\"" + type + "\"}";
    }

    private static String trade(
            final String status, final String takerOrderId, final String price, final String size, String makers) {
        return trade("trade-1", status, takerOrderId, price, size, makers);
    }

    private static String trade(
            final String tradeId,
            final String status,
            final String takerOrderId,
            final String price,
            final String size,
            String makers) {
        if (!makers.startsWith("[")) {
            makers = "[" + makers + "]";
        }
        return "{\"asset_id\":\"token-yes\",\"event_type\":\"trade\",\"fee_rate_bps\":\"0\",\"id\":\""
                + tradeId + "\",\"maker_orders\":" + makers + ",\"market\":\"condition-1\",\"outcome\":\"YES\","
                + "\"owner\":\"test-key\",\"price\":\"" + price + "\",\"side\":\"BUY\",\"size\":\"" + size + "\","
                + "\"status\":\"" + status + "\",\"taker_order_id\":\"" + takerOrderId + "\","
                + "\"timestamp\":\"1700000000000\",\"trader_side\":\"TAKER\",\"type\":\"TRADE\"}";
    }

    /** A resting order on the market's other outcome, matched with ours by minting or merging. */
    private static String otherOutcomeLeg(final String orderId, final String matchedAmount, final String price) {
        return "{\"asset_id\":\"token-no\",\"matched_amount\":\"" + matchedAmount + "\",\"order_id\":\"" + orderId
                + "\",\"outcome\":\"NO\",\"owner\":\"other\",\"price\":\"" + price + "\"}";
    }

    private static String makerLeg(final String orderId, final String matchedAmount, final String price) {
        return "{\"asset_id\":\"token-yes\",\"matched_amount\":\"" + matchedAmount + "\",\"order_id\":\"" + orderId
                + "\",\"outcome\":\"YES\",\"owner\":\"other\",\"price\":\"" + price + "\"}";
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
