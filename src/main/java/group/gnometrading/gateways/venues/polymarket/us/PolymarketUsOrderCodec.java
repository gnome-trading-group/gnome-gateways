package group.gnometrading.gateways.venues.polymarket.us;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.orders.VenueRejectText;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.strings.MutableString;
import group.gnometrading.utils.ByteBufferUtils;
import group.gnometrading.utils.FixedPointAscii;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Polymarket US's order entry wire format, without I/O: request bodies and paths written into the caller's buffers,
 * and answers read back. Market-specific facts come in as a {@link PolymarketUsMarket}, so one codec serves every
 * market on an account. Prices are taken in the venue's (long) terms; {@link PolymarketUsListing#convertPrice} turns a
 * listing's into them. Not thread-safe: one per sending thread. Nothing here allocates.
 *
 * <p>The venue has no client order id. A resting order goes out good till a date that is its stamp (see
 * {@link PolymarketUsStamps}), which every event for it echoes; an IOC can't carry one, so its correlation id
 * ({@code ioc-<slot>-<counter>}) is never sent and its events are matched by its terms.
 */
public final class PolymarketUsOrderCodec {

    /** Room a path built by {@link #orderPath} needs. */
    public static final int MAX_PATH_LENGTH = PolymarketUsRequests.ORDER_PATH.length()
            + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH
            + Math.max(PolymarketUsRequests.CANCEL_SUFFIX.length(), PolymarketUsRequests.MODIFY_SUFFIX.length());

    private static final byte[] IMMEDIATE_ID_PREFIX = "ioc-".getBytes(StandardCharsets.US_ASCII);

    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private static final byte[] FAILED_ENTRY_PREFIX = "order[".getBytes(StandardCharsets.US_ASCII);
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private final PolymarketUsOrderFields lookupFields = new PolymarketUsOrderFields();

    /** A resting order's correlation id: its stamp, as the venue writes it back. */
    public static void writeStampId(final int slot, final OrderContext ctx) {
        final long stamp = PolymarketUsStamps.stampMillis(slot, ctx.clientOidCounter);
        ctx.correlationIdLength = PolymarketUsStamps.write(stamp, ctx.correlationIdBytes, 0);
    }

    /** {@code ioc-{slot}-{counter}}: never sent, so only unique among the account's orders. */
    public static void writeImmediateId(final int slot, final OrderContext ctx) {
        final ByteBuffer id = ByteBuffer.wrap(ctx.correlationIdBytes);
        id.put(IMMEDIATE_ID_PREFIX);
        ByteBufferUtils.putLongAscii(id, slot);
        id.put((byte) '-');
        ByteBufferUtils.putLongAscii(id, ctx.clientOidCounter);
        ctx.correlationIdLength = id.position();
    }

