package group.gnometrading.gateways.orders;

import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.RejectReason;

/**
 * What to do about an order submission after each answer, until it is settled: accepted, rejected, or left registered
 * for the venue's own events to settle. Venue-agnostic and free of I/O, so the blocking per-listing writer and the
 * pipelined per-account lane follow the same rules; each drives it with the outcome of every send and lookup.
 *
 * <p>A submit with no clear answer is sent again where the venue deduplicates it, since looking it up straight away
 * could miss one the venue is still processing; elsewhere it is only looked up. A lookup's miss is believed only once
 * the last send is {@code readSettleNanos} old: a venue's reads can lag its writes (Kalshi's by about a second), and
 * an order wrongly taken as never placed would rest unseen.
 */
public final class SubmitPolicy {

    /** Sends after the first one with no clear answer. */
    public static final int MAX_RESENDS = 2;

    public enum Action {
        ACCEPT,
        /** Rejected by the venue: the reason is {@link OrderContext#rejectReason}, or EXCHANGE_REJECTED if unset. */
        REJECT,
        REJECT_RATE_LIMITED,
        RESEND,
        /** Look the order up, not before {@link OrderContext#lookUpNotBeforeNanos}. */
        LOOK_UP,
        /** It may be live: it stays registered and the venue's events settle it. */
        LEAVE_UNKNOWN
    }

    /** Why an order is being looked up, which decides what a miss means. */
    public enum LookupReason {
        /** A resend was refused, for the venue's own reasons or the rate limit: a miss means the first never landed. */
        RESEND_REFUSED,
        /** A resend was refused as a duplicate, so the order exists; the lookup is only for its venue id. */
        DUPLICATE,
        /** The venue would place a resend twice, so the order is only looked for, and a miss proves nothing. */
        NO_RESEND,
        /**
         * Looked for again, long after its last send, as its submit's outcome was left unknown: the venue's reads have
         * long caught up, so a miss means it never landed.
         */
        SWEEP
    }

    private final long readSettleNanos;

    public SubmitPolicy(final long readSettleNanos) {
        this.readSettleNanos = readSettleNanos;
    }

    /**
     * After a send, the first or a resend, whose start was recorded in {@link OrderContext#lastSendNanos}.
     *
     * @param resendsAreSafe whether the venue deduplicates a resent submit
     */
    public Action afterSend(final OrderContext ctx, final SubmitResult result, final boolean resendsAreSafe) {
        final boolean first = ctx.submitAttempts == 0;
        ctx.submitAttempts++;
        if (first) {
            switch (result) {
                case ACCEPTED:
                    return Action.ACCEPT;
                    // A first send can't be a duplicate of our own: the id is new, so the venue is refusing it.
                case REJECTED:
                case DUPLICATE:
                    return Action.REJECT;
                case RATE_LIMITED:
                    return Action.REJECT_RATE_LIMITED;
                default:
                    return resendsAreSafe ? Action.RESEND : lookUp(ctx, LookupReason.NO_RESEND, 0);
            }
        }
        switch (result) {
            case ACCEPTED:
                return Action.ACCEPT;
            case DUPLICATE:
                return lookUp(ctx, LookupReason.DUPLICATE, 0);
                // A resend refused for the rate limit says nothing of the first send, which may have landed.
            case REJECTED:
            case RATE_LIMITED:
                return lookUp(ctx, LookupReason.RESEND_REFUSED, 0);
            default:
                return resendOrLeave(ctx);
        }
    }

    /** After a lookup made for the reason {@link #afterSend} gave, at {@code nowNanos}. */
    public Action afterLookUp(final OrderContext ctx, final LookupResult result, final long nowNanos) {
        if (result == LookupResult.FOUND) {
            return Action.ACCEPT;
        }
        if (ctx.lookupReason == LookupReason.SWEEP) {
            return result == LookupResult.NOT_FOUND ? Action.REJECT : Action.LEAVE_UNKNOWN;
        }
        if (ctx.lookupReason != LookupReason.RESEND_REFUSED) {
            return Action.LEAVE_UNKNOWN;
        }
        if (result == LookupResult.UNKNOWN) {
            return resendOrLeave(ctx);
        }
        final long settledAt = ctx.lastSendNanos + this.readSettleNanos;
        if (nowNanos - settledAt >= 0) {
            return Action.REJECT;
        }
        return lookUp(ctx, LookupReason.RESEND_REFUSED, settledAt);
    }

    private static Action resendOrLeave(final OrderContext ctx) {
        return ctx.submitAttempts <= MAX_RESENDS ? Action.RESEND : Action.LEAVE_UNKNOWN;
    }

    private static Action lookUp(final OrderContext ctx, final LookupReason reason, final long notBeforeNanos) {
        ctx.lookupReason = reason;
        ctx.lookUpNotBeforeNanos = notBeforeNanos;
        return Action.LOOK_UP;
    }

    /**
     * The usual reading of an HTTP status: a 429 refused it for the rate limit, any other 4xx refused it, and anything
     * else unclear is unknown.
     */
    public static SubmitResult classifyFailure(final int statusCode) {
        if (statusCode == HTTPResponse.STATUS_TOO_MANY_REQUESTS) {
            return SubmitResult.RATE_LIMITED;
        }
        return statusCode >= 400 && statusCode < 500 ? SubmitResult.REJECTED : SubmitResult.UNKNOWN;
    }

    /** The reading of a refused cancel's response: the rate limit for a 429, else the venue's refusal. */
    public static CancelResult classifyCancelFailure(final HTTPResponse response) {
        return response.getStatusCode() == HTTPResponse.STATUS_TOO_MANY_REQUESTS
                ? CancelResult.RATE_LIMITED
                : CancelResult.REJECTED;
    }

    /**
     * Why the OMS is told a refused amend failed: the rate limit for a 429, else the reason the venue's text gives, else
     * the venue's refusal.
     */
    public static RejectReason rejectReasonFor(final HTTPResponse response, final VenueRejectText rejectText) {
        if (response.getStatusCode() == HTTPResponse.STATUS_TOO_MANY_REQUESTS) {
            return RejectReason.RATE_LIMITED;
        }
        final RejectReason reason = rejectText.classify(response.getBody());
        return reason != null ? reason : RejectReason.EXCHANGE_REJECTED;
    }
}
