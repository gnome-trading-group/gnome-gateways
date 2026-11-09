package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.utils.AsciiParsing;
import group.gnometrading.utils.ByteBufferUtils;
import group.gnometrading.utils.ScaledMath;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Polymarket International's order entry wire format, without I/O: orders signed and written into the caller's
 * buffer, cancels, and the submit's answer read back. Market-specific facts come in as a {@link PolymarketIntlMarket},
 * so one codec serves every market on an account (one signer, one API key). Not thread-safe: one per sending thread.
 * Nothing here allocates.
 */
public final class PolymarketIntlOrderCodec {

    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_NOT_FOUND = 404;
    // Polymarket's 503 is trading disabled, cancel-only or post-only mode: a refusal, not a lost answer.
    public static final int HTTP_SERVICE_UNAVAILABLE = 503;

    // The CLOB's answer to an order whose hash it already has (seen live: "order 0x... is invalid. Duplicated.").
    private static final byte[] DUPLICATED = "Duplicated.".getBytes(StandardCharsets.US_ASCII);
    private static final String ZERO_BYTES32 = "0x0000000000000000000000000000000000000000000000000000000000000000";
    private static final int BUY_SIDE = 0;
    private static final int SELL_SIDE = 1;
    // Polymarket sizes are in 6-decimal units, matching SIZE_SCALING_FACTOR, and in whole hundredths of a share.
    private static final long SHARE_INCREMENT = Statics.SIZE_SCALING_FACTOR / 100;
    // Dollars are in 6-decimal units too.
    private static final long CENT = Statics.SIZE_SCALING_FACTOR / 100;

    private final PolymarketIntlOrderSigner signer;
    private final String owner;
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private final SubmitAnswer submitAnswer = new SubmitAnswer();

    /** @param owner the API key every order is placed under */
    public PolymarketIntlOrderCodec(final PolymarketIntlOrderSigner signer, final String owner) {
        this.signer = signer;
        this.owner = owner;
    }

