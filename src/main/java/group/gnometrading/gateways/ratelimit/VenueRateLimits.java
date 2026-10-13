package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a venue allows one account to send: its buckets, which request draws on which and at what cost, and which
 * response headers report the venue's own count. The limits are the venue's published ones; {@link #withRate} and
 * {@link #withShare} adjust them from configuration.
 */
public final class VenueRateLimits {

    /** The share of a bucket's burst that background requests leave for orders, on top of the cancel reserve. */
    static final double BACKGROUND_HEADROOM = 0.3;

    static final String RETRY_AFTER = "Retry-After";
    static final String POLY_REMAINING = "Poly-RateLimit-Remaining";
    static final String POLY_RESET = "Poly-RateLimit-Reset";

    private static final String SHARE_KEY = "share";
    private static final String PER_SECOND_SUFFIX = ".per.second";

    private static final String KALSHI_ORDERS = "/trade-api/v2/portfolio/events/orders";

    /**
     * @param cancelReserve the share of the burst kept for cancels; 0 for a bucket cancels don't draw on
     */
    public record Bucket(String name, double tokensPerSecond, long burst, double cancelReserve) {}

    /**
     * Matches a request whose method is {@code method} (any if null) and whose path, before any query, starts with
     * {@code prefix} and ends with {@code suffix} (any ending if null).
     *
     * @param bucket the bucket it draws on; null if the venue doesn't limit it
     */
    public record Rule(HTTPMethod method, String prefix, String suffix, String bucket, long cost, RequestClass type) {}

    private final String venue;
    private final List<Bucket> buckets;
    private final List<Rule> rules;
    private final List<String> capturedHeaders;
    private final double share;

    private VenueRateLimits(
            final String venue,
            final List<Bucket> buckets,
            final List<Rule> rules,
            final List<String> capturedHeaders,
            final double share) {
        this.venue = venue;
        this.buckets = List.copyOf(buckets);
        this.rules = List.copyOf(rules);
        this.capturedHeaders = List.copyOf(capturedHeaders);
        this.share = share;
        validate();
    }

    /**
     * Kalshi's Basic tier: separate read and write token buckets per account. Orders cost 10 tokens, cancels 2,
     * everything else 10. Its 429 carries no Retry-After.
     */
    public static VenueRateLimits kalshi() {
        return new VenueRateLimits(
                "Kalshi",
                List.of(new Bucket("write", 100, 100, 0.2), new Bucket("read", 200, 200, 0)),
                List.of(
                        new Rule(HTTPMethod.POST, KALSHI_ORDERS + "/", "/amend", "write", 10, RequestClass.ORDER),
                        new Rule(HTTPMethod.POST, KALSHI_ORDERS, null, "write", 10, RequestClass.ORDER),
                        new Rule(HTTPMethod.DELETE, KALSHI_ORDERS + "/", null, "write", 2, RequestClass.CANCEL),
                        new Rule(HTTPMethod.GET, "", null, "read", 10, RequestClass.BACKGROUND),
                        new Rule(null, "", null, "write", 10, RequestClass.ORDER)),
                List.of(RETRY_AFTER),
                1.0);
    }

    /**
     * Polymarket International's standard tier, per signer: an order bucket and a separate cancel bucket. Reads are
     * left to Cloudflare, which delays rather than refuses them. Its order and cancel responses report what is
     * left of the bucket, and a 429 says when to retry.
     */
    public static VenueRateLimits polymarketIntl() {
        return new VenueRateLimits(
                "Polymarket International",
                List.of(new Bucket("order", 40, 60, 0), new Bucket("cancel", 80, 120, 0)),
                List.of(
                        new Rule(HTTPMethod.POST, "/order", null, "order", 1, RequestClass.ORDER),
                        new Rule(HTTPMethod.DELETE, "", null, "cancel", 1, RequestClass.CANCEL),
                        new Rule(HTTPMethod.GET, "", null, null, 0, RequestClass.BACKGROUND),
                        new Rule(null, "", null, "order", 1, RequestClass.ORDER)),
                List.of(RETRY_AFTER, POLY_REMAINING, POLY_RESET),
                1.0);
    }

    /** Polymarket US: one bucket per API key for every call. Its 429 carries no Retry-After. */
    public static VenueRateLimits polymarketUs() {
        return new VenueRateLimits(
                "Polymarket US",
                List.of(new Bucket("key", 20, 20, 0.2)),
                List.of(
                        new Rule(HTTPMethod.POST, "/v1/order/", "/cancel", "key", 1, RequestClass.CANCEL),
                        new Rule(HTTPMethod.POST, "/v1/order/", "/modify", "key", 1, RequestClass.ORDER),
                        new Rule(HTTPMethod.GET, "", null, "key", 1, RequestClass.BACKGROUND),
                        new Rule(null, "", null, "key", 1, RequestClass.ORDER)),
                List.of(RETRY_AFTER),
                1.0);
    }

    /**
     * These limits adjusted by configuration: {@code share} for {@link #withShare}, and {@code <bucket>.per.second}
     * for {@link #withRate}. Any other key is refused, so a misspelt one can't silently leave the defaults in place.
     */
    public VenueRateLimits withOverrides(final Map<String, String> overrides) {
        VenueRateLimits limits = this;
        for (final Map.Entry<String, String> entry : new TreeMap<>(overrides).entrySet()) {
            final String key = entry.getKey();
            final double value = Double.parseDouble(entry.getValue().trim());
            if (key.equals(SHARE_KEY)) {
                limits = limits.withShare(value);
            } else if (key.endsWith(PER_SECOND_SUFFIX)) {
                limits = limits.withRate(key.substring(0, key.length() - PER_SECOND_SUFFIX.length()), value);
            } else {
                throw new IllegalArgumentException(this.venue + " has no rate-limit setting " + key);
            }
        }
        return limits;
    }

    /** These limits with {@code bucket} refilling at {@code tokensPerSecond}, e.g. for a higher venue tier. */
    public VenueRateLimits withRate(final String bucket, final double tokensPerSecond) {
        final List<Bucket> changed = new ArrayList<>();
        boolean found = false;
        for (final Bucket b : this.buckets) {
            if (b.name().equals(bucket)) {
                changed.add(new Bucket(b.name(), tokensPerSecond, b.burst(), b.cancelReserve()));
                found = true;
            } else {
                changed.add(b);
            }
        }
        if (!found) {
            throw new IllegalArgumentException(this.venue + " has no rate-limit bucket " + bucket);
        }
        return new VenueRateLimits(this.venue, changed, this.rules, this.capturedHeaders, this.share);
    }

    /**
     * These limits with at most {@code share} of each bucket's rate ever used by this process: a cap for when other
     * processes share the account, never a reservation.
     */
    public VenueRateLimits withShare(final double newShare) {
        return new VenueRateLimits(this.venue, this.buckets, this.rules, this.capturedHeaders, newShare);
    }

    public String venue() {
        return this.venue;
    }

    /** The rate {@code bucket} refills at, before this process's share and budget are applied. */
    double tokensPerSecond(final String bucket) {
        return bucket(bucket).tokensPerSecond();
    }

    List<Bucket> buckets() {
        return this.buckets;
    }

    List<Rule> rules() {
        return this.rules;
    }

    /** The response headers an HTTP client must capture for these limits, in the order the limiter reads them. */
    public String[] capturedHeaders() {
        return this.capturedHeaders.toArray(new String[0]);
    }

    double share() {
        return this.share;
    }

    int headerIndex(final String name) {
        return this.capturedHeaders.indexOf(name);
    }

    private void validate() {
        if (!(this.share > 0 && this.share <= 1)) {
            throw new IllegalArgumentException(this.venue + " rate-limit share must be in (0, 1]");
        }
        final Rule last = this.rules.get(this.rules.size() - 1);
        if (last.method() != null || !last.prefix().isEmpty() || last.suffix() != null) {
            throw new IllegalArgumentException(this.venue + " rate-limit rules must end with one matching everything");
        }
        for (final Rule rule : this.rules) {
            if (rule.bucket() == null) {
                continue;
            }
            final Bucket bucket = bucket(rule.bucket());
            final long headroom = Math.round(bucket.burst() * (bucket.cancelReserve() + BACKGROUND_HEADROOM));
            if (rule.cost() > bucket.burst() - headroom) {
                throw new IllegalArgumentException(
                        this.venue + " rule " + rule + " costs more than its bucket can ever hold for it");
            }
        }
    }

    private Bucket bucket(final String name) {
        for (final Bucket bucket : this.buckets) {
            if (bucket.name().equals(name)) {
                return bucket;
            }
        }
        throw new IllegalArgumentException(this.venue + " rate-limit rule names no bucket " + name);
    }
}