    public static boolean isImmediate(final OrderContext ctx) {
        if (ctx.correlationIdLength < IMMEDIATE_ID_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < IMMEDIATE_ID_PREFIX.length; i++) {
            if (ctx.correlationIdBytes[i] != IMMEDIATE_ID_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes a new order's body into {@code out} from its start: an IOC (by its correlation id) or one good till its
     * stamp. Returns its length.
     */
    public int buildOrder(
            final PolymarketUsMarket market,
            final OrderContext ctx,
            final long venuePrice,
            final Side side,
            final boolean postOnly,
            final ByteBuffer out) {
        final boolean immediate = isImmediate(ctx);
        start(out);
        this.jsonEncoder.writeObjectEntry("marketSlug", market.marketSlug()).writeComma();
        this.jsonEncoder.writeObjectEntry("type", "ORDER_TYPE_LIMIT").writeComma();
        writePriceAndQuantity(venuePrice, ctx.originalQty, out);
        if (immediate) {
            this.jsonEncoder
                    .writeObjectEntry("tif", "TIME_IN_FORCE_IMMEDIATE_OR_CANCEL")
                    .writeComma();
        } else {
            this.jsonEncoder
                    .writeObjectEntry("tif", "TIME_IN_FORCE_GOOD_TILL_DATE")
                    .writeComma();
            writeGoodTillTime(ctx, out);
        }
        this.jsonEncoder
                .writeObjectEntry(
                        "intent",
                        PolymarketUsListing.intentName(market.listing().intent(side)))
                .writeComma();
        this.jsonEncoder
                .writeObjectEntry("manualOrderIndicator", "MANUAL_ORDER_INDICATOR_AUTOMATIC")
                .writeComma();
        this.jsonEncoder.writeObjectEntry("participateDontInitiate", postOnly).writeComma();
        // Waits for an IOC's outcome, so its answer is final. A resting order would wait up to ten seconds.
        this.jsonEncoder.writeObjectEntry("synchronousExecution", immediate);
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /**
     * Writes a modify's body into {@code out} from its start; returns its length. The stamp is sent again, so the
     * order keeps the id it's known by; post-only is sent again too, or a repriced order could cross.
     */
    public int buildModify(
            final PolymarketUsMarket market,
            final OrderContext ctx,
            final long venuePrice,
            final long size,
            final boolean postOnly,
            final ByteBuffer out) {
        start(out);
        this.jsonEncoder.writeObjectEntry("marketSlug", market.marketSlug()).writeComma();
        writePriceAndQuantity(venuePrice, size, out);
        this.jsonEncoder.writeObjectEntry("tif", "TIME_IN_FORCE_GOOD_TILL_DATE").writeComma();
        writeGoodTillTime(ctx, out);
        this.jsonEncoder.writeObjectEntry("participateDontInitiate", postOnly);
        this.jsonEncoder.writeObjectEnd();
        return out.position();
    }

    /** {@code /v1/order/{venue id}{suffix}}, into {@code path}. */
    public static void orderPath(final OrderContext ctx, final String suffix, final MutableString path) {
        path.reset();
        for (int i = 0; i < PolymarketUsRequests.ORDER_PATH.length(); i++) {
            path.append((byte) PolymarketUsRequests.ORDER_PATH.charAt(i));
        }
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            path.append(ctx.exchangeOrderIdBytes[i]);
        }
        for (int i = 0; i < suffix.length(); i++) {
            path.append((byte) suffix.charAt(i));
        }
    }

    /**
     * Reads a submit's answer, whose status is {@code status}. A refusal's reason is left in {@code ctx.rejectReason},
     * and an accepted order's venue id in the context. An order the exchange refuses still gets an id, and its
     * rejection comes on the order stream.
     */
    public SubmitResult readSubmitAnswer(final HTTPResponse response, final int status, final OrderContext ctx) {
        if (!response.isSuccess()) {
            final SubmitResult result = SubmitPolicy.classifyFailure(status);
            if (result == SubmitResult.REJECTED) {
                ctx.rejectReason = VenueRejectText.POLYMARKET_US.classify(response.getBody());
            }
            return result;
        }
        return readOrderId(response, ctx) ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
    }

    /** Takes the venue's id from {@code {"id":"...","executions":[...]}}. */
    private boolean readOrderId(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        ctx.exchangeOrderIdLength = 0;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("id") && !entry.isNull()) {
                        final var id = entry.asString();
                        ctx.exchangeOrderIdLength = Math.min(id.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
                        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
                            ctx.exchangeOrderIdBytes[i] = id.byteAt(i);
                        }
                    }
                }
            }
        }
        return ctx.exchangeOrderIdLength > 0;
    }

    /**
     * Takes each entry's venue id from a batch create's answer, {@code {"createdOrderIds":[...]}}, which lists them in
     * request order; an entry the list doesn't reach is left without one.
     */
    public void readCreatedOrderIds(final ByteBuffer body, final OrderContext[] ctxs, final int count) {
        for (int i = 0; i < count; i++) {
            ctxs[i].exchangeOrderIdLength = 0;
        }
        if (body == null || !body.hasRemaining()) {
            return;
        }
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("createdOrderIds") && !entry.isNull()) {
                        readIds(entry, ctxs, count);
                    }
                }
            }
        }
    }

    private static void readIds(final JsonDecoder.JsonNode ids, final OrderContext[] ctxs, final int count) {
        int index = 0;
        try (var list = ids.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem()) {
                    if (index < count && !item.isNull()) {
                        final var id = item.asString();
                        final OrderContext ctx = ctxs[index];
                        ctx.exchangeOrderIdLength = Math.min(id.length(), OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
                        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
                            ctx.exchangeOrderIdBytes[i] = id.byteAt(i);
                        }
                    }
                    index++;
                }
            }
        }
    }

    /**
     * The entry a refused batch names, as in {@code {"code":3,"message":"order[1]: Market not found: ..."}}; -1 if it
     * names none.
     */
    public static int failedEntry(final ByteBuffer body) {
        if (body == null) {
            return -1;
        }
        final int end = body.limit();
        for (int i = body.position(); i + FAILED_ENTRY_PREFIX.length < end; i++) {
            if (matchesAt(body, i, FAILED_ENTRY_PREFIX)) {
                return indexAt(body, i + FAILED_ENTRY_PREFIX.length, end);
            }
        }
        return -1;
    }

    /** The number at {@code at} closed by {@code ]}; -1 if there is none. */
    private static int indexAt(final ByteBuffer body, final int start, final int end) {
        int index = 0;
        int at = start;
        while (at < end && at - start < 4 && body.get(at) >= '0' && body.get(at) <= '9') {
            index = index * 10 + (body.get(at) - '0');
            at++;
        }
        return at > start && at < end && body.get(at) == ']' ? index : -1;
    }

    private static boolean matchesAt(final ByteBuffer body, final int at, final byte[] text) {
        for (int j = 0; j < text.length; j++) {
            if (body.get(at + j) != text[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Looks for the order with {@code stamp} in an open-orders answer ({@code {"orders":[...]}}), taking its venue id
     * into the context if found.
     */
    public boolean findOpenOrder(final ByteBuffer body, final long stamp, final OrderContext ctx) {
        boolean found = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("orders") && !entry.isNull()) {
                        found = scanOrders(entry, stamp, ctx);
                    }
                }
            }
        }
        return found;
    }

    private boolean scanOrders(final JsonDecoder.JsonNode orders, final long stamp, final OrderContext ctx) {
        boolean found = false;
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    this.lookupFields.reset();
                    this.lookupFields.readOrder(order);
                    if (!found && this.lookupFields.goodTillMillis == stamp) {
                        ctx.exchangeOrderIdLength = this.lookupFields.orderIdLength;
                        System.arraycopy(
                                this.lookupFields.orderId, 0, ctx.exchangeOrderIdBytes, 0, ctx.exchangeOrderIdLength);
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    private void start(final ByteBuffer out) {
        out.clear();
        this.jsonEncoder.wrap(out);
        this.jsonEncoder.writeObjectStart();
    }

    private void writePriceAndQuantity(final long venuePrice, final long size, final ByteBuffer out) {
        this.jsonEncoder.writeString("price").writeColon().writeObjectStart();
        this.jsonEncoder.writeString("value").writeColon();
        out.put((byte) '"');
        FixedPointAscii.put(out, venuePrice, Statics.PRICE_SCALING_FACTOR);
        out.put((byte) '"');
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("currency", "USD");
        this.jsonEncoder.writeObjectEnd().writeComma();
        this.jsonEncoder.writeString("quantity").writeColon();
        FixedPointAscii.put(out, size, Statics.SIZE_SCALING_FACTOR);
        this.jsonEncoder.writeComma();
    }

    private void writeGoodTillTime(final OrderContext ctx, final ByteBuffer out) {
        this.jsonEncoder.writeString("goodTillTime").writeColon();
        out.put((byte) '"');
        out.put(ctx.correlationIdBytes, 0, ctx.correlationIdLength);
        out.put((byte) '"');
        this.jsonEncoder.writeComma();
    }
}