    /**
     * Signs a new order and writes its body into {@code out} from its start. Sets the context's correlation id and
     * venue id (both the order hash, known before sending) and whether it ends on the venue's word.
     *
     * @return the body's length
     */
    public int buildOrder(
            final PolymarketIntlMarket market,
            final long price,
            final long size,
            final Side side,
            final TimeInForce tif,
            final boolean postOnly,
            final long nowMillis,
            final OrderContext ctx,
            final ByteBuffer out) {
        final int pmSide = side == Side.Bid ? BUY_SIDE : SELL_SIDE;
        // Exact: the OMS keeps size to whole lots (hundredths of a share), and every tick is a multiple of 1e-4.
        final long notional = size / SHARE_INCREMENT * price / (Statics.PRICE_SCALING_FACTOR / SHARE_INCREMENT);
        final boolean immediate = isImmediate(tif);
        final long makerAmount;
        final long takerAmount;
        if (pmSide == BUY_SIDE && immediate) {
            // The venue reads an IOC or FOK BUY as dollars to spend: whole cents of them, and the shares those buy
            // at the limit to the tick's precision. At a better price it spends them all, buying more shares.
            makerAmount = notional - notional % CENT;
            final long shares = ScaledMath.multiplyDivide(makerAmount, Statics.PRICE_SCALING_FACTOR, price);
            takerAmount = shares - shares % market.amountIncrement();
        } else {
            makerAmount = pmSide == BUY_SIDE ? notional : size;
            takerAmount = pmSide == BUY_SIDE ? size : notional;
        }
        ctx.awaitsFinalFill = immediate;

        final PolymarketIntlOrderSigner.SignedOrder signed = this.signer.signOrder(
                market.tokenIdBytes(), makerAmount, takerAmount, pmSide, nowMillis, market.negRisk());

        ctx.submittedAtMillis = nowMillis;
        // The order hash is the venue's orderID, so it is both the correlation id and the cancel handle.
        ctx.correlationIdLength = signed.writeOrderHashHex(ctx.correlationIdBytes, 0);
        ctx.exchangeOrderIdLength = signed.writeOrderHashHex(ctx.exchangeOrderIdBytes, 0);

        out.clear();
        this.jsonEncoder.wrap(out);
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("order").writeColon().writeObjectStart();
        this.jsonEncoder.writeObjectEntry("salt", signed.salt()).writeComma();
        this.jsonEncoder.writeObjectEntry("maker", signed.maker()).writeComma();
        this.jsonEncoder.writeObjectEntry("signer", signed.signer()).writeComma();
        this.jsonEncoder.writeObjectEntry("tokenId", market.tokenId()).writeComma();
        writeQuotedLong("makerAmount", signed.makerAmount(), out);
        writeQuotedLong("takerAmount", signed.takerAmount(), out);
        this.jsonEncoder
                .writeObjectEntry("side", signed.side() == BUY_SIDE ? "BUY" : "SELL")
                .writeComma();
        // Kept in the body for GTD handling but no longer part of the signed order.
        this.jsonEncoder.writeObjectEntry("expiration", "0").writeComma();
        this.jsonEncoder
                .writeObjectEntry("signatureType", signed.signatureType())
                .writeComma();
        writeQuotedLong("timestamp", signed.timestampMillis(), out);
        this.jsonEncoder.writeObjectEntry("metadata", ZERO_BYTES32).writeComma();
        this.jsonEncoder.writeObjectEntry("builder", ZERO_BYTES32).writeComma();
        this.jsonEncoder.writeString("signature").writeColon();
        out.put((byte) '"');
        signed.writeSignatureHex(out);
        out.put((byte) '"');
        this.jsonEncoder.writeObjectEnd().writeComma();
        this.jsonEncoder.writeObjectEntry("owner", this.owner).writeComma();
        this.jsonEncoder.writeObjectEntry("orderType", orderType(tif)).writeComma();
        this.jsonEncoder.writeObjectEntry("deferExec", false).writeComma();
        this.jsonEncoder.writeObjectEntry("postOnly", postOnly);
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /** Writes a cancel's body ({@code {"orderID":"0x..."}}) into {@code out} from its start; returns its length. */
    public int buildCancel(final OrderContext ctx, final ByteBuffer out) {
        out.clear();
        this.jsonEncoder.wrap(out);
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeString("orderID").writeColon();
        out.put((byte) '"');
        out.put(ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
        out.put((byte) '"');
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /** Whether a cancel's answer lists the order as cancelled. */
    public boolean readCanceled(final HTTPResponse response, final OrderContext ctx) {
        return PolymarketIntlRequests.listsAsCanceled(
                this.jsonDecoder, response, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
    }

    /**
     * Reads a submit's answer, whose status is {@code status}. A refusal's reason is left in {@code ctx.rejectReason}. The venue's orderID must be the
     * hash signed: events are matched on that hash, so a different one would leave a live order nothing tracks.
     */
    public SubmitResult readSubmitAnswer(final HTTPResponse response, final int status, final OrderContext ctx) {
        if (!response.isSuccess()) {
            return readRefusal(response, status, ctx);
        }
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return SubmitResult.UNKNOWN;
        }
        final SubmitAnswer answer = this.submitAnswer;
        answer.reset();
        // Read in place and rewound after, so a refusal can still be logged; a duplicate would allocate.
        final int start = body.position();
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    answer.read(entry, ctx);
                }
            }
        }
        body.position(start);
        if (!answer.sawSuccess) {
            return SubmitResult.UNKNOWN;
        }
        if (!answer.success) {
            ctx.rejectReason = VenueRejectText.POLYMARKET_INTL.classify(body);
            return SubmitResult.REJECTED;
        }
        if (answer.sawId && !answer.idMatches) {
            throw new IllegalStateException("Polymarket assigned an orderID that differs from the signed order's hash; "
                    + "the order signer no longer matches the venue");
        }
        return SubmitResult.ACCEPTED;
    }

