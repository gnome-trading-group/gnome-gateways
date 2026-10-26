package group.gnometrading.gateways.venues.lighter;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.probe.BookProbeClient;
import group.gnometrading.gateways.feed.probe.JsonLevels;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.book.Side;
import java.io.IOException;

/**
 * Lighter's {@code GET /api/v1/orderBookOrders?market_id=…}: resting orders, not levels, so sizes on one price are
 * added up. Unauthenticated callers get 60 requests a minute, shared with the websocket's budget, so the prober keeps
 * well under it.
 */
public final class LighterBookProbe implements BookProbeClient {

    private static final int ORDERS = 250;

    private final HTTPClient http;
    private final String host;
    private final JsonDecoder decoder = new JsonDecoder();

    public LighterBookProbe(final HTTPClient http, final String host) {
        this.http = http;
        this.host = host;
    }

    @Override
    public Request prepare(final Subject subject) {
        return new Request("/api/v1/orderBookOrders?market_id=" + subject.key() + "&limit=" + ORDERS, null);
    }

    @Override
    public boolean fetch(final Request request, final Book into) throws IOException {
        final HTTPResponse response = http.get(HTTPProtocol.HTTPS, host, request.path());
        if (!response.isSuccess() || response.getBody() == null) {
            return false;
        }
        try (var root = decoder.wrap(response.getBody());
                var object = root.asObject()) {
            while (object.hasNextKey()) {
                try (var key = object.nextKey()) {
                    if (key.getName().equals("bids")) {
                        JsonLevels.objects(key, into, Side.Bid, "price", "remaining_base_amount");
                    } else if (key.getName().equals("asks")) {
                        JsonLevels.objects(key, into, Side.Ask, "price", "remaining_base_amount");
                    }
                }
            }
        }
        return true;
    }
}
