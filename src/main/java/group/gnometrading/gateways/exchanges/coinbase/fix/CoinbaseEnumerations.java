package group.gnometrading.gateways.exchanges.coinbase.fix;

public class CoinbaseEnumerations {

    public static final class DefaultSelfTradePreventionStrategy {
        public static final char CancelAggressingOrders = 'N';
        public static final char CancelBothOrders = 'Q';
    }

    public static final class CancelOrdersOnDisconnect {
        public static final char CancelAllSessionOrders = 'S';
        public static final char CancelAllProfileOrders = 'Y';
    }

    public static final class DropCopyFlag {
        public static final char NormalOrderEntry = 'N';
        public static final char OnlyFills = 'Y';
    }

}