    private static SubmitResult readRefusal(final HTTPResponse response, final int status, final OrderContext ctx) {
        if (status == HTTP_BAD_REQUEST && saysDuplicate(response.getBody())) {
            return SubmitResult.DUPLICATE;
        }
        final SubmitResult result =
                status == HTTP_SERVICE_UNAVAILABLE ? SubmitResult.REJECTED : SubmitPolicy.classifyFailure(status);
        if (result == SubmitResult.REJECTED) {
            ctx.rejectReason = VenueRejectText.POLYMARKET_INTL.classify(response.getBody());
        }
        return result;
    }

    private static boolean saysDuplicate(final ByteBuffer body) {
        return body != null
                && body.hasRemaining()
                && AsciiParsing.indexOf(body, body.position(), body.limit(), DUPLICATED) >= 0;
    }

    /**
     * After an accepted IOC or FOK, all it will ever fill, in shares (a BUY takes them, a SELL gives them), from the
     * submit's answer; {@link OrderContext#QTY_ABSENT} for a delayed order whose answer doesn't say.
     */
    public long finalFillQty(final OrderContext ctx) {
        return ctx.side == Side.Bid ? this.submitAnswer.takingAmount : this.submitAnswer.makingAmount;
    }

    private void writeQuotedLong(final String key, final long value, final ByteBuffer out) {
        this.jsonEncoder.writeString(key).writeColon();
        out.put((byte) '"');
        ByteBufferUtils.putLongAscii(out, value);
        out.put((byte) '"');
        this.jsonEncoder.writeComma();
    }

    private static boolean isImmediate(final TimeInForce tif) {
        return tif == TimeInForce.IMMEDIATE_OR_CANCELED || tif == TimeInForce.FILL_OR_KILL;
    }

    private static String orderType(final TimeInForce tif) {
        if (tif == TimeInForce.FILL_OR_KILL) {
            return "FOK";
        }
        if (tif == TimeInForce.IMMEDIATE_OR_CANCELED) {
            return "FAK";
        }
        return "GTC";
    }

    /** The fields of a submit's answer that settle it; reused for every submit. */
    private static final class SubmitAnswer {
        boolean sawSuccess;
        boolean success;
        boolean sawId;
        boolean idMatches;
        long makingAmount;
        long takingAmount;

        void reset() {
            this.sawSuccess = false;
            this.success = false;
            this.sawId = false;
            this.idMatches = false;
            this.makingAmount = OrderContext.QTY_ABSENT;
            this.takingAmount = OrderContext.QTY_ABSENT;
        }

        void read(final JsonDecoder.JsonNode entry, final OrderContext ctx) {
            // The decoder reads a null as whatever string comes after it.
            if (entry.isNull()) {
                return;
            }
            final GnomeString name = entry.getName();
            if (name.equals("success")) {
                this.sawSuccess = true;
                this.success = entry.asBoolean();
            } else if (name.equals("orderID")) {
                final GnomeString id = entry.asString();
                this.sawId = id.length() > 0;
                this.idMatches =
                        PolymarketIntlRequests.matches(id, ctx.exchangeOrderIdBytes, ctx.exchangeOrderIdLength);
            } else if (name.equals("makingAmount")) {
                this.makingAmount = amount(entry.asString());
            } else if (name.equals("takingAmount")) {
                this.takingAmount = amount(entry.asString());
            }
        }

        /** An amount in shares or dollars, both 6-decimal; QTY_ABSENT if the venue left it empty. */
        private static long amount(final GnomeString value) {
            return value.length() == 0 ? OrderContext.QTY_ABSENT : value.toFixedPointLong(Statics.SIZE_SCALING_FACTOR);
        }
    }
}
