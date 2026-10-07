package group.gnometrading.gateways.outbound.exchanges.kalshi;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.outbound.OrderContext;
import group.gnometrading.gateways.outbound.OutboundSocketWriter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.MutableString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ByteBufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

public final class KalshiOutboundWriter extends OutboundSocketWriter {

    private static final String ORDER_PATH = "/trade-api/v2/portfolio/events/orders";
    private static final String EXCHANGE_STATUS_PATH = "/trade-api/v2/exchange/status";
    private static final long LOOKUP_WINDOW_SECONDS = 60L;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int MAX_LOGGED_REASON_CHARS = 500;
    private static final ViewString ORDER_PATH_GS = new ViewString(ORDER_PATH);
    private static final String AMEND_SUFFIX = "/amend";
    private static final byte[] AMEND_SUFFIX_BYTES = AMEND_SUFFIX.getBytes(StandardCharsets.US_ASCII);

    private static final String HEADER_KEY = "KALSHI-ACCESS-KEY";
    private static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    private static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";
    // Kalshi refuses a JSON body without it (400 invalid_content_type), and the HTTP client never writes one.
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private static final byte[] ORDER_ID_MARKER = "\"order_id\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMAINING_COUNT_MARKER = "\"remaining_count\":\"".getBytes(StandardCharsets.UTF_8);

    // PRICE_SCALING_FACTOR / 10_000 — converts internal price to 4-decimal fractional part
    private static final long PRICE_SCALE_DIVISOR = Statics.PRICE_SCALING_FACTOR / 10_000L;
    // SIZE_SCALING_FACTOR / 100 — converts internal size to 2-decimal fractional part
    private static final long SIZE_SCALE_DIVISOR = Statics.SIZE_SCALING_FACTOR / 100L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Logger logger;
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

    private final byte[] clientOrderIdPrefix;
    // Each order's id is written here, then copied into its context, so no buffer is made per order.
    private final byte[] clientOrderIdBuf = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private final ByteBuffer clientOrderIdBuffer = ByteBuffer.wrap(clientOrderIdBuf);
    private final byte[] lookupOrderId = new byte[OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH];
    private int lookupOrderIdLength;

    // The market's exchange shard, read from Kalshi at start; -1 until known, when Kalshi routes by ticker alone,
    // which reaches the right shard but costs every shard's write budget and some latency.
    private int exchangeIndex = -1;
    // Cancels take their routing as a query; an order id alone can't name the shard.
    private byte[] cancelRoutingQuery;

    private final MutableString cancelPath;
    private final KalshiOrderPages orderPages;

    public KalshiOutboundWriter(
            final Logger logger,
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final ManyToOneRingBuffer<OrderContext> newOrderQueue,
            final ManyToOneRingBuffer<OrderContext> writerReportQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue,
            final HTTPClient httpClient,
            final String apiHost,
            final KalshiAuthSigner authSigner,
            final EpochNanoClock clock,
            final Listing listing,
            final String sessionTag) {
        super(orderOutboundBuffer, newOrderQueue, writerReportQueue, releasedOrderQueue);
        this.logger = logger;
        this.httpClient = httpClient;
        this.apiHost = apiHost;
        this.authSigner = authSigner;
        this.clock = clock;
        this.jsonEncoder.wrap(this.jsonBodyBuffer);
        this.clientOrderIdPrefix = (sessionTag + "-").getBytes(StandardCharsets.US_ASCII);

        final String exchangeSecurityId = listing.exchangeSecurityId();
        final int colonIdx = exchangeSecurityId.indexOf(':');
        final String suffix = colonIdx >= 0 ? exchangeSecurityId.substring(colonIdx + 1) : "";
        this.marketTicker = colonIdx >= 0 ? exchangeSecurityId.substring(0, colonIdx) : exchangeSecurityId;
        this.isNoListing = "no".equalsIgnoreCase(suffix);
        this.cancelRoutingQuery = routingQuery(KalshiApiUtil.SHARD_UNKNOWN);
        this.orderPages = new KalshiOrderPages(httpClient, apiHost, authSigner, this::epochMillis);
        // Room for the routing query with any shard number.
        this.cancelPath = new MutableString(ORDER_PATH.length()
                + 1
                + OrderContext.EXCHANGE_ORDER_ID_MAX_LENGTH
                + AMEND_SUFFIX.length()
                + this.cancelRoutingQuery.length
                + "&exchange_index=".length()
                + 11);
    }

