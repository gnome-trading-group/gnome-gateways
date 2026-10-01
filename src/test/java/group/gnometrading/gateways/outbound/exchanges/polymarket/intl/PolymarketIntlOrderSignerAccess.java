package group.gnometrading.gateways.outbound.exchanges.polymarket.intl;

/** Exposes the fixed-salt constructor to tests in other packages, so signatures are reproducible. */
public final class PolymarketIntlOrderSignerAccess {

    private PolymarketIntlOrderSignerAccess() {}

    public static PolymarketIntlOrderSigner withSalt(
            final byte[] privateKey,
            final String signerAddress,
            final String makerAddress,
            final int signatureType,
            final long salt) {
        return new PolymarketIntlOrderSigner(privateKey, signerAddress, makerAddress, signatureType, salt);
    }
}
