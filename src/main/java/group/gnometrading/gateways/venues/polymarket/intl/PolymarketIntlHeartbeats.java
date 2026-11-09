package group.gnometrading.gateways.venues.polymarket.intl;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPCompletion;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.AsciiParsing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.agrona.concurrent.EpochClock;

/**
 * Keeps a Polymarket International key's heartbeat alive. Once one beat is accepted the venue cancels every order on
 * the key if 10 s pass without another, and there is no way to disarm it; a single lost request must never cost the
 * orders. So beats go every 2.5 s on their own connections, kept warm and apart from order traffic; a request with no
 * answer within 2 s is sent again at once on another connection; the venue's chained {@code heartbeat_id} is kept, and
 * a 400 naming the id it expects is answered with that id straight away.
 *
 * <p>Beats are sent only while {@code mayBeat} holds: the caller passes whether the account's lane is alive, so that a
 * dead lane lets the venue cancel rather than keeping orders alive behind it. {@link #atRisk()} turns true once 6 s have
 * passed without an accepted beat. Runs on its own thread, opening its connections itself; allocates nothing once
 * running.
 */
public final class PolymarketIntlHeartbeats implements GnomeAgent {

    public static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(2_500);
    public static final long REQUEST_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    public static final long AT_RISK_NANOS = TimeUnit.SECONDS.toNanos(6);
    // After an answer that isn't the venue's id, so a refusing venue isn't hammered; still several tries inside 10 s.
    private static final long ERROR_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int MAX_ID_LENGTH = 128;
    private static final ViewString PATH = new ViewString("/v1/heartbeats");
    private static final byte[] BODY_START = "{\"heartbeat_id\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BODY_END = "\"}".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_MARKER = "\"heartbeat_id\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUOTE = {'"'};
    private static final byte[] HEADER_API_KEY = ascii(PolymarketIntlAuthHeaders.API_KEY_HEADER);
    private static final byte[] HEADER_SIGNATURE = ascii(PolymarketIntlAuthHeaders.SIGNATURE_HEADER);
    private static final byte[] HEADER_TIMESTAMP = ascii(PolymarketIntlAuthHeaders.TIMESTAMP_HEADER);
    private static final byte[] HEADER_PASSPHRASE = ascii(PolymarketIntlAuthHeaders.PASSPHRASE_HEADER);
    private static final byte[] HEADER_ADDRESS = ascii(PolymarketIntlAuthHeaders.ADDRESS_HEADER);
    private static final byte[] HEADER_CONTENT_TYPE = ascii("Content-Type");
    private static final byte[] CONTENT_TYPE_JSON = ascii("application/json");
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final String name;
    private final Logger logger;
    private final AsyncHTTPConnections connections;
    private final HTTPConnector connector;
    private final PolymarketIntlAuthHeaders auth;
    private final BooleanSupplier mayBeat;
    private final LongSupplier nanoClock;
    private final EpochClock epochClock;
    private final long intervalNanos;
    private final long requestTimeoutNanos;
    private final long atRiskNanos;

    private final byte[] heartbeatId = new byte[MAX_ID_LENGTH];
    private int heartbeatIdLength;
    private final byte[] body = new byte[BODY_START.length + MAX_ID_LENGTH + BODY_END.length];
    private boolean inFlight;
    private long tag;
    private long nextSendNanos;
    private volatile long lastAcceptedNanos;
    private volatile boolean armed;
    private volatile boolean atRisk;
    // Written on this agent's thread only; read for status.
    private volatile long acceptedCount;
    private volatile long mismatchCount;

    /**
     * @param connections the heartbeat's own connections, at least two, so a retry finds one already open
     * @param connector opens and replaces {@code connections}; run on this agent's thread
     * @param auth its own instance: it is signed on this thread
     * @param mayBeat whether beats may go now; false lets the venue cancel the key's orders
     */
    public PolymarketIntlHeartbeats(
            final String name,
            final Logger logger,
            final AsyncHTTPConnections connections,
            final HTTPConnector connector,
            final PolymarketIntlAuthHeaders auth,
            final BooleanSupplier mayBeat,
            final LongSupplier nanoClock,
            final EpochClock epochClock,
            final long intervalNanos,
            final long requestTimeoutNanos,
            final long atRiskNanos) {
        if (connections.size() < 2) {
            throw new IllegalArgumentException("Heartbeats need at least two connections, to retry on a fresh one");
        }
        this.name = name;
        this.logger = logger;
        this.connections = connections;
        this.connector = connector;
        this.auth = auth;
        this.mayBeat = mayBeat;
        this.nanoClock = nanoClock;
        this.epochClock = epochClock;
        this.intervalNanos = intervalNanos;
        this.requestTimeoutNanos = requestTimeoutNanos;
        this.atRiskNanos = atRiskNanos;
        System.arraycopy(BODY_START, 0, this.body, 0, BODY_START.length);
    }

    @Override
    public int doWork() throws Exception {
        int work = this.connector.doWork();
        final long now = this.nanoClock.getAsLong();
        if (this.inFlight) {
            work += pollAnswer(now);
        }
        if (!this.inFlight && now - this.nextSendNanos >= 0 && this.mayBeat.getAsBoolean()) {
            work += send(now);
        }
        checkRisk(now);
        return work;
    }

