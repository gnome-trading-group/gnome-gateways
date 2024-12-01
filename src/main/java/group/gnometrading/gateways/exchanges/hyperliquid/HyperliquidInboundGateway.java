package group.gnometrading.gateways.exchanges.hyperliquid;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.gateways.WebSocketMarketInboundGateway;
import group.gnometrading.gateways.codecs.Decoder;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;

import java.io.IOException;
import java.util.List;

public class HyperliquidInboundGateway extends WebSocketMarketInboundGateway<JSONDecoder> {

    public HyperliquidInboundGateway(WebSocketClient socketClient, Publication publication, Decoder<JSONDecoder> decoder, JSONDecoder messageHolder, List<Listing> listings) {
        super(socketClient, publication, decoder, messageHolder, listings);
    }

    @Override
    protected void handleGatewayMessage(JSONDecoder message) throws IOException {

    }

    @Override
    public void onSocketClose() {

    }
}
