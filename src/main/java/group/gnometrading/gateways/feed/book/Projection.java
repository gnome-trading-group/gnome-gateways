package group.gnometrading.gateways.feed.book;

import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Side;

/**
 * How a stream sees its market's book. A binary market quoted in one outcome's terms (a Kalshi ticker, a Polymarket US
 * slug) is two listings: one sees the book as it is, the other in the other outcome's terms, where a bid at p is an
 * ask at 1 - p and a buyer is a seller.
 */
public enum Projection {
    IDENTITY,
    COMPLEMENT;

    public long price(final long price, final long nullValue) {
        return this == IDENTITY || price == nullValue ? price : Statics.PRICE_SCALING_FACTOR - price;
    }

    public Side side(final Side side) {
        if (this == IDENTITY) {
            return side;
        }
        if (side == Side.Bid) {
            return Side.Ask;
        }
        return side == Side.Ask ? Side.Bid : side;
    }

    /** The book side this projection reads for its own {@code side}. */
    public PriceLevels source(final Book book, final Side side) {
        return book.side(side(side));
    }
}
