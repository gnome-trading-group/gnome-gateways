package group.gnometrading.gateways.venues.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.probe.BookProbeClient;
import group.gnometrading.gateways.feed.probe.JsonLevels;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Side;
import java.io.IOException;

/**
 * Kalshi's public order book, {@code GET /trade-api/v2/markets/{ticker}/orderbook}: YES bids and NO bids, each in its
 * own leg's price. The websocket book is in YES-leg prices, so a NO bid at p is a YES ask at 1 - p. Kalshi's REST book
 * carries no sequence, so it can't be lined up with the websocket exactly; the prober's second fetch absorbs a change
 * that lands between the two reads.
 */
public final class KalshiBookProbe implements BookProbeClient {

    private static final int DEPTH = 10;

    private final HTTPClient http;
    private final String host;
    private final JsonDecoder decoder = new JsonDecoder();

    public KalshiBookProbe(final HTTPClient http, final String host) {
        this.http = http;
        this.host = host;
    }

    @Override
    public Request prepare(final Subject subject) {
        return new Request("/trade-api/v2/markets/" + subject.key() + "/orderbook?depth=" + DEPTH, null);
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
                    if (key.getName().equals("orderbook_fp")) {
                        book(key, into);
                    }
                }
            }
        }
        return true;
    }

    private static void book(final JsonDecoder.JsonNode node, final Book into) {
        try (var book = node.asObject()) {
            while (book.hasNextKey()) {
                try (var key = book.nextKey()) {
                    if (key.getName().equals("yes_dollars")) {
                        pairs(key, into, false);
                    } else if (key.getName().equals("no_dollars")) {
                        pairs(key, into, true);
                    }
                }
            }
        }
    }

    private static void pairs(final JsonDecoder.JsonNode node, final Book into, final boolean noLeg) {
        if (node.isNull()) {
            return;
        }
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var pair = array.nextItem()) {
                    pair(pair, into, noLeg);
                }
            }
        }
    }

    private static void pair(final JsonDecoder.JsonNode node, final Book into, final boolean noLeg) {
        long price = JsonLevels.UNREADABLE;
        long size = JsonLevels.UNREADABLE;
        try (var pair = node.asArray()) {
            if (pair.hasNextItem()) {
                try (var item = pair.nextItem()) {
                    price = item.asString().toFixedPointLong(Statics.PRICE_SCALING_FACTOR, JsonLevels.UNREADABLE);
                }
            }
            if (pair.hasNextItem()) {
                try (var item = pair.nextItem()) {
                    size = item.asString().toFixedPointLong(Statics.SIZE_SCALING_FACTOR, JsonLevels.UNREADABLE);
                }
            }
        }
        if (price == JsonLevels.UNREADABLE || size == JsonLevels.UNREADABLE) {
            return;
        }
        if (noLeg) {
            into.add(Side.Ask, Statics.PRICE_SCALING_FACTOR - price, size, 1);
        } else {
            into.add(Side.Bid, price, size, 1);
        }
    }
}
