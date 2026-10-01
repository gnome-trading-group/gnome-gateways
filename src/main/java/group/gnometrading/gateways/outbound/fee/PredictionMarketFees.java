package group.gnometrading.gateways.outbound.fee;

import group.gnometrading.schemas.Statics;

public final class PredictionMarketFees {

    private PredictionMarketFees() {}

    /** {@code quantity × feeRate × (p(1−p))^exponent}, scaled by {@link Statics#PRICE_SCALING_FACTOR}. */
    public static long calculateScaledFee(
            final long price, final long quantity, final double feeRate, final double exponent) {
        if (feeRate <= 0.0) {
            return 0;
        }
        final double priceDecimal = (double) price / Statics.PRICE_SCALING_FACTOR;
        final double qtyDecimal = (double) quantity / Statics.SIZE_SCALING_FACTOR;
        final double curve = priceDecimal * (1.0 - priceDecimal);
        final double shaped = exponent == 1.0 ? curve : Math.pow(curve, exponent);
        return (long) (qtyDecimal * feeRate * shaped * Statics.PRICE_SCALING_FACTOR);
    }
}