    /** Reads the market's exchange shard, so every request goes straight to it. */
    @Override
    public void onStart() {
        try {
            this.exchangeIndex = fetchExchangeIndex();
        } catch (final IOException | RuntimeException e) {
            this.exchangeIndex = KalshiApiUtil.SHARD_UNKNOWN;
        }
        if (this.exchangeIndex < 0) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Kalshi market %s: exchange shard unknown, orders will route by ticker",
                    this.marketTicker);
        }
        this.cancelRoutingQuery = routingQuery(this.exchangeIndex);
    }

    private int fetchExchangeIndex() throws IOException {
        return KalshiApiUtil.fetchExchangeIndex(
                this.httpClient, this.apiHost, this.authSigner, epochMillis(), this.marketTicker);
    }

    private byte[] routingQuery(final int shard) {
        return KalshiApiUtil.routingQuery(this.marketTicker, shard).getBytes(StandardCharsets.US_ASCII);
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
        ctx.submittedAtMillis = epochMillis();
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
                this.authSigner.signature(),
                HEADER_CONTENT_TYPE,
                CONTENT_TYPE_JSON);

        if (!response.isSuccess()) {
            final int status = response.getStatusCode();
            logRejection("submit", ctx, status, response);
            // A 503 while the shard isn't trading is Kalshi refusing every order, not a lost answer.
            if (status == HTTP_SERVICE_UNAVAILABLE && !shardIsTrading()) {
                return SubmitResult.REJECTED;
            }
            // Kalshi's answer to a client_order_id it already has.
            if (status == HTTP_CONFLICT) {
                return SubmitResult.DUPLICATE;
            }
            return classifyFailure(status);
        }
        return parseOrderId(response, ctx) ? SubmitResult.ACCEPTED : SubmitResult.UNKNOWN;
    }

    /** Whether the market's shard is taking orders; true when that can't be told, so an order isn't given up on. */
    private boolean shardIsTrading() {
        try {
            this.authSigner.sign(epochMillis(), "GET", EXCHANGE_STATUS_PATH);
            final HTTPResponse response = this.httpClient.get(
                    HTTPProtocol.HTTPS,
                    this.apiHost,
                    EXCHANGE_STATUS_PATH,
                    HEADER_KEY,
                    this.authSigner.apiKey(),
                    HEADER_TIMESTAMP,
                    this.authSigner.timestamp(),
                    HEADER_SIGNATURE,
                    this.authSigner.signature());
            // Read whatever the status code: in an outage the status endpoint is itself a 503, saying trading is off.
            if (response.getBody() == null) {
                return true;
            }
            return readTradingActive(response.getBody());
        } catch (final IOException | RuntimeException e) {
            return true;
        }
    }

    private boolean readTradingActive(final ByteBuffer body) {
        boolean exchangeTrading = true;
        int shardTrading = -1;
        try (var root = this.jsonDecoder.wrap(body);
                var status = root.asObject()) {
            while (status.hasNextKey()) {
                try (var entry = status.nextKey()) {
                    if (entry.getName().equals("trading_active")) {
                        exchangeTrading = entry.asBoolean();
                    } else if (entry.getName().equals("exchange_index_statuses")) {
                        shardTrading = readShardTrading(entry);
                    }
                }
            }
        }
        return shardTrading >= 0 ? shardTrading == 1 : exchangeTrading;
    }

    /** 1 if this market's shard is listed as trading, 0 if listed as not, -1 if it isn't listed. */
    private int readShardTrading(final JsonDecoder.JsonNode statuses) {
        int result = -1;
        try (var list = statuses.asArray()) {
            while (list.hasNextItem()) {
                final int shard = readShardIfOurs(list.nextItem());
                if (shard >= 0) {
                    result = shard;
                }
            }
        }
        return result;
    }

    /** The shard's trading flag as 1 or 0 if it is this market's shard, else -1. */
    private int readShardIfOurs(final JsonDecoder.JsonNode item) {
        int index = -1;
        boolean trading = true;
        try (item;
                var shard = item.asObject()) {
            while (shard.hasNextKey()) {
                final var field = shard.nextKey();
                if (field.getName().equals("exchange_index")) {
                    index = (int) field.asLong();
                } else if (field.getName().equals("trading_active")) {
                    trading = field.asBoolean();
                }
                field.close();
            }
        }
        if (index != this.exchangeIndex) {
            return -1;
        }
        return trading ? 1 : 0;
    }

    /** Logs a refusal with Kalshi's own reason; only on the error path, so building the text is fine. */
    private void logRejection(
            final String action, final OrderContext ctx, final int status, final HTTPResponse response) {
        if (status <= 0) {
            return;
        }
        final ByteBuffer body = response.getBody();
        String reason = body == null
                ? ""
                : StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        if (reason.length() > MAX_LOGGED_REASON_CHARS) {
            reason = reason.substring(0, MAX_LOGGED_REASON_CHARS);
        }
        this.logger.logf(
                LogMessage.ORDER_REJECTED_BY_VENUE,
                "Kalshi refused %s of %s: %d %s",
                action,
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII),
                status,
                reason);
    }

    /**
     * Looks for the order among this market's recent orders, every page of them. Kalshi cannot filter by client
     * order id, so the listing is narrowed to the ticker and to orders created since shortly before the order's
     * own submit.
     */
    @Override
    protected SubmitResult findOrder(final OrderContext ctx) throws Exception {
        final long minTimestampSeconds = ctx.submittedAtMillis / 1000L - LOOKUP_WINDOW_SECONDS;
        final List<ByteBuffer> pages;
        try {
            pages = this.orderPages.fetch(this.marketTicker, "&min_ts=" + minTimestampSeconds);
        } catch (final IOException e) {
            return SubmitResult.UNKNOWN;
        }
        for (final ByteBuffer page : pages) {
            if (findInOrderList(page, ctx)) {
                return SubmitResult.ACCEPTED;
            }
        }
        return SubmitResult.REJECTED;
    }

    /** Whether the order's Kalshi id is known, looking the order up if it isn't yet. */
    private boolean venueIdKnown(final OrderContext ctx) throws Exception {
        return ctx.exchangeOrderIdLength > 0 || findOrder(ctx) == SubmitResult.ACCEPTED;
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
     * {@code {sessionTag}-{counter}}. The OMS's counter restarts with each session, and Kalshi deduplicates on
     * this id, so the session tag keeps ids unique across sessions.
     */
    private void writeClientOrderId(final OrderContext ctx) {
        this.clientOrderIdBuffer.clear();
        this.clientOrderIdBuffer.put(this.clientOrderIdPrefix);
        ByteBufferUtils.putLongAscii(this.clientOrderIdBuffer, ctx.clientOidCounter);
        ctx.correlationIdLength = this.clientOrderIdBuffer.position();
        System.arraycopy(this.clientOrderIdBuf, 0, ctx.correlationIdBytes, 0, ctx.correlationIdLength);
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
        if (!venueIdKnown(ctx)) {
            // The submit's outcome is still unknown and Kalshi lists no such order: nothing can be sent, and the
            // OMS gets the same answer as for a cancel that never reached the venue.
            throw new IOException("Kalshi has no order "
                    + new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII)
                    + " to cancel");
        }
        buildOrderPath(ctx);
        // Kalshi signs the path without its query string.
        this.authSigner.sign(epochMillis(), "DELETE", this.cancelPath);
        for (final byte b : this.cancelRoutingQuery) {
            this.cancelPath.append(b);
        }

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

        if (!response.isSuccess()) {
            logRejection("cancel", ctx, response.getStatusCode(), response);
            return false;
        }
        return true;
    }

    @Override
    protected void handleModifyOrder() throws Exception {
        final long clientOidCounter = this.modifyOrder.getClientOidCounter();
        final OrderContext ctx = getActiveOrder(clientOidCounter);
        if (ctx == null) {
            return;
        }
        if (!venueIdKnown(ctx)) {
            enqueueCancelReject(ctx, RejectReason.UNKNOWN);
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
                this.authSigner.signature(),
                HEADER_CONTENT_TYPE,
                CONTENT_TYPE_JSON);

        if (response.isSuccess()) {
            // Kalshi acknowledges an amend only in this response body, and only the reader knows the
            // order's fills, so hand it the venue's numbers to build the acknowledgement from.
            ctx.originalQty = this.modifyOrder.decoder.size();
            // The amended count is what has filled plus what rests, and remaining_count is what rests after the
            // amend, so the order's fills so far are their difference. The response's own fill_count is only what
            // the amend filled by crossing the book.
            final long remaining = parseFixedPointField(response, REMAINING_COUNT_MARKER);
            enqueueAmendAccepted(
                    ctx,
                    ctx.originalQty,
                    remaining == OrderContext.QTY_ABSENT ? OrderContext.QTY_ABSENT : ctx.originalQty - remaining,
                    remaining);
        } else {
            logRejection("amend", ctx, response.getStatusCode(), response);
            enqueueCancelReject(ctx, RejectReason.EXCHANGE_REJECTED);
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
        writeExchangeIndex();
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
        writeExchangeIndex();
        this.jsonEncoder.writeObjectEnd();
        this.jsonBodyLength = this.jsonBodyBuffer.position();
    }

    private void writeExchangeIndex() {
        if (this.exchangeIndex >= 0) {
            this.jsonEncoder.writeComma();
            this.jsonEncoder.writeObjectEntry("exchange_index", this.exchangeIndex);
        }
    }

    // Kalshi takes fixed-point decimals as JSON strings and refuses numbers.
    private void writePriceField(final long price) {
        this.jsonEncoder.writeString("price").writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, price / Statics.PRICE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 4, (price % Statics.PRICE_SCALING_FACTOR) / PRICE_SCALE_DIVISOR);
        this.jsonBodyBuffer.put((byte) '"');
    }

    private void writeSizeField(final long size) {
        this.jsonEncoder.writeString("count").writeColon();
        this.jsonBodyBuffer.put((byte) '"');
        ByteBufferUtils.putLongAscii(this.jsonBodyBuffer, size / Statics.SIZE_SCALING_FACTOR);
        this.jsonBodyBuffer.put((byte) '.');
        ByteBufferUtils.putNaturalPaddedLongAscii(
                this.jsonBodyBuffer, 2, (size % Statics.SIZE_SCALING_FACTOR) / SIZE_SCALE_DIVISOR);
        this.jsonBodyBuffer.put((byte) '"');
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
