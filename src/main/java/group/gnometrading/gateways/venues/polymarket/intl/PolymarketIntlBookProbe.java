package group.gnometrading.gateways.venues.polymarket.intl;

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

/** Polymarket's public {@code GET /book?token_id=…}, every level of one outcome token; best prices come last. */
public final class PolymarketIntlBookProbe implements BookProbeClient {

    private final HTTPClient http;
    private final String host;
    private final JsonDecoder decoder = new JsonDecoder();

    public PolymarketIntlBookProbe(final HTTPClient http, final String host) {
        this.http = http;
        this.host = host;
    }

    @Override
    public Request prepare(final Subject subject) {
        return new Request("/book?token_id=" + subject.key(), null);
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
                        JsonLevels.objects(key, into, Side.Bid, "price", "size");
                    } else if (key.getName().equals("asks")) {
                        JsonLevels.objects(key, into, Side.Ask, "price", "size");
                    }
                }
            }
        }
        return true;
    }
}
