package group.gnometrading.gateways.account.lane;

import group.gnometrading.gateways.ratelimit.RequestClass;

/**
 * When one request of a session's went through an {@link AccountLane} and its venue, told to the lane's
 * {@link AccountLane.Admissions#timed} once the request is over. One instance, reused for every request and valid only
 * during that call.
 *
 * <p>The lane's times are on its monotonic clock ({@link AccountLane}'s {@code nanoClock}, which ingress also stamps
 * {@link LaneCommand#enqueuedNanos} with), 0 where the step didn't happen; the venue's are what its answer says of itself.
 */
public final class RequestTiming {

    /** The venue answered and took the request. */
    public static final int ANSWERED = 0;
    /** The venue answered and refused it. */
    public static final int REJECTED = 1;
    /** The connection failed while it was in flight: the venue may or may not have it. */
    public static final int TRANSPORT_ERROR = 2;
    /** No answer before the lane's deadline. */
    public static final int NO_ANSWER = 3;
    /** Never sent: held past its wait, or dropped for a later request of the same order. */
    public static final int NOT_SENT = 4;

    public static final long NO_VENUE_TIME = 0;
    public static final int NO_VENUE_PROCESSING = -1;

    public long sessionSlot;
    public long clientOidCounter;
    public RequestClass requestClass;
    public int outcome;
    /** When ingress put the command on the lane's ring. */
    public long ingressNanos;
    /** When the lane took it off. */
    public long takenNanos;
    /** When the lane decided to send it, or refused it. */
    public long decidedNanos;
    /** When the socket write carrying it returned. */
    public long wireSentNanos;
    /** When the lane found the venue's whole answer. */
    public long answeredNanos;
    /** How long before {@link #answeredNanos} the lane last looked for answers: the most that time can be late. */
    public long answerSlackNanos;
    /** The transport's own result: an HTTP status here; 0 if none came. */
    public int venueCode;
    /** The venue's own time for the request, epoch nanoseconds, where its answer gives one. */
    public long venueTsNanos;
    /** How long the venue says it took, where its answer says. */
    public int venueProcessingMicros;

    public void reset() {
        this.sessionSlot = 0;
        this.clientOidCounter = 0;
        this.requestClass = null;
        this.outcome = ANSWERED;
        this.ingressNanos = 0;
        this.takenNanos = 0;
        this.decidedNanos = 0;
        this.wireSentNanos = 0;
        this.answeredNanos = 0;
        this.answerSlackNanos = 0;
        this.venueCode = 0;
        this.venueTsNanos = NO_VENUE_TIME;
        this.venueProcessingMicros = NO_VENUE_PROCESSING;
    }
}
