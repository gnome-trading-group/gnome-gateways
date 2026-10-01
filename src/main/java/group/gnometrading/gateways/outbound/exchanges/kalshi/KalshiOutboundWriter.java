package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundSocketWriter;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundWriter extends OutboundSocketWriter {

    private static final String ORDER_PATH = "/trade-api/v2/portfolio/events/orders";
    private static final String ORDERS_LOOKUP_PATH = "/trade-api/v2/portfolio/orders";
    private static final long LOOKUP_WINDOW_SECONDS = 60L;
    private static final int LOOKUP_LIMIT = 200;
    private static final ViewString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    private static final String AMEND_SUFFIX = "/amend";
    private static final byte[] AMEND_SUFFIX_BYTES = AMEND_SUFFIX.getBytes(StandardCharsets.US_ASCII);

    private static final String HEADER_KEY = "KALSHI-ACCESS-KEY";
    private static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    private static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";

    private static final byte[] ORDER_ID_MARKER = "\"order_id\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FILL_COUNT_MARKER = "\"fill_count\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMAINING_COUNT_MARKER = "\"remaining_count\":\"".getBytes(StandardCharsets.UTF_8);

    // PRICE_SCALING_FACTOR / 10_000 — converts internal price to 4-decimal fractional part
    private static final long PRICE_SCALE_DIVISOR = Statics.PRICE_SCALING_FACTOR / 10_000L;
    // SIZE_SCALING_FACTOR / 100 — converts internal size to 2-decimal fractional part
    private static final long SIZE_SCALE_DIVISOR = Statics.SIZE_SCALING_FACTOR / 100L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final HTTPClient httpClient;
    private final String apiHost;
    private final KalshiAuthSigner authSigner;
    private final EpochNanoClock clock;
    private final String marketTicker;
    private final boolean isNoListing;

    private final byte[] jsonBodyBuf = new byte[1 << 11]; // 2 KiB
    private final ByteBuffer jsonBodyBuffer = ByteBuffer.wrap(jsonBodyBuf);
    private final JsonEncoder jsonEncoder = new JsonEncoder();
    private final JsonDecoder jsonDecoder = new JsonDecoder();
    private int jsonBodyLength;

    private final byte[] sessionPrefix;
    private long preparedAtMillis;
    private final byte[] lookupOrderId = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int lookupOrderIdLength;

    private final MutableString cancelPath = new MutableString(
            ORDER_PATH.length() + 1 + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH + AMEND_SUFFIX.length());

    public KalshiOutboundWriter(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochNanoClock clock,
            final Listing listing) {
        super(orderOutboundBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.clock = clock;
        this.jsonEncoder.wrap(this.jsonBodyBuffer);
        this.sessionPrefix = Long.toString(clock.nanoTime() / NANOS_PER_MILLI, Character.MAX_RADIX)
                .getBytes(StandardCharsets.US_ASCII);

        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIdx = exchangeSecurityId.indexOf(':');
        final String suffix = colonIdx >= 0 ? exchangeSecurityId.substring(colonIdx + 1) : "";
        this.marketTicker = colonIdx >= 0 ? exchangeSecurityId.substring(0, colonIdx) : exchangeSecurityId;
        this.isNoListing = "no".equalsIgnoreCase(suffix);
    }

    @Override
    public String roleName() {
        return "kalshi-outbound-writer";
    }

    @Override
    protected boolean prepareOrder(final OrderContext ctx) {
        long price = this.order.decoder.price();
        Side side = this.order.decoder.side();
        if (this.isNoListing) {
            side = (side == Side.Bid) ? Side.Ask : Side.Bid;
            price = Statics.PRICE_SCALING_FACTOR - price;
        }
        writeClientOrderId(ctx);
        this.preparedAtMillis = epochMillis();
        buildOrderJson(
                price,
                this.order.decoder.size(),
                side,
                this.order.decoder.orderType(),
                this.order.decoder.timeInForce(),
                this.order.decoder.flags().postOnly(),
                ctx);
        return true;
    }

    @Override
    protected SubmitResult submitOrder(final OrderContext ctx) throws Exception {
        this.authSigner.sign(epochMillis(), "POST", ORDER_PATH);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                ORDER_PATH_GS,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (!response.isSuccess()) {
            return classifyFailure(response.getStatusCode());
        }
        return parseOrderId(response, ctx) ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
    }

    /**
     * Looks for the order among this market's recent orders. Kalshi cannot filter by client order id,
     * so the listing is narrowed to the ticker and to orders created since shortly before the submit.
     */
    @Override
    protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
        final long minTimestampSeconds = this.preparedAtMillis / 1000L - LOOKUP_WINDOW_SECONDS;
        final String path = ORDERS_LOOKUP_PATH + "?ticker=" + this.marketTicker + "&min_ts=" + minTimestampSeconds
                + "&limit=" + LOOKUP_LIMIT;
        // Kalshi signs the path without its query string.
        this.authSigner.sign(epochMillis(), "GET", ORDERS_LOOKUP_PATH);

        final HTTPResponse response = this.httpClient.get(
                HTTPProtocol.HTTPS,
                this.apiHost,
                path,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (!response.isSuccess() || response.getBody() == null) {
            return SubmitResult.UNKNOWN;
        }
        return findInOrderList(response.getBody(), ctx) ? SubmitResult.ACCEPTED : SubmitResult.REJECTED;
    }

    /** Scans {@code {"orders":[...]}} for our client order id, taking the venue's order_id if found. */
    private boolean findInOrderList(final ByteBuffer body, final OrderContext ctx) {
        boolean found = false;
        try (var root = this.jsonDecoder.wrap(body);
                var obj = root.asObject()) {
            while (obj.hasNextKey()) {
                try (var entry = obj.nextKey()) {
                    if (entry.getName().equals("orders")) {
                        found = scanOrders(entry, ctx);
                    }
                }
            }
        }
        return found;
    }

    private boolean scanOrders(final JsonDecoder.JsonNode orders, final OrderContext ctx) {
        boolean found = false;
        try (var list = orders.asArray()) {
            while (list.hasNextItem()) {
                try (var item = list.nextItem();
                        var order = item.asObject()) {
                    found |= !found && readOrderIfOurs(order, ctx);
                }
            }
        }
        return found;
    }

    private boolean readOrderIfOurs(final JsonDecoder.JsonObject order, final OrderContext ctx) {
        boolean ours = false;
        this.lookupOrderIdLength = 0;
        while (order.hasNextKey()) {
            try (var field = order.nextKey()) {
                final GnomeString name = field.getName();
                if (name.equals("client_order_id")) {
                    ours = equalsCorrelationId(field.asString(), ctx);
                } else if (name.equals("order_id")) {
                    this.lookupOrderIdLength = copyBytes(field.asString(), this.lookupOrderId);
                }
            }
        }
        if (ours && this.lookupOrderIdLength > 0) {
            ctx.exchangeOrderIdLength = this.lookupOrderIdLength;
            System.arraycopy(this.lookupOrderId, 0, ctx.exchangeOrderIdBytes, 0, this.lookupOrderIdLength);
            return true;
        }
        return false;
    }

    /**
     * {@code {sessionPrefix}-{strategyId}-{counter}}. The OMS's counter restarts with the process, and
     * Kalshi deduplicates on this id, so the process's start time keeps ids from one run unique.
     */
    private void writeClientOrderId(final OrderContext ctx) {
        final ByteBuffer out = ByteBuffer.wrap(ctx.correlationIdBytes);
        out.put(this.sessionPrefix);
        out.put((byte) '-');
        ByteBufferUtils.putLongAscii(out, ctx.clientOidStrategyId);
        out.put((byte) '-');
        ByteBufferUtils.putLongAscii(out, ctx.clientOidCounter);
        ctx.correlationIdLength = out.position();
    }

    private static boolean equalsCorrelationId(final GnomeString value, final OrderContext ctx) {
        if (value.length() != ctx.correlationIdLength) {
            return false;
        }
        for (int i = 0; i < ctx.correlationIdLength; i++) {
            if (value.byteAt(i) != ctx.correlationIdBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static int copyBytes(final GnomeString value, final byte[] dest) {
        final int length = Math.min(value.length(), dest.length);
        for (int i = 0; i < length; i++) {
            dest[i] = value.byteAt(i);
        }
        return length;
    }

    @Override
    protected boolean cancelOrder(final OrderContext ctx) throws Exception {
        buildOrderPath(ctx);
        this.authSigner.sign(epochMillis(), "DELETE", this.cancelPath);

        final HTTPResponse response = this.httpClient.delete(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        return response.isSuccess();
    }

    @Override
    protected void handleModifyOrder() throws Exception {
        final long clientOidCounter = this.modifyOrder.getClientOidCounter();
        final OrderContext ctx = getActiveOrder(clientOidCounter);
        if (ctx == null) {
            return;
        }

        long price = this.modifyOrder.decoder.price();
        long size = this.modifyOrder.decoder.size();
        Side side = ctx.side;

        if (this.isNoListing) {
            side = (side == Side.Bid) ? Side.Ask : Side.Bid;
            price = Statics.PRICE_SCALING_FACTOR - price;
        }

        buildAmendPath(ctx);
        buildAmendJson(price, size, side);
        this.authSigner.sign(epochMillis(), "POST", this.cancelPath);

        final HTTPResponse response = this.httpClient.post(
                HTTPProtocol.HTTPS,
                this.apiHost,
                this.cancelPath,
                this.jsonBodyBuf,
                this.jsonBodyLength,
                HEADER_KEY,
                this.authSigner.apiKey(),
                HEADER_TIMESTAMP,
                this.authSigner.timestamp(),
                HEADER_SIGNATURE,
                this.authSigner.signature());

        if (response.isSuccess()) {
            // Kalshi acknowledges an amend only in this response body, and only the reader knows the
            // order's fills, so hand it the venue's numbers to build the acknowledgement from.
            ctx.originalQty = this.modifyOrder.decoder.size();
            final OrderContext notice = buildAmendAccepted(
                    ctx,
                    ctx.originalQty,
                    parseFixedPointField(response, FILL_COUNT_MARKER),
                    parseFixedPointField(response, REMAINING_COUNT_MARKER));
            enqueueWriterReport(notice);
            returnToPool(notice);
        } else {
            enqueueCancelReject(ctx);
        }
    }

    private void buildOrderJson(
            final long price,
            final long size,
            final Side side,
            final OrderType orderType,
            final TimeInForce tif,
            final boolean postOnly,
            final OrderContext ctx) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("ticker", this.marketTicker);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeString("client_order_id").writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonBodyBuffer.put(ctx.correlationIdBytes, 0, ctx.correlationIdLength);
        this.jsonBodyBuffer.put((byte) '"');
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", side == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePriceField(price);
        this.jsonEncoder.writeComma();
        writeSizeField(size);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("time_in_force", resolveTimeInForce(orderType, tif));
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("self_trade_prevention_type", "maker");
        if (postOnly) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("post_only", true);
        }
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void buildAmendJson(final long price, final long size, final Side side) {
        this.jsonBodyBuffer.clear();
        this.jsonEncoder.writeObjectStart();
        this.jsonEncoder.writeObjectEntry("ticker", this.marketTicker);
        this.jsonEncoder.writeComma();
        this.jsonEncoder.writeObjectEntry("side", side == Side.Bid ? "bid" : "ask");
        this.jsonEncoder.writeComma();
        writePriceField(price);
        this.jsonEncoder.writeComma();
        writeSizeField(size);
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void writePriceField(final long price) {
        this.jsonEncoder.writeString("price").writeColon();
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, price / Statics.PRICE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 4, (price % Statics.PRICE_SCALING_FACTOR) / PRICE_SCALE_DIVISOR);
    }

    private void writeSizeField(final long size) {
        this.jsonEncoder.writeString("count").writeColon();
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, size / Statics.SIZE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 2, (size % Statics.SIZE_SCALING_FACTOR) / SIZE_SCALE_DIVISOR);
    }

    private void buildOrderPath(final OrderContext ctx) {
        this.cancelPath.reset();
        this.cancelPath.appendString(ORDER_PATH_GS);
        this.cancelPath.append((byte) '/');
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            this.cancelPath.append(ctx.exchangeOrderIdBytes[i]);
        }
    }

    private void buildAmendPath(final OrderContext ctx) {
        buildOrderPath(ctx);
        for (byte b : AMEND_SUFFIX_BYTES) {
            this.cancelPath.append(b);
        }
    }

    private static boolean parseOrderId(final HTTPResponse response, final OrderContext ctx) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        final int from = body.position();
        final int to = body.limit();
        final int markerPos = indexOfBytes(body, from, to, ORDER_ID_MARKER);
        if (markerPos < 0) {
            return false;
        }
        final int valueStart = markerPos + ORDER_ID_MARKER.length;
        int valueEnd = valueStart;
        while (valueEnd < to && body.get(valueEnd) != '"') {
            valueEnd++;
        }
        if (valueEnd >= to) {
            return false;
        }
        ctx.exchangeOrderIdLength = Math.min(valueEnd - valueStart, OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH);
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            ctx.exchangeOrderIdBytes[i] = body.get(valueStart + i);
        }
        return true;
    }

    /**
     * Reads a Kalshi FixedPointCount field (a quoted decimal such as {@code "5.00"}) out of a
     * response body and scales it to {@link Statics#SIZE_SCALING_FACTOR}. Returns
     * {@link #OrderContext.QTY_ABSENT} when the field is missing, null or unparseable.
     */
    private static long parseFixedPointField(final HTTPResponse response, final byte[] marker) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return OrderContext.QTY_ABSENT;
        }
        final int markerPos = indexOfBytes(body, body.position(), body.limit(), marker);
        if (markerPos < 0) {
            return OrderContext.QTY_ABSENT;
        }
        return scanFixedPoint(body, markerPos + marker.length, body.limit());
    }

    private static long scanFixedPoint(final ByteBuffer body, final int start, final int to) {
        int pos = start;
        long whole = 0;
        while (pos < to) {
            final int digit = body.get(pos) - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            whole = whole * 10 + digit;
            pos++;
        }
        if (pos == start) {
            return OrderContext.QTY_ABSENT;
        }

        final long value = whole * Statics.SIZE_SCALING_FACTOR;
        if (pos >= to || body.get(pos) != '.') {
            return value;
        }
        return value + scanFraction(body, pos + 1, to);
    }

    private static long scanFraction(final ByteBuffer body, final int start, final int to) {
        long frac = 0;
        long place = Statics.SIZE_SCALING_FACTOR / 10;
        int pos = start;
        while (pos < to && place > 0) {
            final int digit = body.get(pos) - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            frac += digit * place;
            place /= 10;
            pos++;
        }
        return frac;
    }

    private static int indexOfBytes(final ByteBuffer buf, final int from, final int to, final byte[] pattern) {
        outer:
        for (int i = from; i <= to - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get(i + j) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String resolveTimeInForce(final OrderType orderType, final TimeInForce tif) {
        if (orderType == OrderType.MARKET) {
            return "fill_or_kill";
        }
        if (tif == TimeInForce.IMMEDIATE_OR_CANCELED) {
            return "immediate_or_cancel";
        }
        return "good_till_canceled";
    }

    private long epochMillis() {
        return this.clock.nanoTime() / NANOS_PER_MILLI;
    }
}