    /** Whether a beat has been accepted, so the venue will cancel the key's orders if beats stop. */
    public boolean armed() {
        return this.armed;
    }

    /** Whether the key is armed and no beat has been accepted for the at-risk time: its orders may soon be cancelled. */
    public boolean atRisk() {
        return this.atRisk;
    }

    /** Beats the venue has accepted. */
    public long accepted() {
        return this.acceptedCount;
    }

    /** Beats the venue refused for carrying another id than it expected, each then resent with the id it named. */
    public long mismatches() {
        return this.mismatchCount;
    }

    /** When the last beat was accepted, on this agent's clock; 0 if none has been. */
    public long lastAcceptedNanos() {
        return this.lastAcceptedNanos;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return this.name + "-heartbeats";
    }

    private int send(final long now) {
        final int connection = this.connections.acquire();
        if (connection == AsyncHTTPConnections.NONE) {
            return 0;
        }
        final int bodyLength = writeBody();
        this.auth.sign(
                this.epochClock.time() / MILLIS_PER_SECOND, "POST", PATH, PATH.length(), this.body, 0, bodyLength);
        final HTTPRequestWriter request = this.connections
                .request(connection, HTTPMethod.POST)
                .path(PATH)
                .header(HEADER_API_KEY, this.auth.apiKeyBytes())
                .header(HEADER_SIGNATURE, this.auth.signatureBytes(), 0, this.auth.signatureLength())
                .header(HEADER_TIMESTAMP, this.auth.timestampBytes(), 0, this.auth.timestampLength())
                .header(HEADER_PASSPHRASE, this.auth.passphraseBytes())
                .header(HEADER_ADDRESS, this.auth.addressBytes())
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        request.body(this.body, 0, bodyLength);
        final int result = this.connections.send(connection, ++this.tag, now + this.requestTimeoutNanos);
        if (result == AsyncHTTPConnections.SENT) {
            this.inFlight = true;
        }
        // Not sent, or failed mid-write: tried again on the next pass, on whichever connection is open.
        return 1;
    }

    private int writeBody() {
        int length = BODY_START.length;
        System.arraycopy(this.heartbeatId, 0, this.body, length, this.heartbeatIdLength);
        length += this.heartbeatIdLength;
        System.arraycopy(BODY_END, 0, this.body, length, BODY_END.length);
        return length + BODY_END.length;
    }

    private int pollAnswer(final long now) {
        if (!this.connections.poll()) {
            return 0;
        }
        final HTTPCompletion completion = this.connections.completion();
        if (completion.tag() != this.tag) {
            return 1;
        }
        this.inFlight = false;
        if (completion.outcome() != HTTPCompletion.RESPONSE) {
            // No answer in time: it may have been accepted, but the deadline is too close to wait and see. Its
            // connection is closed; the retry goes at once on another, with the same id.
            return 1;
        }
        final HTTPResponse response = completion.response();
        if (response.isSuccess() && readId(response)) {
            onAccepted(now);
        } else if (response.getStatusCode() == HTTP_BAD_REQUEST && readId(response)) {
            // The venue expects another id, the one it names: a rejected beat refreshes nothing, so at once.
            this.mismatchCount++;
            this.nextSendNanos = now;
        } else {
            this.nextSendNanos = now + ERROR_RETRY_NANOS;
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: Polymarket heartbeat refused with %d",
                    this.name,
                    response.getStatusCode());
        }
        return 1;
    }

    private void onAccepted(final long now) {
        this.acceptedCount++;
        this.lastAcceptedNanos = now;
        this.nextSendNanos = now + this.intervalNanos;
        this.armed = true;
    }

    /** Takes the {@code heartbeat_id} an answer carries; false if it carries none. */
    private boolean readId(final HTTPResponse response) {
        final ByteBuffer body = response.getBody();
        if (body == null || !body.hasRemaining()) {
            return false;
        }
        final int marker = AsciiParsing.indexOf(body, body.position(), body.limit(), ID_MARKER);
        if (marker < 0) {
            return false;
        }
        final int start = marker + ID_MARKER.length;
        final int end = AsciiParsing.indexOf(body, start, body.limit(), QUOTE);
        if (end < 0 || end - start > MAX_ID_LENGTH) {
            return false;
        }
        for (int i = start; i < end; i++) {
            this.heartbeatId[i - start] = body.get(i);
        }
        this.heartbeatIdLength = end - start;
        return true;
    }

    /** Logged only when it changes. */
    private void checkRisk(final long now) {
        final long since = this.lastAcceptedNanos;
        final boolean risk = this.armed && now - since > this.atRiskNanos;
        if (risk != this.atRisk) {
            this.atRisk = risk;
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    risk
                            ? "%s: no Polymarket heartbeat accepted for %d ms; the venue cancels every order at 10 s"
                            : "%s: Polymarket heartbeat accepted again after %d ms",
                    this.name,
                    TimeUnit.NANOSECONDS.toMillis(now - since));
        }
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
