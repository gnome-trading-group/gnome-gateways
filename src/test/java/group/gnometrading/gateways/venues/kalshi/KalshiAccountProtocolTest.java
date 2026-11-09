package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.gateways.account.reader.RecordedReports;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderReports;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Side;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Kalshi's account-wide socket: orders of two sessions on two markets, one stream of events, and catch-up. */
class KalshiAccountProtocolTest {

    private static final int EXCHANGE_ID = 1;
    private static final long MARKET_A = 10;
    private static final long MARKET_B = 11;

    private final HTTPClient restClient = mock(HTTPClient.class);
    private final List<String> restPaths = new ArrayList<>();
    private final RecordedReports recorded = new RecordedReports();
    private final OrderReports reports = new OrderReports(64, SystemEpochNanoClock.INSTANCE, this.recorded);
    private String restAnswer = "{\"cursor\":\"\",\"orders\":[]}";
    private KalshiAccountProtocol protocol;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.protocol = new KalshiAccountProtocol(
                new KalshiAuthSigner("key", generator.generateKeyPair().getPrivate()),
                this.restClient,
                "api.test",
                SystemEpochNanoClock.INSTANCE);
        this.protocol.addMarket(EXCHANGE_ID, MARKET_A, FeeTimeline.EMPTY);
        this.protocol.addMarket(EXCHANGE_ID, MARKET_B, FeeTimeline.EMPTY);
        when(this.restClient.get(
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
                    this.restPaths.add(call.getArgument(2));
                    final HTTPResponse answer = mock(HTTPResponse.class);
                    when(answer.isSuccess()).thenReturn(true);
                    when(answer.getStatusCode()).thenReturn(200);
                    when(answer.getBody())
                            .thenReturn(ByteBuffer.wrap(this.restAnswer.getBytes(StandardCharsets.UTF_8)));
                    return answer;
                });
    }

    @Test
    void eventsForTwoSessionsOrdersAreReportedToEach() {
        register(7, 1, MARKET_A, "alpha-1");
        register(8, 1, MARKET_B, "beta-1");
        frame(update("beta-1", "resting", "0.00", "3.00"));
        frame(update("alpha-1", "executed", "3.00", "0.00"));

        assertEquals(List.of(ExecType.NEW, ExecType.FILL), this.recorded.execTypes());
        assertEquals(8, this.recorded.reports.get(0).slot());
        assertEquals(MARKET_B, this.recorded.reports.get(0).securityId());
        assertEquals(7, this.recorded.reports.get(1).slot());
        assertEquals(MARKET_A, this.recorded.reports.get(1).securityId());
        assertEquals(List.of(1L), this.recorded.released);
    }

    @Test
    void kalshisIdForAnOrderIsLearnedFromItsFirstEvent() {
        register(7, 1, MARKET_A, "alpha-1");
        frame(update("alpha-1", "resting", "0.00", "3.00"));
        frame(update("alpha-1", "executed", "3.00", "0.00"));
        assertEquals(List.of("venue-alpha-1"), this.recorded.venueIds, "handed to the lane once");
    }

    @Test
    void anotherAccountHoldersOrderIsSkipped() {
        register(7, 1, MARKET_A, "alpha-1");
        frame(update("someone-else-9", "executed", "3.00", "0.00"));
        assertTrue(this.recorded.reports.isEmpty());
    }

    @Test
    void catchUpListsEveryMarketsOrdersSinceTheSocketWasLastHeard() throws Exception {
        register(7, 1, MARKET_A, "alpha-1");
        register(8, 1, MARKET_B, "beta-1");
        this.restAnswer = "{\"cursor\":\"\",\"orders\":[" + listed("alpha-1", "canceled", "1.00", "0.00") + ","
                + listed("beta-1", "resting", "0.00", "3.00") + "]}";

        this.protocol.fetchCatchUp(1_800_000_000_000L, this.reports.held());
        this.protocol.applyCatchUp(this.reports);

        assertEquals("/trade-api/v2/portfolio/orders?limit=200&min_ts=1799999940", this.restPaths.get(0));
        assertEquals(
                List.of(ExecType.PARTIAL_FILL, ExecType.CANCEL, ExecType.NEW),
                this.recorded.execTypes(),
                "alpha filled 1 and was cancelled while the socket was down; beta rests");
    }

    private void register(final long slot, final long counter, final long market, final String clientOrderId) {
        final OrderContext ctx = RecordedReports.order(slot, counter, EXCHANGE_ID, market, clientOrderId, 3_000_000L);
        ctx.side = Side.Bid;
        this.reports.register(ctx);
    }

    private void frame(final String json) {
        this.reports.receivedAt(System.nanoTime());
        this.protocol.onFrame(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), this.reports);
    }

    private static String update(
            final String clientOrderId, final String status, final String fillCount, final String remaining) {
        return "{\"type\":\"user_order\",\"sid\":1,\"msg\":{"
                + "\"order_id\":\"venue-" + clientOrderId + "\","
                + "\"client_order_id\":\"" + clientOrderId + "\","
                + "\"status\":\"" + status + "\","
                + "\"fill_count_fp\":\"" + fillCount + "\","
                + "\"remaining_count_fp\":\"" + remaining + "\","
                + "\"taker_fill_cost_dollars\":\"0.000000\","
                + "\"maker_fill_cost_dollars\":\"1.500000\","
                + "\"last_updated_ts_ms\":1800000000000"
                + "}}";
    }

    private static String listed(
            final String clientOrderId, final String status, final String fillCount, final String remaining) {
        return "{\"action\":\"buy\",\"client_order_id\":\"" + clientOrderId + "\","
                + "\"created_time\":\"2026-10-07T18:00:00.000000Z\",\"fill_count_fp\":\"" + fillCount + "\","
                + "\"initial_count_fp\":\"3.00\",\"last_update_time\":\"2026-10-07T18:01:40.25Z\","
                + "\"maker_fees_dollars\":\"0.000000\",\"maker_fill_cost_dollars\":\"0.500000\","
                + "\"order_id\":\"venue-" + clientOrderId + "\",\"outcome_side\":\"yes\","
                + "\"remaining_count_fp\":\"" + remaining + "\",\"status\":\"" + status + "\","
                + "\"taker_fees_dollars\":\"0.000000\",\"taker_fill_cost_dollars\":\"0.000000\","
                + "\"ticker\":\"KX-A\",\"type\":\"limit\",\"yes_price_dollars\":\"0.5000\"}";
    }
}
