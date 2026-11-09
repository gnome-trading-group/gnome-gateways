package group.gnometrading.gateways.venues;

import group.gnometrading.gateways.orders.OrderPathTestKit;
import group.gnometrading.gateways.venues.kalshi.KalshiOrderPath;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderPath;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsOrderPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Prints what each step of an order's path costs per venue, once warm: building and signing it, reading answers, and
 * applying its events. Not part of {@code mvn test}; run with {@code mvn test-compile surefire:test@benchmark
 * -Dtest=OrderPathBenchmark}, and compare runs on the same, otherwise idle, machine.
 */
@Tag("benchmark")
class OrderPathBenchmark {

    private static final int WARM_UP = 50_000;
    private static final int MEASURED = 100_000;
    // RSA-2048 costs about half a millisecond a signature.
    private static final int RSA_WARM_UP = 2_000;
    private static final int RSA_MEASURED = 5_000;

    @Test
    void report() {
        final KalshiOrderPath kalshi = new KalshiOrderPath();
        OrderPathTestKit.time("Kalshi order build + answer", WARM_UP, MEASURED, kalshi::build);
        OrderPathTestKit.time("Kalshi request signing (RSA-PSS 2048)", RSA_WARM_UP, RSA_MEASURED, kalshi::sign);
        OrderPathTestKit.time("Kalshi resting + fill events", WARM_UP, MEASURED, kalshi::events);
        OrderPathTestKit.time("Kalshi amend + cancel requests", WARM_UP, MEASURED, kalshi::amendAndCancel);
        OrderPathTestKit.time("Submit policy, unknown outcome", WARM_UP, MEASURED, kalshi::unknownOutcome);

        final PolymarketIntlOrderPath intl = new PolymarketIntlOrderPath();
        OrderPathTestKit.time("Polymarket Intl order sign + build + cancel", WARM_UP, MEASURED, intl::build);
        OrderPathTestKit.time("Polymarket Intl answers", WARM_UP, MEASURED, intl::answers);
        OrderPathTestKit.time("Polymarket Intl L2 signing (HMAC)", WARM_UP, MEASURED, intl::sign);
        OrderPathTestKit.time("Polymarket Intl placement + trade events", WARM_UP, MEASURED, intl::events);

        final PolymarketUsOrderPath us = new PolymarketUsOrderPath();
        OrderPathTestKit.time("Polymarket US order + modify build, answer", WARM_UP, MEASURED, us::build);
        OrderPathTestKit.time("Polymarket US request signing (Ed25519)", WARM_UP, MEASURED, us::sign);
        OrderPathTestKit.time("Polymarket US resting + fill executions", WARM_UP, MEASURED, us::events);
    }
}
