package group.gnometrading.gateways.outbound;

import group.gnometrading.schemas.RejectReason;
import group.gnometrading.utils.AsciiParsing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Why a venue refused an order, read from the text of its answer without allocating. Each venue has its own table of
 * phrases, matched ignoring case so a venue recasing a message still matches; only phrases seen in a venue's real
 * answers belong here. The body's position and limit are left as they were, so it can still be logged.
 */
public final class VenueRejectText {

    private static final byte[] KALSHI_INSUFFICIENT_BALANCE = lowercase("insufficient_balance");
    // Kalshi answers a crossing post-only order as {"code":"invalid_order",...,"details":"post only cross"}.
    private static final byte[] KALSHI_POST_ONLY_CROSS = lowercase("post only cross");

    private static final byte[] POLYMARKET_NOT_ENOUGH_BALANCE = lowercase("not enough balance");
    private static final byte[] POLYMARKET_CROSSES_BOOK = lowercase("crosses book");
    private static final byte[] POLYMARKET_CANCEL_ONLY = lowercase("cancel-only");

    public static final VenueRejectText KALSHI = new VenueRejectText(
            new byte[][] {KALSHI_INSUFFICIENT_BALANCE, KALSHI_POST_ONLY_CROSS},
            new RejectReason[] {RejectReason.INSUFFICIENT_FUNDS, RejectReason.POST_ONLY_WOULD_CROSS});

    public static final VenueRejectText POLYMARKET_INTL = new VenueRejectText(
            new byte[][] {POLYMARKET_NOT_ENOUGH_BALANCE, POLYMARKET_CROSSES_BOOK, POLYMARKET_CANCEL_ONLY},
            new RejectReason[] {
                RejectReason.INSUFFICIENT_FUNDS, RejectReason.POST_ONLY_WOULD_CROSS, RejectReason.MARKET_CLOSED
            });

    // No Polymarket US refusal has been captured yet, so every one is reported as the venue's plain refusal.
    public static final VenueRejectText POLYMARKET_US = new VenueRejectText(new byte[0][], new RejectReason[0]);

    private final byte[][] patterns;
    private final RejectReason[] reasons;

    private VenueRejectText(final byte[][] patterns, final RejectReason[] reasons) {
        if (patterns.length != reasons.length) {
            throw new IllegalArgumentException("Each reject phrase needs exactly one reason");
        }
        this.patterns = patterns;
        this.reasons = reasons;
    }

    /** The reason for the first phrase found between the body's position and limit; null if none is, or no body. */
    public RejectReason classify(final ByteBuffer body) {
        if (body == null) {
            return null;
        }
        return classify(body, body.position(), body.limit());
    }

    /** The reason for the first phrase found in {@code body} between {@code start} and {@code end}; null if none is. */
    public RejectReason classify(final ByteBuffer body, final int start, final int end) {
        for (int i = 0; i < this.patterns.length; i++) {
            if (AsciiParsing.indexOfIgnoreCase(body, start, end, this.patterns[i]) >= 0) {
                return this.reasons[i];
            }
        }
        return null;
    }

    private static byte[] lowercase(final String phrase) {
        return phrase.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    }
}
