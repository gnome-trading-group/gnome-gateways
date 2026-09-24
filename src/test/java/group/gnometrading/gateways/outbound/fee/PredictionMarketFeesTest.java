package group.gnometrading.gateways.outbound.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Statics;
import org.junit.jupiter.api.Test;

class PredictionMarketFeesTest {

    @Test
    void calculateScaledFee_typicalPolymarketTrade() {
        // 10 contracts @ $0.50, rate = 0.02 (2%): fee = 10 * 0.02 * 0.50 * 0.50 * PRICE_SCALE = 50_000_000
        long price = (long) (0.50 * Statics.PRICE_SCALING_FACTOR);
        long qty = (long) (10.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(50_000_000L, PredictionMarketFees.calculateScaledFee(price, qty, 0.02));
    }

    @Test
    void calculateScaledFee_zeroRateReturnsZero() {
        long price = (long) (0.50 * Statics.PRICE_SCALING_FACTOR);
        long qty = (long) (10.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(0L, PredictionMarketFees.calculateScaledFee(price, qty, 0.0));
    }

    @Test
    void calculateScaledFee_negativeRateReturnsZero() {
        long price = (long) (0.50 * Statics.PRICE_SCALING_FACTOR);
        long qty = (long) (10.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(0L, PredictionMarketFees.calculateScaledFee(price, qty, -0.01));
    }

    @Test
    void calculateScaledFee_priceAtOneReturnsZero() {
        // p=1.0, (1-p)=0.0 → fee = 0
        long qty = (long) (10.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(0L, PredictionMarketFees.calculateScaledFee(Statics.PRICE_SCALING_FACTOR, qty, 0.07));
    }

    @Test
    void calculateScaledFee_priceAtZeroReturnsZero() {
        // p=0.0 → fee = 0
        long qty = (long) (10.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(0L, PredictionMarketFees.calculateScaledFee(0L, qty, 0.07));
    }

    @Test
    void calculateScaledFee_kalshiStandardRate() {
        // 5 contracts @ $0.56, rate = 0.07 (7%)
        // fee = 5 * 0.07 * 0.56 * 0.44 * PRICE_SCALE = 86_240_000
        long price = (long) (0.56 * Statics.PRICE_SCALING_FACTOR);
        long quantity = (long) (5.0 * Statics.SIZE_SCALING_FACTOR);
        assertEquals(86_240_000L, PredictionMarketFees.calculateScaledFee(price, quantity, 0.07));
    }
}
