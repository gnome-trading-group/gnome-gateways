package group.gnometrading.gateways.venues.polymarket.us;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Payloads follow what the private socket sent in the 2026-10-08 live checks, trimmed of market metadata. */
class PolymarketUsOutboundReaderTest {

    private static final String SLUG = "tec-mlb-champ-2026-09-27-lad";
    private static final long FIXED_NANO = 1_791_470_000_000_000_000L;
    private static final int SLOT = 4242;
    private static final String STAMP_1 =
            PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(SLOT, 1));
    private static final String STAMP_2 =
            PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(SLOT, 2));

    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private SequencedPoller poller;
    private ManyToOneRingBuffer<OrderContext> newOrderQueue;
    private ManyToOneRingBuffer<OrderContext> writerReportQueue;
    private ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private WebSocketClient client;
    private WebSocketResponse response;
    private HTTPClient restClient;
    private PolymarketUsOutboundReader reader;
    private List<OrderExecutionReport> captured;
    private final Map<String, String> restAnswers = new HashMap<>();
    private final List<String> restPaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
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
        reader = buildReader(":long");
    }

    // ========== Resting orders, matched by stamp ==========

    @Test
    void newExecution_AcknowledgesTheOrderItsStampNames() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.01", "1");

        process(execution(
                "EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.01", 1, 0, "0", STAMP_1)));

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.NEW, report.decoder.execType());
        assertEquals(1L, report.getClientOidCounter());
        assertEquals(qty("1"), report.decoder.leavesQty());
        assertEquals(STAMP_1, exchangeOrderId(report));
    }

    @Test
    void stampWithoutMillis_IsMatchedByTime_NotText() throws Exception {
        final String wholeSecond = PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.BASE_MILLIS + 5_000);
        enqueueResting(wholeSecond, 1, Side.Bid, "0.01", "1");

        process(execution(
                "EXECUTION_TYPE_NEW",
                order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.01", 1, 0, "0", "2100-01-01T00:00:05Z")));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
    }

    @Test
    void repeatedNew_IsNotAcknowledgedTwice() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.01", "1");
        final String newEvent =
                execution("EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.01", 1, 0, "0", STAMP_1));
        process(newEvent);
        awaitReport(0);

        process(newEvent);

        assertNoFurtherReports();
    }

    @Test
    void fill_IsPricedAtItsOwnPrice_WithTheVenuesFee() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.44", "1");
        process(execution(
                "EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.44", 1, 0, "0", STAMP_1)));

        process(fill(
                order("V1", "ORDER_STATE_FILLED", "BUY_LONG", "0.44", 1, 1, "0.4360", STAMP_1),
                "0.0200",
                "1.0000",
                "0.4360",
                true));

        final OrderExecutionReport report = awaitReport(1);
        assertEquals(ExecType.FILL, report.decoder.execType());
        assertEquals(OrderStatus.FILLED, report.decoder.orderStatus());
        assertEquals(qty("1"), report.decoder.filledQty());
        assertEquals(price("0.436"), report.decoder.fillPrice());
        assertEquals(price("0.02"), report.decoder.fee());
        assertEquals(Liquidity.TAKER, report.decoder.liquidity());
        assertEquals(RejectReason.NULL_VAL, report.decoder.rejectReason());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void partialFills_ReportEachFill_AndRedeliveryChangesNothing() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "5");
        final String first = fill(
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_LONG", "0.40", 5, 2, "0.4000", STAMP_1),
                "0.0100",
                "2.0000",
                "0.4000",
                false);
        process(first);
        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        final OrderExecutionReport partial = awaitReport(1);
        assertEquals(ExecType.PARTIAL_FILL, partial.decoder.execType());
        assertEquals(qty("2"), partial.decoder.filledQty());
        assertEquals(qty("3"), partial.decoder.leavesQty());
        assertEquals(Liquidity.MAKER, partial.decoder.liquidity());
        assertEquals(price("0.01"), partial.decoder.fee());

        process(first);
        process(fill(
                order("V1", "ORDER_STATE_FILLED", "BUY_LONG", "0.40", 5, 5, "0.4000", STAMP_1),
                "0.0300",
                "3.0000",
                "0.4000",
                false));

        final OrderExecutionReport rest = awaitReport(2);
        assertEquals(ExecType.FILL, rest.decoder.execType());
        assertEquals(qty("3"), rest.decoder.filledQty());
        assertEquals(qty("5"), rest.decoder.cumulativeQty());
        assertEquals(price("0.02"), rest.decoder.fee());
        assertEquals(3, captured.size());
    }

    @Test
    void canceled_EndsTheOrderWithItsFillsSoFar() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "5");
        process(fill(
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_LONG", "0.40", 5, 2, "0.4000", STAMP_1),
                "0.0100",
                "2.0000",
                "0.4000",
                false));
        awaitReport(1);

        process(execution(
                "EXECUTION_TYPE_CANCELED",
                order("V1", "ORDER_STATE_CANCELED", "BUY_LONG", "0.40", 5, 2, "0.4000", STAMP_1)));

        final OrderExecutionReport report = awaitReport(2);
        assertEquals(ExecType.CANCEL, report.decoder.execType());
        assertEquals(qty("2"), report.decoder.cumulativeQty());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void laterEvents_AreMatchedByVenueId() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "1");
        process(execution(
                "EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.40", 1, 0, "0", STAMP_1)));
        awaitReport(0);

        process(execution(
                "EXECUTION_TYPE_CANCELED", order("V1", "ORDER_STATE_CANCELED", "BUY_LONG", "0.40", 1, 0, "0", null)));

        assertEquals(ExecType.CANCEL, awaitReport(1).decoder.execType());
    }

    @Test
    void rejectedAfterTakingIt_IsAReject() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "1");

        process(execution(
                "EXECUTION_TYPE_REJECTED",
                order("V1", "ORDER_STATE_REJECTED", "BUY_LONG", "0.40", 1, 0, "0", STAMP_1)));

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(RejectReason.EXCHANGE_REJECTED, report.decoder.rejectReason());
        assertEquals(List.of(1L), drainReleased());
    }

    @Test
    void rejectedWithACodeNotYetMapped_IsAPlainVenueRefusal() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "1");

        process(execution(
                        "EXECUTION_TYPE_REJECTED",
                        order("V1", "ORDER_STATE_REJECTED", "BUY_LONG", "0.40", 1, 0, "0", STAMP_1))
                .replace(
                        "\"text\":\"\"",
                        "\"text\":\"order refused\", \"orderRejectReason\":\"ORD_REJECT_REASON_EXCHANGE_OPTION\""));

        final OrderExecutionReport report = awaitReport(0);
        assertEquals(ExecType.REJECT, report.decoder.execType());
        assertEquals(RejectReason.EXCHANGE_REJECTED, report.decoder.rejectReason());
    }

    @Test
    void execution_ReadsTheRejectCodeAndText_AndForgetsThemOnReset() {
        final PolymarketUsOrderFields fields = new PolymarketUsOrderFields();
        fields.reset();
        final String execution = "{\"type\":\"EXECUTION_TYPE_REJECTED\", \"text\":\"order refused\", "
                + "\"orderRejectReason\":\"ORD_REJECT_REASON_EXCHANGE_OPTION\"}";
        try (var root = new JsonDecoder().wrap(ByteBuffer.wrap(execution.getBytes(StandardCharsets.UTF_8)));
                var obj = root.asObject()) {
            fields.readExecution(obj);
        }

        assertEquals(
                "ORD_REJECT_REASON_EXCHANGE_OPTION",
                new String(fields.orderRejectReason, 0, fields.orderRejectReasonLength, StandardCharsets.US_ASCII));
        assertEquals("order refused", new String(fields.text, 0, fields.textLength, StandardCharsets.UTF_8));
        assertEquals(RejectReason.EXCHANGE_REJECTED, fields.rejectReason());

        fields.reset();
        assertEquals(0, fields.orderRejectReasonLength);
        assertEquals(0, fields.textLength);
    }

    @Test
    void otherOrders_AreIgnored() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "1");

        process(execution("EXECUTION_TYPE_NEW", order("X", "ORDER_STATE_NEW", "BUY_LONG", "0.40", 1, 0, "0", STAMP_2)));
        process(execution(
                "EXECUTION_TYPE_NEW",
                order("Y", "ORDER_STATE_NEW", "BUY_LONG", "0.40", 1, 0, "0", "2026-12-31T23:59:59.123Z")));
        process(execution(
                "EXECUTION_TYPE_NEW", order("Z", "ORDER_STATE_NEW", "BUY_SHORT", "0.40", 1, 0, "0", STAMP_1)));

        assertNoFurtherReports();
    }

    @Test
    void shortListing_ReportsFillsInNoTerms() throws Exception {
        reader = buildReader(":short");
        enqueueResting(STAMP_1, 1, Side.Bid, "0.45", "1");

        process(fill(
                order("V1", "ORDER_STATE_FILLED", "BUY_SHORT", "0.45", 1, 1, "0.4400", STAMP_1),
                "0.0200",
                "1.0000",
                "0.4400",
                true));

        assertEquals(price("0.56"), awaitReport(1).decoder.fillPrice());
    }

    // ========== Amends ==========

    @Test
    void acceptedAmend_IsAcknowledgedWithWhatIsLeftAfterTheFills() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.06", "3");
        process(fill(
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_LONG", "0.06", 3, 2, "0.0600", STAMP_1),
                "0.0000",
                "2.0000",
                "0.0600",
                true));
        awaitReport(1);

        process(execution(
                "EXECUTION_TYPE_REPLACE",
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_LONG", "0.06", 4, 2, "0.0600", STAMP_1)));
        amendNotice(STAMP_1, 1, qty("4"));
        process("");

        final OrderExecutionReport ack = awaitReport(2);
        assertEquals(ExecType.NEW, ack.decoder.execType());
        assertEquals(qty("2"), ack.decoder.cumulativeQty());
        assertEquals(qty("2"), ack.decoder.leavesQty());
        assertEquals(3, captured.size(), "the REPLACE event itself reports nothing");
    }

    @Test
    void amendDownToTheFills_EndsTheOrder() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.06", "3");
        process(fill(
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "BUY_LONG", "0.06", 3, 2, "0.0600", STAMP_1),
                "0.0000",
                "2.0000",
                "0.0600",
                true));
        awaitReport(1);

        process(execution(
                "EXECUTION_TYPE_REPLACE",
                order("V1", "ORDER_STATE_FILLED", "BUY_LONG", "0.05", 2, 2, "0.0600", STAMP_1)));

        final OrderExecutionReport end = awaitReport(2);
        assertEquals(ExecType.CANCEL, end.decoder.execType());
        assertEquals(qty("2"), end.decoder.cumulativeQty());
        assertEquals(List.of(1L), drainReleased());
    }

    // ========== IOCs, matched by terms ==========

    @Test
    void ioc_IsMatchedByItsTerms_AndEndsAtItsFinalFill() throws Exception {
        enqueueImmediate(1, Side.Ask, "0.435", "3");

        process(fill(
                order("V1", "ORDER_STATE_PARTIALLY_FILLED", "SELL_LONG", "0.435", 3, 1, "0.4350", null, true),
                "0.0200",
                "1.0000",
                "0.4350",
                true));
        process(execution(
                "EXECUTION_TYPE_EXPIRED",
                order("V1", "ORDER_STATE_EXPIRED", "SELL_LONG", "0.435", 3, 1, "0.4350", null, true)));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        assertEquals(ExecType.PARTIAL_FILL, awaitReport(1).decoder.execType());
        final OrderExecutionReport end = awaitReport(2);
        assertEquals(ExecType.CANCEL, end.decoder.execType());
        assertEquals(qty("1"), end.decoder.cumulativeQty());
    }

    @Test
    void liveIocFill_IsMatchedByItsTerms() throws Exception {
        enqueueImmediate(1, Side.Bid, "0.436", "1");

        process(new String(
                getClass()
                        .getResourceAsStream("/polymarket-us/ioc-fill-execution.json")
                        .readAllBytes(),
                StandardCharsets.UTF_8));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.FILL, fill.decoder.execType());
        assertEquals(price("0.436"), fill.decoder.fillPrice());
        assertEquals(price("0.02"), fill.decoder.fee());
    }

    @Test
    void identicalIocs_AreMatchedOldestFirst() throws Exception {
        enqueueImmediate(1, Side.Bid, "0.436", "1");
        enqueueImmediate(2, Side.Bid, "0.436", "1");

        process(execution(
                "EXECUTION_TYPE_EXPIRED",
                order("V1", "ORDER_STATE_EXPIRED", "BUY_LONG", "0.436", 1, 0, "0", null, true)));

        assertEquals(1L, awaitReport(0).getClientOidCounter());
    }

    @Test
    void iocTheVenueCut_IsStillMatched() throws Exception {
        enqueueImmediate(1, Side.Bid, "0.432", "2.5");

        process(fill(
                order("V1", "ORDER_STATE_FILLED", "BUY_LONG", "0.432", 2, 2, "0.4320", null, true),
                "0.0300",
                "2.0000",
                "0.4320",
                true));

        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        assertEquals(ExecType.PARTIAL_FILL, awaitReport(1).decoder.execType());
        final OrderExecutionReport end = awaitReport(2);
        assertEquals(ExecType.CANCEL, end.decoder.execType(), "the venue's order is done at its own size");
        assertEquals(qty("2"), end.decoder.cumulativeQty());
    }

    @Test
    void iocWithOtherTerms_IsNotMatched() throws Exception {
        enqueueImmediate(1, Side.Bid, "0.436", "1");

        process(execution(
                "EXECUTION_TYPE_EXPIRED",
                order("V1", "ORDER_STATE_EXPIRED", "BUY_LONG", "0.437", 1, 0, "0", null, true)));
        process(execution(
                "EXECUTION_TYPE_EXPIRED",
                order("V2", "ORDER_STATE_EXPIRED", "BUY_LONG", "0.436", 2, 0, "0", null, true)));

        assertNoFurtherReports();
    }

    @Test
    void venueIdFromTheWriter_MatchesWithoutTerms() throws Exception {
        enqueueImmediate(1, Side.Bid, "0.436", "1");
        venueIdNotice("ioc-" + SLOT + "-1", 1, "V1");

        process(execution(
                "EXECUTION_TYPE_EXPIRED",
                order("V1", "ORDER_STATE_EXPIRED", "BUY_LONG", "0.999", 9, 0, "0", null, true)));

        assertEquals(ExecType.CANCEL, awaitReport(0).decoder.execType());
    }

    // ========== Catch-up ==========

    @Test
    void catchUp_ReportsWhatHappenedWhileDisconnected() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.40", "2");
        enqueueResting(STAMP_2, 2, Side.Bid, "0.30", "1");
        process(execution(
                "EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.40", 2, 0, "0", STAMP_1)));
        awaitReport(0);

        restAnswers.put(
                "/v1/orders/open",
                "{\"orders\":[" + order("V2", "ORDER_STATE_NEW", "BUY_LONG", "0.30", 1, 0, "0", STAMP_2) + "]}");
        restAnswers.put(
                "/v1/order/V1",
                "{\"order\":"
                        + order(
                                "V1",
                                "ORDER_STATE_FILLED",
                                "BUY_LONG",
                                "0.40",
                                2,
                                2,
                                "0.3950",
                                STAMP_1,
                                false,
                                "0.0400")
                        + "}");
        catchUp();

        assertTrue(restPaths.contains("/v1/orders/open?slugs=" + SLUG), restPaths.toString());
        final OrderExecutionReport rested = awaitReport(1);
        assertEquals(ExecType.NEW, rested.decoder.execType());
        assertEquals(2L, rested.getClientOidCounter());
        final OrderExecutionReport fill = awaitReport(2);
        assertEquals(ExecType.FILL, fill.decoder.execType());
        assertEquals(1L, fill.getClientOidCounter());
        assertEquals(qty("2"), fill.decoder.filledQty());
        assertEquals(price("0.395"), fill.decoder.fillPrice());
        assertEquals(price("0.04"), fill.decoder.fee());
        assertEquals(Liquidity.TAKER, fill.decoder.liquidity(), "a fee is only charged to takers");
    }

    @Test
    void catchUp_FillWithARebate_IsAMakerFill() throws Exception {
        enqueueResting(STAMP_1, 1, Side.Bid, "0.35", "3");
        process(execution(
                "EXECUTION_TYPE_NEW", order("V1", "ORDER_STATE_NEW", "BUY_LONG", "0.35", 3, 0, "0", STAMP_1)));
        awaitReport(0);

        restAnswers.put("/v1/orders/open", "{\"orders\":[]}");
        restAnswers.put(
                "/v1/order/V1",
                "{\"order\":"
                        + order(
                                "V1",
                                "ORDER_STATE_FILLED",
                                "BUY_LONG",
                                "0.35",
                                3,
                                3,
                                "0.3500",
                                STAMP_1,
                                false,
                                "-0.0100")
                        + "}");
        catchUp();

        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(price("-0.01"), fill.decoder.fee());
        assertEquals(Liquidity.MAKER, fill.decoder.liquidity());
    }

    @Test
    void catchUp_LooksUpAnOrderSentWhileDisconnected() throws Exception {
        restAnswers.put("/v1/orders/open", "{\"orders\":[]}");
        restAnswers.put(
                "/v1/order/V9",
                "{\"order\":"
                        + order(
                                "V9",
                                "ORDER_STATE_FILLED",
                                "BUY_LONG",
                                "0.44",
                                1,
                                1,
                                "0.4350",
                                STAMP_1,
                                false,
                                "0.0200")
                        + "}");
        stubRest();
        // Fetched while the order and its venue id are still queued for the reader.
        findMethod("fetchVenueState").invoke(reader);
        enqueueResting(STAMP_1, 1, Side.Bid, "0.44", "1");
        venueIdNotice(STAMP_1, 1, "V9");
        process("");

        findMethod("applyVenueState").invoke(reader);

        assertTrue(restPaths.contains("/v1/order/V9"), restPaths.toString());
        assertEquals(ExecType.NEW, awaitReport(0).decoder.execType());
        final OrderExecutionReport fill = awaitReport(1);
        assertEquals(ExecType.FILL, fill.decoder.execType());
        assertEquals(price("0.435"), fill.decoder.fillPrice());
        assertEquals(price("0.02"), fill.decoder.fee());
    }

    // ========== helpers ==========

    private PolymarketUsOutboundReader buildReader(final String suffix) throws Exception {
        final Listing listing = new Listing(
                7,
                new Exchange(2, "POLYMARKET_US", "Polymarket US", "us"),
                new Security(3, "TEST", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                SLUG + suffix,
                "TEST");
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(
                "key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate());
        final PolymarketUsOutboundReader built = new PolymarketUsOutboundReader(
                new NullLogger(),
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                () -> FIXED_NANO,
                listing,
                client,
                new JsonDecoder(),
                signer,
                new PolymarketUsVenueOrderQuery(restClient, "api.test", signer, () -> FIXED_NANO / 1_000_000L));
        built.pauseControl.release();
        return built;
    }

    private void catchUp() throws Exception {
        stubRest();
        findMethod("fetchVenueState").invoke(reader);
        findMethod("applyVenueState").invoke(reader);
    }

    private void stubRest() throws Exception {
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
                    final String path = call.getArgument(2);
                    restPaths.add(path);
                    String best = null;
                    for (final String prefix : restAnswers.keySet()) {
                        if (path.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                            best = prefix;
                        }
                    }
                    final HTTPResponse answer = mock(HTTPResponse.class);
                    when(answer.isSuccess()).thenReturn(best != null);
                    when(answer.getStatusCode()).thenReturn(best != null ? 200 : 404);
                    when(answer.getBody())
                            .thenReturn(ByteBuffer.wrap(
                                    (best != null ? restAnswers.get(best) : "{}").getBytes(StandardCharsets.UTF_8)));
                    return answer;
                });
    }

    private static Method findMethod(final String name) throws NoSuchMethodException {
        Class<?> type = PolymarketUsOutboundReader.class;
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

    /** Publishes a sentinel order's NEW and checks it is the only report after the ones already seen. */
    private void assertNoFurtherReports() throws Exception {
        poller.poll();
        final int seen = captured.size();
        final String sentinel = PolymarketUsOutboundWriterTest.stampText(PolymarketUsStamps.stampMillis(SLOT, 99));
        enqueueResting(sentinel, 99, Side.Bid, "0.01", "1");
        process(execution(
                "EXECUTION_TYPE_NEW", order("S", "ORDER_STATE_NEW", "BUY_LONG", "0.01", 1, 0, "0", sentinel)));
        assertEquals(99L, awaitReport(seen).getClientOidCounter());
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

    private void enqueueResting(
            final String stamp, final long counter, final Side side, final String limit, final String size) {
        enqueue(stamp, counter, side, limit, size);
    }

    private void enqueueImmediate(final long counter, final Side side, final String limit, final String size) {
        enqueue("ioc-" + SLOT + "-" + counter, counter, side, limit, size);
    }

    private void enqueue(
            final String correlationId, final long counter, final Side side, final String limit, final String size) {
        final int idx = newOrderQueue.tryClaim();
        final OrderContext ctx = newOrderQueue.indexAt(idx);
        ctx.reset();
        ctx.clientOidCounter = counter;
        ctx.exchangeId = 2;
        ctx.securityId = 3L;
        ctx.side = side;
        ctx.originalQty = qty(size);
        ctx.leavesQty = ctx.originalQty;
        ctx.limitPrice = price(limit);
        final byte[] id = correlationId.getBytes(StandardCharsets.US_ASCII);
        ctx.correlationIdLength = id.length;
        System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
        newOrderQueue.commit(idx);
    }

    private void amendNotice(final String correlationId, final long counter, final long newSize) {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext notice = writerReportQueue.indexAt(idx);
        notice.reset();
        notice.amendAccepted = true;
        notice.clientOidCounter = counter;
        notice.originalQty = newSize;
        notice.cumulativeFilledQty = OrderContext.QTY_ABSENT;
        notice.leavesQty = OrderContext.QTY_ABSENT;
        final byte[] id = correlationId.getBytes(StandardCharsets.US_ASCII);
        notice.correlationIdLength = id.length;
        System.arraycopy(id, 0, notice.correlationIdBytes, 0, id.length);
        writerReportQueue.commit(idx);
    }

    private void venueIdNotice(final String correlationId, final long counter, final String venueId) {
        final int idx = writerReportQueue.tryClaim();
        final OrderContext notice = writerReportQueue.indexAt(idx);
        notice.reset();
        notice.venueOrderIdNotice = true;
        notice.clientOidCounter = counter;
        final byte[] id = correlationId.getBytes(StandardCharsets.US_ASCII);
        notice.correlationIdLength = id.length;
        System.arraycopy(id, 0, notice.correlationIdBytes, 0, id.length);
        final byte[] venue = venueId.getBytes(StandardCharsets.US_ASCII);
        notice.exchangeOrderIdLength = venue.length;
        System.arraycopy(venue, 0, notice.exchangeOrderIdBytes, 0, venue.length);
        writerReportQueue.commit(idx);
    }

    private List<Long> drainReleased() {
        final List<Long> result = new ArrayList<>();
        releasedOrderQueue.read(ctx -> result.add(ctx.clientOidCounter), Integer.MAX_VALUE);
        return result;
    }

    private static String execution(final String type, final String order) {
        return "{\"requestId\":\"orders\", \"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\", "
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\", \"order\":" + order
                + ", \"lastShares\":\"0.0000\", \"lastPx\":{\"value\":\"0.0000\", \"currency\":\"USD\"}, \"type\":\""
                + type + "\", \"text\":\"\", \"tradeId\":\"\", \"aggressor\":false, "
                + "\"transactTime\":\"2026-10-08T14:33:25.874628732Z\"}}}";
    }

    private static String fill(
            final String order,
            final String commission,
            final String lastShares,
            final String lastPx,
            final boolean aggressor) {
        return "{\"requestId\":\"orders\", \"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\", "
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"E\", \"order\":"
                + order.replace(
                        "\"commissionNotionalTotalCollected\":{\"value\":\"0.0000\"",
                        "\"commissionNotionalTotalCollected\":{\"value\":\"" + commission + "\"")
                + ", \"lastShares\":\"" + lastShares + "\", \"lastPx\":{\"value\":\"" + lastPx
                + "\", \"currency\":\"USD\"}, \"type\":\"EXECUTION_TYPE_FILL\", \"tradeId\":\"T\", \"aggressor\":"
                + aggressor + ", \"transactTime\":\"2026-10-08T14:37:56.769167653Z\"}}}";
    }

    private static String order(
            final String id,
            final String state,
            final String intent,
            final String price,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime) {
        return order(id, state, intent, price, quantity, cumQuantity, avgPx, goodTillTime, false);
    }

    private static String order(
            final String id,
            final String state,
            final String intent,
            final String price,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime,
            final boolean immediate) {
        return order(id, state, intent, price, quantity, cumQuantity, avgPx, goodTillTime, immediate, "0.0000");
    }

    private static String order(
            final String id,
            final String state,
            final String intent,
            final String price,
            final long quantity,
            final long cumQuantity,
            final String avgPx,
            final String goodTillTime,
            final boolean immediate,
            final String commission) {
        return "{\"id\":\"" + id + "\", \"marketSlug\":\"" + SLUG + "\", \"side\":\"ORDER_SIDE_BUY\", "
                + "\"type\":\"ORDER_TYPE_LIMIT\", \"price\":{\"value\":\"" + price + "\", \"currency\":\"USD\"}, "
                + "\"quantity\":" + quantity + ", \"cumQuantity\":" + cumQuantity + ", \"leavesQuantity\":"
                + (quantity - cumQuantity) + ", \"tif\":\""
                + (immediate ? "TIME_IN_FORCE_IMMEDIATE_OR_CANCEL" : "TIME_IN_FORCE_GOOD_TILL_DATE")
                + "\", \"goodTillTime\":" + (goodTillTime == null ? "null" : "\"" + goodTillTime + "\"")
                + ", \"intent\":\"ORDER_INTENT_" + intent + "\", \"marketMetadata\":{\"slug\":\"" + SLUG
                + "\", \"team\":{\"id\":3014}}, \"state\":\"" + state + "\", "
                + "\"commissionNotionalTotalCollected\":{\"value\":\"" + commission + "\", \"currency\":\"USD\"}, "
                + "\"avgPx\":{\"value\":\"" + avgPx + "\", \"currency\":\"USD\"}, "
                + "\"createTime\":\"2026-10-08T14:33:25.872677584Z\"}";
    }

    private static String exchangeOrderId(final OrderExecutionReport report) {
        final StringBuilder id = new StringBuilder();
        for (int i = 0; i < OrderExecutionReportDecoder.exchangeOrderIdLength(); i++) {
            final byte b = report.decoder.exchangeOrderId(i);
            if (b == 0) {
                break;
            }
            id.append((char) b);
        }
        return id.toString();
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
