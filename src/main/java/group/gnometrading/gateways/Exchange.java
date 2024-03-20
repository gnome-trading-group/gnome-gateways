package group.gnometrading.gateways;

import group.gnometrading.objects.MarketUpdateEncoder;

public abstract class Exchange {
    /**
     * An exchange needs to output a MarketUpdateEncoder into a buffer.
     * This buffer corresponds to the Aeron IPC Queue.
     *
     * I create an exhcnage and connect via websockets.
     */
    private MarketUpdateEncoder marketUpdateEncoder;

    private void writeMarketUpdate() {

    }

    /**
     * Connect to the exchange blocking.
     * @return true if connected successfully
     */
    protected abstract boolean connectBlocking();
}
