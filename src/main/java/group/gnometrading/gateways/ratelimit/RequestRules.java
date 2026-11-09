package group.gnometrading.gateways.ratelimit;

import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.strings.GnomeString;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A venue's rate-limit rules, compiled for matching without allocation: which bucket a request draws from, what it
 * costs and what class it is, by method and path (the query is ignored). The first rule that matches wins; the last
 * matches every request. Shared by every limiter of a venue's requests, so they agree on what each one costs.
 */
final class RequestRules {

    static final int NO_BUCKET = -1;

    private final HTTPMethod[] methods;
    private final byte[][] prefixes;
    private final byte[][] suffixes;
    private final int[] buckets;
    private final long[] costs;
    private final RequestClass[] types;

    RequestRules(final VenueRateLimits limits) {
        final List<VenueRateLimits.Bucket> specs = limits.buckets();
        final List<VenueRateLimits.Rule> rules = limits.rules();
        this.methods = new HTTPMethod[rules.size()];
        this.prefixes = new byte[rules.size()][];
        this.suffixes = new byte[rules.size()][];
        this.buckets = new int[rules.size()];
        this.costs = new long[rules.size()];
        this.types = new RequestClass[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            final VenueRateLimits.Rule rule = rules.get(i);
            this.methods[i] = rule.method();
            this.prefixes[i] = rule.prefix().getBytes(StandardCharsets.US_ASCII);
            this.suffixes[i] =
                    rule.suffix() == null ? new byte[0] : rule.suffix().getBytes(StandardCharsets.US_ASCII);
            this.buckets[i] = bucketIndex(specs, rule.bucket());
            this.costs[i] = rule.cost();
            this.types[i] = rule.type();
        }
    }

    /** The bucket rule {@code rule} draws from, or {@link #NO_BUCKET} if it is unlimited. */
    int bucket(final int rule) {
        return this.buckets[rule];
    }

    long cost(final int rule) {
        return this.costs[rule];
    }

    RequestClass type(final int rule) {
        return this.types[rule];
    }

    int size() {
        return this.types.length;
    }

    int classify(final HTTPMethod method, final String path) {
        int end = path.indexOf('?');
        end = end < 0 ? path.length() : end;
        for (int i = 0; i < this.methods.length; i++) {
            if (methodMatches(i, method) && matches(path, end, this.prefixes[i], this.suffixes[i])) {
                return i;
            }
        }
        throw new IllegalStateException("The last rule matches every request");
    }

    int classify(final HTTPMethod method, final GnomeString path) {
        int end = path.length();
        for (int i = 0; i < end; i++) {
            if (path.byteAt(i) == '?') {
                end = i;
                break;
            }
        }
        for (int i = 0; i < this.methods.length; i++) {
            if (methodMatches(i, method) && matches(path, end, this.prefixes[i], this.suffixes[i])) {
                return i;
            }
        }
        throw new IllegalStateException("The last rule matches every request");
    }

    private boolean methodMatches(final int rule, final HTTPMethod method) {
        return this.methods[rule] == null || this.methods[rule] == method;
    }

    private static boolean matches(final String path, final int end, final byte[] prefix, final byte[] suffix) {
        if (end < prefix.length + suffix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (path.charAt(i) != prefix[i]) {
                return false;
            }
        }
        final int suffixStart = end - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (path.charAt(suffixStart + i) != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(final GnomeString path, final int end, final byte[] prefix, final byte[] suffix) {
        if (end < prefix.length + suffix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (path.byteAt(i) != prefix[i]) {
                return false;
            }
        }
        final int suffixStart = end - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (path.byteAt(suffixStart + i) != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int bucketIndex(final List<VenueRateLimits.Bucket> buckets, final String name) {
        if (name == null) {
            return NO_BUCKET;
        }
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No rate-limit bucket " + name);
    }
}
