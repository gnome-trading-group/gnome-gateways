package group.gnometrading.gateways.feed.probe;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.gateways.feed.GapReason;
import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectHost;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.TopLevels;
import group.gnometrading.gateways.feed.book.Book;
import group.gnometrading.gateways.feed.book.PriceLevels;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Checks that markets' books are right, over a path independent of their websockets: each market's book is fetched
 * from the venue's REST API and its best five levels a side compared with the ones its reader last published. A match
 * marks the market verified. A mismatch counts only if the market heard nothing from just before the fetch until
 * {@link #SETTLE_NANOS} after it: a book that moved meanwhile can't be compared with a snapshot from another moment, so
 * the fetch is simply retried. A counted mismatch is fetched again after {@link #REFETCH_DELAY_NANOS}, and a second
 * makes the market stale, which resyncs it. So the probe judges quiet books, the ones a lost update can leave wrong
 * unnoticed; busy books have their venue's own sequence, nonce or best-price checks.
 *
 * <p>Only markets whose venue sends changes are probed: one that sends the whole book every time can't drift. Every
 * market is probed every {@code intervalNanos}; one heard from and then quiet for longer than usual becomes SUSPECT and
 * is probed at once. Each source has its own budget of probes per minute.
 */
public final class BookProber implements GnomeAgent {

    static final long REFETCH_DELAY_NANOS = TimeUnit.SECONDS.toNanos(2);
    // Longer than a venue's message takes to arrive, so a change made as the fetch was answered has shown up.
    static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** How quiet a market may be before it is suspect, and how often every market is probed. */
    public record Settings(long intervalNanos, long silenceMinNanos, int silenceGapMultiple) {
        public static final Settings DEFAULT =
                new Settings(TimeUnit.MINUTES.toNanos(15), TimeUnit.SECONDS.toNanos(60), 20);
    }

    private static final class Target {
        final Subject subject;
        final BookProbeClient client;
        final BookProbeClient.Request request;
        final RateBudget budget;
        long nextProbeNanos;
        boolean mismatched;
        // A mismatch waiting to settle: when to judge it, and the market's last receive before its fetch.
        long settleAtNanos;
        long receivedBeforeFetch;

        Target(final Subject subject, final BookProbeClient client, final RateBudget budget, final long due) {
            this.subject = subject;
            this.client = client;
            this.request = client.prepare(subject);
            this.budget = budget;
            this.nextProbeNanos = due;
        }
    }

    private final Logger logger;
    private final EpochNanoClock clock;
    private final Settings settings;
    // Replaced whole on every add or remove and walked by index, so a pass allocates nothing.
    private volatile Target[] targets = new Target[0];
    private final Book fetched = new Book(4096);
    private final long[] prices = new long[TopLevels.LEVELS * 2];
    private final long[] sizes = new long[TopLevels.LEVELS * 2];
    private final int[] depths = new int[2];
    private volatile long probes;
    private volatile long mismatches;

    public BookProber(final Logger logger, final EpochNanoClock clock, final Settings settings) {
        this.logger = logger;
        this.clock = clock;
        this.settings = settings;
    }

    /** Any thread. The first probe is spread over one interval, so a new shard doesn't probe everything at once. */
    public synchronized void add(final Subject subject, final BookProbeClient client, final RateBudget budget) {
        final long spread = Math.floorMod(subject.key().hashCode(), Math.max(1, settings.intervalNanos()));
        final Target target = new Target(subject, client, budget, clock.nanoTime() + spread);
        final Target[] current = without(subject);
        final Target[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = target;
        targets = next;
    }

    public synchronized void remove(final Subject subject) {
        targets = without(subject);
    }

    private Target[] without(final Subject subject) {
        return Arrays.stream(targets).filter(t -> t.subject != subject).toArray(Target[]::new);
    }

    public long probes() {
        return probes;
    }

    public long mismatches() {
        return mismatches;
    }

    @Override
    public int doWork() {
        int work = 0;
        final long now = clock.nanoTime();
        final Target[] current = targets;
        for (int i = 0; i < current.length; i++) {
            work += check(current[i], now);
        }
        return work;
    }

    private int check(final Target target, final long now) {
        final Subject subject = target.subject;
        final SubjectState state = subject.state();
        if (state == SubjectState.LIVE && silent(subject, now) && subject.transition(state, SubjectState.SUSPECT)) {
            target.nextProbeNanos = now;
        }
        if (target.settleAtNanos != 0) {
            return now >= target.settleAtNanos ? settle(target, now) : 0;
        }
        if (!subject.state().trusted() || now < target.nextProbeNanos) {
            return 0;
        }
        if (target.budget.tryAcquire(1, 1, now) == 0) {
            return 0;
        }
        probe(target, now);
        return 1;
    }

    /**
     * Heard from since it was last found right, then quiet for longer than max(the minimum, a multiple of its usual
     * gap). A market found right and silent since is left to its regular probe: being quiet is no news about it.
     */
    private boolean silent(final Subject subject, final long now) {
        final long last = subject.lastReceiveNanos();
        if (last == 0 || subject.lastVerifiedNanos() >= last) {
            return false;
        }
        final long allowed =
                Math.max(settings.silenceMinNanos(), settings.silenceGapMultiple() * subject.meanGapNanos());
        return now - last > allowed;
    }

    private void probe(final Target target, final long now) {
        final Subject subject = target.subject;
        probes++;
        final long receivedBefore = subject.lastReceiveNanos();
        fetched.clear();
        final boolean answered;
        try {
            answered = target.client.fetch(target.request, fetched);
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Probe of %s failed: %s", subject.key(), e);
            target.nextProbeNanos = now + REFETCH_DELAY_NANOS * 15;
            return;
        }
        if (!answered) {
            target.nextProbeNanos = now + REFETCH_DELAY_NANOS * 15;
            return;
        }
        if (matches(subject)) {
            target.mismatched = false;
            target.nextProbeNanos = now + settings.intervalNanos();
            subject.verified(now);
            subject.transition(SubjectState.SUSPECT, SubjectState.LIVE);
            return;
        }
        target.receivedBeforeFetch = receivedBefore;
        target.settleAtNanos = now + SETTLE_NANOS;
    }

    /** A mismatch counts only if the market stayed quiet across its fetch and the settle after. */
    private int settle(final Target target, final long now) {
        target.settleAtNanos = 0;
        final Subject subject = target.subject;
        if (subject.lastReceiveNanos() != target.receivedBeforeFetch
                || !subject.state().trusted()) {
            target.nextProbeNanos = now + REFETCH_DELAY_NANOS;
            return 0;
        }
        mismatched(target, now);
        return 1;
    }

    private void mismatched(final Target target, final long now) {
        final Subject subject = target.subject;
        if (!target.mismatched) {
            target.mismatched = true;
            target.nextProbeNanos = now + REFETCH_DELAY_NANOS;
            return;
        }
        mismatches++;
        target.mismatched = false;
        target.nextProbeNanos = now + settings.intervalNanos();
        logger.logf(LogMessage.UNKNOWN_ERROR, "%s's book disagrees with the venue's twice; resyncing", subject.key());
        final SubjectHost host = subject.host();
        if (host != null) {
            host.requestStale(subject, GapReason.PROBE_MISMATCH);
        }
    }

    /** The best five levels a side, prices and sizes, as far as either book goes. */
    private boolean matches(final Subject subject) {
        subject.top().read(prices, sizes, depths);
        return sideMatches(fetched.bids(), 0) && sideMatches(fetched.asks(), 1);
    }

    private boolean sideMatches(final PriceLevels levels, final int side) {
        final int depth = Math.min(TopLevels.LEVELS, levels.depth());
        if (depth != depths[side]) {
            return false;
        }
        for (int i = 0; i < depth; i++) {
            final int at = side * TopLevels.LEVELS + i;
            if (levels.price(i) != prices[at] || levels.size(i) != sizes[at]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String roleName() {
        return "book-prober";
    }
}
