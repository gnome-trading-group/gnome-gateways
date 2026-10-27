package group.gnometrading.gateways.feed;

import group.gnometrading.gateways.feed.probe.BookProbeClient;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The markets a process reads, by stream. Each stream names a listing; the listings of one venue market (a Kalshi
 * ticker's YES and NO, say) share one {@link Subject}, subscribed once, which publishes to each stream as it sees it.
 * Markets are placed on their source's {@link ConnectionGroup} and, where the source can be probed, on the
 * {@link BookProber}.
 *
 * <p>A market a connection quarantines is retried after {@link #RETRY_DELAYS_NANOS}, longer each time it fails again
 * within {@link #RECOVERED_NANOS}; its streams carry on flagged meanwhile.
 *
 * <p>Its methods are called from one thread, the runtime's; quarantines arrive from connections' threads and are
 * picked up by {@link #tick}.
 */
public final class Feeds {

    static final long[] RETRY_DELAYS_NANOS = {
        TimeUnit.MINUTES.toNanos(5), TimeUnit.MINUTES.toNanos(15), TimeUnit.MINUTES.toNanos(60)
    };
    static final long RECOVERED_NANOS = TimeUnit.MINUTES.toNanos(60);

    /** How a source's listings map to its markets, how deep its books are, and how it may be probed. */
    public record Source(
            String code,
            Function<String, SubjectRef> subjects,
            int levelsPerSide,
            BookProbeClient probe,
            RateBudget probeBudget) {}

    private static final class Market {
        final Source source;
        final ConnectionGroup<?> group;
        final Subject subject;
        long retryAtNanos;
        long lastRetryNanos;
        int failures;

        Market(final Source source, final ConnectionGroup<?> group, final Subject subject) {
            this.source = source;
            this.group = group;
            this.subject = subject;
        }
    }

    private final Logger logger;
    private final BookProber prober;
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, ConnectionGroup<?>> groups = new HashMap<>();
    // The same groups, walked by index so a tick allocates nothing.
    private final List<ConnectionGroup<?>> groupList = new ArrayList<>();
    // Keyed by source code, then venue market.
    private final Map<String, Map<String, Market>> markets = new HashMap<>();
    private final Map<Subject, Market> bySubject = new IdentityHashMap<>();
    private final Map<Integer, Market> streams = new HashMap<>();
    private final ConcurrentLinkedQueue<Subject> quarantined = new ConcurrentLinkedQueue<>();
    private final List<Market> waiting = new ArrayList<>();

    /** @param prober checks books against the venue's REST book; null where nothing is probed */
    public Feeds(final Logger logger, final BookProber prober) {
        this.logger = logger;
        this.prober = prober;
    }

    /** Adds a source and the connections its markets go on. */
    public <C> void addSource(final Source source, final int maxPerConnection, final ConnectionGroup.Opener<C> opener) {
        final ConnectionGroup<C> group = new ConnectionGroup<>(
                source.code(),
                maxPerConnection,
                opener,
                new FeedListener() {
                    @Override
                    public void onQuarantined(final Subject subject) {
                        quarantined.add(subject);
                    }
                },
                logger);
        sources.put(source.code(), source);
        groups.put(source.code(), group);
        groupList.add(group);
    }

    public boolean hasSource(final String code) {
        return sources.containsKey(code);
    }

    /** Starts publishing a listing's book to a stream's ring. */
    public void addStream(
            final int streamId,
            final String source,
            final String exchangeSecurityId,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final long now)
            throws Exception {
        if (streams.containsKey(streamId)) {
            return;
        }
        final Source feed = sources.get(source);
        if (feed == null) {
            throw new IllegalArgumentException("No feed for source " + source);
        }
        final SubjectRef ref = feed.subjects().apply(exchangeSecurityId);
        final StreamOutput output = new StreamOutput(streamId, ref.projection(), ring);
        final Map<String, Market> ofSource = markets.computeIfAbsent(source, code -> new HashMap<>());
        Market market = ofSource.get(ref.key());
        if (market == null) {
            market = new Market(feed, groups.get(source), new Subject(ref.key(), feed.levelsPerSide(), output));
            // Placed before it is recorded: if opening its connection fails, nothing is left half added, and a
            // later try starts afresh rather than finding a market that is on no connection.
            market.group.add(market.subject, now);
            ofSource.put(ref.key(), market);
            bySubject.put(market.subject, market);
            if (prober != null && feed.probe() != null && market.group.sendsChanges()) {
                prober.add(market.subject, feed.probe(), feed.probeBudget());
            }
        } else {
            market.subject.addOutput(output);
        }
        streams.put(streamId, market);
    }

    /** Stops a stream; its market is unsubscribed once no stream reads it. */
    public void removeStream(final int streamId, final long now) {
        final Market market = streams.remove(streamId);
        if (market == null) {
            return;
        }
        if (market.subject.removeOutput(streamId) > 0) {
            return;
        }
        markets.get(market.source.code()).remove(market.subject.key());
        bySubject.remove(market.subject);
        waiting.remove(market);
        market.group.remove(market.subject, now);
        if (prober != null) {
            prober.remove(market.subject);
        }
    }

    /** The market a stream reads, or null. */
    public Subject subject(final int streamId) {
        final Market market = streams.get(streamId);
        return market == null ? null : market.subject;
    }

    public List<ConnectionGroup<?>> groups() {
        return new ArrayList<>(groupList);
    }

    /**
     * Takes in quarantines, retries markets whose wait is over, and lets each group close and roll connections.
     * Allocates nothing unless one of those happens, as it runs in a trading session's JVM.
     */
    public void tick(final long now) throws Exception {
        Subject subject;
        while ((subject = quarantined.poll()) != null) {
            final Market market = bySubject.get(subject);
            if (market == null) {
                continue;
            }
            market.group.forget(subject, now);
            if (market.lastRetryNanos != 0 && now - market.lastRetryNanos >= RECOVERED_NANOS) {
                market.failures = 0;
            }
            final long delay = RETRY_DELAYS_NANOS[Math.min(market.failures, RETRY_DELAYS_NANOS.length - 1)];
            market.failures++;
            market.retryAtNanos = now + delay;
            waiting.add(market);
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s %s quarantined, retrying in %d minutes",
                    market.source.code(),
                    subject.key(),
                    TimeUnit.NANOSECONDS.toMinutes(delay));
        }
        for (int i = waiting.size() - 1; i >= 0; i--) {
            final Market market = waiting.get(i);
            if (now >= market.retryAtNanos) {
                waiting.remove(i);
                market.lastRetryNanos = now;
                market.group.add(market.subject, now);
            }
        }
        for (int i = 0; i < groupList.size(); i++) {
            groupList.get(i).tick(now);
        }
    }

    /** Closes every connection. */
    public void closeAll() {
        for (ConnectionGroup<?> group : groupList) {
            group.closeAll();
        }
    }
}
