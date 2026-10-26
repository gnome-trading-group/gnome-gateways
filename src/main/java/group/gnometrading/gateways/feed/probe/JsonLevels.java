package group.gnometrading.gateways.feed.probe;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Side;

/** Reading REST books' level arrays into a {@link Book}, for the venues' probe clients. */
public final class JsonLevels {

    public static final long UNREADABLE = Long.MIN_VALUE;

    private JsonLevels() {}

    /** [{priceKey: "0.5", sizeKey: "10"}, …]; sizes on one price add up (Lighter lists orders, not levels). */
    public static void objects(
            final JsonDecoder.JsonNode node,
            final Book into,
            final Side side,
            final String priceKey,
            final String sizeKey) {
        try (var array = node.asArray()) {
            while (array.hasNextItem()) {
                try (var item = array.nextItem()) {
                    object(item, into, side, priceKey, sizeKey);
                }
            }
        }
    }

    private static void object(
            final JsonDecoder.JsonNode item,
            final Book into,
            final Side side,
            final String priceKey,
            final String sizeKey) {
        long price = UNREADABLE;
        long size = UNREADABLE;
        try (var level = item.asObject()) {
            while (level.hasNextKey()) {
                try (var key = level.nextKey()) {
                    if (key.getName().equals(priceKey)) {
                        price = amount(key, Statics.PRICE_SCALING_FACTOR);
                    } else if (key.getName().equals(sizeKey)) {
                        size = amount(key, Statics.SIZE_SCALING_FACTOR);
                    }
                }
            }
        }
        if (price != UNREADABLE && size != UNREADABLE && size > 0) {
            into.add(side, price, size, 1);
        }
    }

    /** A decimal string, or an {@code Amount} object {@code {"value": "…"}}. */
    public static long amount(final JsonDecoder.JsonNode node, final long scale) {
        if (!node.isObject()) {
            return node.asString().toFixedPointLong(scale, UNREADABLE);
        }
        long value = UNREADABLE;
        try (var amount = node.asObject()) {
            while (amount.hasNextKey()) {
                try (var key = amount.nextKey()) {
                    if (key.getName().equals("value")) {
                        value = key.asString().toFixedPointLong(scale, UNREADABLE);
                    }
                }
            }
        }
        return value;
    }
}
