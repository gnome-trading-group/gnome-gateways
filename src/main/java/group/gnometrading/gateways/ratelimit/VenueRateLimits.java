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

    public static final String RETRY_AFTER = "Retry-After";
    public static final String POLY_REMAINING = "Poly-RateLimit-Remaining";
    public static final String POLY_RESET = "Poly-RateLimit-Reset";

    private static final String SHARE_KEY = "share";
    private static final String PER_SECOND_SUFFIX = ".per.second";

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

    /** A venue's published limits, as its own package describes them. */
    public static VenueRateLimits of(
            final String venue,
            final List<Bucket> buckets,
            final List<Rule> rules,
            final List<String> capturedHeaders) {
        return new VenueRateLimits(venue, buckets, rules, capturedHeaders, 1.0);
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
