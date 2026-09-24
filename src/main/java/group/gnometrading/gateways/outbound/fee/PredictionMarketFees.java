package group.gnometrading.gateways.outbound.fee;

import group.gnometrading.schemas.Statics;

public final class PredictionMarketFees {

    private PredictionMarketFees() {}

    public static long calculateScaledFee(final long price, final long quantity, final double feeRate) {
        if (feeRate <= 0.0) {
            return 0;
        }
        double priceDecimal = (double) price / Statics.PRICE_SCALING_FACTOR;
        double qtyDecimal = (double) quantity / Statics.SIZE_SCALING_FACTOR;
        return (long) (qtyDecimal * feeRate * priceDecimal * (1.0 - priceDecimal) * Statics.PRICE_SCALING_FACTOR);
    }
}
