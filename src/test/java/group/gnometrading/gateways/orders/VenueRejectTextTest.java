package group.gnometrading.gateways.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.schemas.RejectReason;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VenueRejectTextTest {

    private static ByteBuffer body(final String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void kalshiNoCashOnTheMarketsShardIsInsufficientFunds() {
        final ByteBuffer body = body("{\"error\":{\"code\":\"insufficient_shard_balance\","
                + "\"message\":\"insufficient shard balance\","
                + "\"details\":\"This account has no balance on the exchange index (shard) this market trades on. "
                + "API orders are not funded automatically: transfer funds to that exchange index with "
                + "POST /portfolio/intra_exchange_instance_transfer, then retry.\"}}");

        assertEquals(RejectReason.INSUFFICIENT_FUNDS, VenueRejectText.KALSHI.classify(body));
        assertEquals(0, body.position(), "the body is left as it was, for the log");
    }

    @Test
    void kalshiInsufficientBalanceIsInsufficientFunds() {
        assertEquals(
                RejectReason.INSUFFICIENT_FUNDS,
                VenueRejectText.KALSHI.classify(body("{\"error\":{\"code\":\"INSUFFICIENT_BALANCE\"}}")));
    }

    @Test
    void kalshiPostOnlyCrossWouldCross() {
        assertEquals(
                RejectReason.POST_ONLY_WOULD_CROSS,
                VenueRejectText.KALSHI.classify(
                        body("{\"error\":{\"code\":\"invalid_order\",\"details\":\"post only cross\"}}")));
    }

    @Test
    void anUnknownRefusalHasNoReason() {
        assertNull(VenueRejectText.KALSHI.classify(body("{\"error\":{\"code\":\"market_closed\"}}")));
        assertNull(VenueRejectText.KALSHI.classify(null));
    }
}
