package group.gnometrading.gateways.venues.kalshi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Kalshi's own time in its order answers, as the probe of 2026-11-14 saw them. */
class KalshiOrderTimingTest {

    private final KalshiOrderCodec codec = new KalshiOrderCodec("maker");

    @Test
    void aCreateAnswersTsMsIsRead() {
        final ByteBuffer body = body("{\"client_order_id\":\"c-1\",\"fill_count\":\"0.00\",\"order_id\":\"o-1\","
                + "\"remaining_count\":\"1.00\",\"ts_ms\":1794671966418}");

        assertEquals(1794671966418L, this.codec.tsMillis(body));
        assertEquals(0, body.position(), "the body is left for the answer's own read");
    }

    @Test
    void aCancelAnswersTsMsIsRead() {
        assertEquals(
                1794671966452L,
                this.codec.tsMillis(body("{\"order_id\":\"o-1\",\"reduced_by\":\"1.00\",\"ts_ms\":1794671966452}")));
    }

    @Test
    void anAnswerWithoutOneHasNone() {
        assertEquals(
                -1, this.codec.tsMillis(body("{\"error\":{\"code\":\"authentication_error\",\"message\":\"no\"}}")));
        assertEquals(-1, this.codec.tsMillis(body("")));
        assertEquals(-1, this.codec.tsMillis(null));
    }

    private static ByteBuffer body(final String json) {
        return ByteBuffer.wrap(json.getBytes(StandardCharsets.US_ASCII));
    }
}
