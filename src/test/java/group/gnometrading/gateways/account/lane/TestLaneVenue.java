package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.strings.MutableString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A plain venue for lane tests: an order's id is {@code s<slot>-<counter>}, submitted as {@code POST /orders} with it
 * as the body; a 200 answers with the venue's id as its body, a 409 is a duplicate, a 404 to {@code GET /orders/<id>}
 * says it isn't there. Cancels are {@code DELETE /orders/<venue id>}, amends {@code POST /orders/<venue id>/amend}.
 *
 * <p>With {@link #maxBatch} above 1 it takes submits and cancels in batches: {@code POST /batch} with the ids joined
 * by commas, answered with the venue's ids joined the same way, and {@code POST /batch/cancel} with the venue ids. A
 * 400 whose body is {@code order[<i>]} refuses the whole batch for entry i.
 */
public final class TestLaneVenue implements LaneVenue<String> {

    public static final String MARKET = "market";
    public static final int EXCHANGE_ID = 7;
    public static final long SECURITY_ID = 11;

    public boolean resendsAreSafe = true;
    // Off: the venue can't look an order up, so an unclear submit is left to its events.
    public boolean lookupsPossible = true;
    // Set: every order gets this correlation id, as two sessions' orders could if their ids collided.
    public String fixedCorrelation;
    public int maxBatch = 1;

    @Override
    public String market(final int exchangeId, final long securityId) {
        return exchangeId == EXCHANGE_ID && securityId == SECURITY_ID ? MARKET : null;
    }

    @Override
    public int prepare(
            final LaneCommand command,
            final String market,
            final OrderContext ctx,
            final byte[] body,
            final long nowMillis) {
        final String text = this.fixedCorrelation != null
                ? this.fixedCorrelation
                : "s" + command.sessionSlot + "-" + command.clientOidCounter;
        final byte[] id = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
        ctx.correlationIdLength = id.length;
        System.arraycopy(id, 0, body, 0, id.length);
        return id.length;
    }

    @Override
    public boolean resendsAreSafe() {
        return this.resendsAreSafe;
    }

    @Override
    public boolean venueIdKnown(final OrderContext ctx) {
        return ctx.exchangeOrderIdLength > 0;
    }

    @Override
    public boolean canLookUp(final OrderContext ctx) {
        return this.lookupsPossible;
    }

    @Override
    public boolean canAmend(final OrderContext ctx, final String market, final long price, final long size) {
        return true;
    }

    @Override
    public void submitShape(final OrderContext ctx, final String market, final RequestShape shape) {
        append(shape.start(HTTPMethod.POST), "/orders");
    }

    @Override
    public boolean lookupShape(final OrderContext ctx, final String market, final RequestShape shape) {
        if (!this.lookupsPossible) {
            return false;
        }
        final MutableString path = shape.start(HTTPMethod.GET);
        append(path, "/orders/");
        for (int i = 0; i < ctx.correlationIdLength; i++) {
            path.append(ctx.correlationIdBytes[i]);
        }
        return true;
    }

    @Override
    public void cancelShape(final OrderContext ctx, final String market, final RequestShape shape) {
        venuePath(ctx, shape.start(HTTPMethod.DELETE), "");
    }

    @Override
    public void amendShape(final OrderContext ctx, final String market, final RequestShape shape) {
        venuePath(ctx, shape.start(HTTPMethod.POST), "/amend");
    }

    @Override
    public void writeSubmit(
            final OrderContext ctx,
            final RequestShape shape,
            final byte[] body,
            final int bodyLength,
            final HTTPRequestWriter request,
            final long nowMillis) {
        request.body(body, 0, bodyLength);
    }

    @Override
    public void writeLookup(
            final OrderContext ctx,
            final String market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        request.noBody();
    }

    @Override
    public void writeCancel(
            final OrderContext ctx,
            final String market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        request.noBody();
    }

    @Override
    public void writeAmend(
            final OrderContext ctx,
            final String market,
            final long price,
            final long size,
            final boolean postOnly,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final byte[] body = (price + ":" + size).getBytes(StandardCharsets.US_ASCII);
        request.body(body, 0, body.length);
    }

    @Override
    public SubmitResult readSubmit(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            readVenueId(ctx, response);
            return SubmitResult.ACCEPTED;
        }
        if (response.getStatusCode() == 409) {
            return SubmitResult.DUPLICATE;
        }
        final SubmitResult result = SubmitPolicy.classifyFailure(response.getStatusCode());
        if (result == SubmitResult.REJECTED) {
            ctx.rejectReason = RejectReason.INVALID_PRICE;
        }
        return result;
    }

    @Override
    public LookupResult readLookup(final OrderContext ctx, final HTTPResponse response) {
        if (response.isSuccess()) {
            readVenueId(ctx, response);
            return LookupResult.FOUND;
        }
        return response.getStatusCode() == 404 ? LookupResult.NOT_FOUND : LookupResult.UNKNOWN;
    }

    @Override
    public CancelResult readCancel(final OrderContext ctx, final HTTPResponse response) {
        return response.isSuccess() ? CancelResult.ACCEPTED : SubmitPolicy.classifyCancelFailure(response);
    }

    @Override
    public RejectReason readAmend(
            final OrderContext ctx, final long newSize, final HTTPResponse response, final AmendAnswer out) {
        return response.isSuccess() ? null : RejectReason.EXCHANGE_REJECTED;
    }

    @Override
    public int maxBatch() {
        return this.maxBatch;
    }

    @Override
    public void batchSubmitShape(final RequestShape shape) {
        append(shape.start(HTTPMethod.POST), "/batch");
    }

    @Override
    public void writeBatchSubmit(
            final OrderContext[] ctxs,
            final byte[][] bodies,
            final int[] bodyLengths,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append(i == 0 ? "" : ",").append(new String(bodies[i], 0, bodyLengths[i], StandardCharsets.US_ASCII));
        }
        final byte[] bytes = body.toString().getBytes(StandardCharsets.US_ASCII);
        request.body(bytes, 0, bytes.length);
    }

    @Override
    public int readBatchSubmit(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final SubmitResult[] results) {
        if (response.isSuccess()) {
            final String[] ids = text(response).split(",");
            for (int i = 0; i < count; i++) {
                final byte[] id = ids[i].getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(id, 0, ctxs[i].exchangeOrderIdBytes, 0, id.length);
                ctxs[i].exchangeOrderIdLength = id.length;
                results[i] = SubmitResult.ACCEPTED;
            }
            return NO_FAILED_ENTRY;
        }
        final int failed = failedEntry(response);
        if (failed != NO_FAILED_ENTRY) {
            ctxs[failed].rejectReason = RejectReason.INVALID_PRICE;
            return failed;
        }
        for (int i = 0; i < count; i++) {
            results[i] = SubmitPolicy.classifyFailure(response.getStatusCode());
        }
        return NO_FAILED_ENTRY;
    }

    @Override
    public void batchCancelShape(final RequestShape shape) {
        append(shape.start(HTTPMethod.POST), "/batch/cancel");
    }

    @Override
    public void writeBatchCancel(
            final OrderContext[] ctxs,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        final StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append(i == 0 ? "" : ",")
                    .append(new String(
                            ctxs[i].exchangeOrderIdBytes, 0, ctxs[i].exchangeOrderIdLength, StandardCharsets.US_ASCII));
        }
        final byte[] bytes = body.toString().getBytes(StandardCharsets.US_ASCII);
        request.body(bytes, 0, bytes.length);
    }

    @Override
    public int readBatchCancel(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final CancelResult[] results) {
        if (response.isSuccess()) {
            for (int i = 0; i < count; i++) {
                results[i] = CancelResult.ACCEPTED;
            }
            return NO_FAILED_ENTRY;
        }
        final int failed = failedEntry(response);
        if (failed != NO_FAILED_ENTRY) {
            return failed;
        }
        for (int i = 0; i < count; i++) {
            results[i] = SubmitPolicy.classifyCancelFailure(response);
        }
        return NO_FAILED_ENTRY;
    }

    private static int failedEntry(final HTTPResponse response) {
        final String text = response.getStatusCode() == 400 ? text(response) : "";
        return text.startsWith("order[") ? Integer.parseInt(text.substring(6, text.indexOf(']'))) : NO_FAILED_ENTRY;
    }

    private static String text(final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        return body == null
                ? ""
                : StandardCharsets.US_ASCII.decode(body.duplicate()).toString();
    }

    private static void readVenueId(final OrderContext ctx, final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        int length = 0;
        for (int i = body.position(); i < body.limit(); i++) {
            ctx.exchangeOrderIdBytes[length++] = body.get(i);
        }
        ctx.exchangeOrderIdLength = length;
    }

    private static void venuePath(final OrderContext ctx, final MutableString path, final String suffix) {
        append(path, "/orders/");
        for (int i = 0; i < ctx.exchangeOrderIdLength; i++) {
            path.append(ctx.exchangeOrderIdBytes[i]);
        }
        append(path, suffix);
    }

    private static void append(final MutableString path, final String text) {
        for (int i = 0; i < text.length(); i++) {
            path.append((byte) text.charAt(i));
        }
    }
}
