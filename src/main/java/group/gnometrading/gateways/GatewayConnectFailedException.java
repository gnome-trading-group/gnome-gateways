package group.gnometrading.gateways;

public final class GatewayConnectFailedException extends RuntimeException {
    public GatewayConnectFailedException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
