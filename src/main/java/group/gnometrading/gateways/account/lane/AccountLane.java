package group.gnometrading.gateways.account.lane;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.account.admission.AdmissionSession;
import group.gnometrading.gateways.account.admission.FairQueue;
import group.gnometrading.gateways.account.admission.FairShareTrace;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.OrderKeys;
import group.gnometrading.gateways.orders.ReaderNotices;
import group.gnometrading.gateways.orders.SubmitPolicy;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPCompletion;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongArrayQueue;
import org.agrona.concurrent.EpochClock;

/**
 * One venue account's order path: every session's orders on the account, sent from one thread over several HTTP
 * connections at once, so a slow answer holds up nothing but its own order. Commands arrive on a ring, named by
 * session slot and counter; requests go out as connections and the account's budget allow, cancels first, then
 * resends and lookups that settle a submit, each in the order it arrived; then amends and new orders, shared between
 * sessions by their weights.
 *
 * <p>Amends and new orders share what the budget leaves after cancels and lookups by their sessions' weights, as
 * {@link FairQueue} decides; one that can't go out within its session's queueing limit is refused rather than sent
 * late.
 *
 * <p>A cancel the venue failed to answer (no answer, or a 5xx) is sent again a few times within a couple of seconds
 * before it is refused, since a cancel is safe to repeat.
 *
 * <p>Each order is settled by {@link SubmitPolicy}, as the per-listing writer's are: a submit with no clear answer is
 * resent where the venue deduplicates it, else looked up, and a lookup's miss is believed only once the last send is
 * old enough for the venue's reads to have caught up. The account's reader holds the same orders and reports
 * everything to the sessions; this thread tells it what only it sees (rejects, venue ids, amends, final fills)
 * through {@link ReaderNotices}, and it hands back the orders it has ended.
 *
 * <p>A cancel for an order not yet sent drops it unsent; one for an order whose submit is unsettled waits for it to
 * settle, and is then sent or, if the venue's id never came (from the answer, or from an event the reader passes
 * back), refused. A new order that can't go out within the queueing limit is refused rather than sent late.
 *
 * <p>An order whose submit's outcome was left unknown is looked for again every so often: found, it carries on; not
 * found, it is rejected. One never found either way, or that the venue can't look up, is given up on in the end: the
 * reader ends it with the fills it has seen, so it holds no slot for good.
 *
 * <p>Everything the sessions hear goes through the reader, so when its queue runs low (it is catching up after a
 * reconnect, or stalled), new orders and amends are held here in arrival order while cancels and answers carry on;
 * a cancel for a held new order drops it. Nothing here blocks or allocates once running, unless the reader's queue
 * fills even so.
 *
 * <p>An {@link Ownership} hears of every order the lane takes on before anything of it is sent, of the venue's id once
 * learned, and of its end, so that a restarted process knows which orders on the account were its own and whose; such
 * an order is {@linkplain #recover taken on again} unsent.
 *
 * @param <M> the venue's market
 */
public final class AccountLane<M> implements GnomeAgent {

    /**
     * The lane's clocks.
     *
     * @param readSettleNanos how far the venue's reads lag its writes: a lookup's miss counts only after this
     * @param responseTimeoutNanos how long an answer is waited for before the request counts as lost
     * @param maxQueueDelayNanos how long a new order may wait for a connection or the budget before it is refused
     * @param sweepIntervalNanos how often an order whose submit's outcome was left unknown is looked for again
     * @param giveUpNanos how long such an order is looked for before it is given up on
     * @param giveUpUnlookableNanos how long one the venue can't look up is waited on before it is given up on
     */
    public record Timing(
            long readSettleNanos,
            long responseTimeoutNanos,
            long maxQueueDelayNanos,
            long sweepIntervalNanos,
            long giveUpNanos,
            long giveUpUnlookableNanos) {

        public static Timing defaults() {
            return new Timing(
                    TimeUnit.SECONDS.toNanos(2),
                    TimeUnit.SECONDS.toNanos(5),
                    TimeUnit.SECONDS.toNanos(2),
                    TimeUnit.SECONDS.toNanos(30),
                    TimeUnit.MINUTES.toNanos(10),
                    TimeUnit.MINUTES.toNanos(2));
        }
    }

    /**
     * Told on the lane's thread of the orders it holds, for a record that outlives the process. Calls may not allocate,
     * and a call's {@code ctx} is valid only during it.
     */
    public interface Ownership {
        Ownership NONE = new Ownership() {};

        /** The lane has taken on an order and is about to send it, or has recovered one. */
        default void added(final OrderContext ctx) {}

        /** The venue's id for a held order is now known. */
        default void venueIdLearned(final OrderContext ctx) {}

        /** The venue took an amend: the order's price and size are now the amend's. */
        default void amended(final OrderContext ctx) {}

        /** The lane no longer holds the order: it ended, or was given up on. */
        default void ended(final OrderContext ctx) {}
    }

    /**
     * Told on the lane's thread what became of each request it was asked for, for the gateway's journal. Calls may not
     * allocate.
     */
    public interface Admissions {
        Admissions NONE = new Admissions() {};

        /**
         * @param queuedNanos how long the request waited before this decision
         * @param tokensLeft the tokens left after it in the bucket its request draws on; -1 if none or not known
         */
        default void decided(
                final long sessionSlot,
                final long clientOidCounter,
                final RequestClass type,
                final AdmissionAction action,
                final long queuedNanos,
                final long tokensLeft) {}

        /**
         * A request's answer came back, or none would.
         *
         * @param type the request's class; a batch's is that of its entries
         * @param roundTripNanos from its send to now
         * @param whole false when no whole answer came: the connection failed or the deadline passed
         */
        default void answered(final RequestClass type, final long roundTripNanos, final boolean whole) {}

        /**
         * A request was taken off the lane's ring to act on, {@code waitedNanos} after it was put there (held while the
         * lane parked it included); only for commands stamped {@link LaneCommand#enqueuedNanos}.
         */
        default void taken(final RequestClass type, final long waitedNanos) {}

        /**
         * A request went to the venue: {@code writeNanos} from deciding to send it to the socket write's return, its
         * encoding and signing included (for a batch, entries written earlier, only the send).
         */
        default void sent(final RequestClass type, final long writeNanos) {}

        /** Every connection was in use when the lane had a request to send; told once until one is free again. */
        default void connectionsBusy() {}

        /** A queued amend or new order was refused for waiting too long with no connection open, not for the budget. */
        default void refusedWithoutConnection() {}
    }

    /** The most sessions an account serves: a bound against a leak, far above what its budget can serve. */
    public static final int MAX_SESSIONS = FairQueue.MAX_SESSIONS;

    static final int MAX_CANCEL_RETRIES = 3;
    static final long CANCEL_RETRY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2);
    static final long CANCEL_RETRY_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private static final long MILLI_TOKENS = 1_000;
    private static final int KIND_BITS = 3;
    // Answers' tags name a batch, not an order, for these kinds.
    private static final int BATCH_SUBMIT = 5;
    private static final int BATCH_CANCEL = 6;
    // Batches on the wire at once: more than the lane has connections, which bound them first.
    private static final int BATCH_POOL = 64;
    private static final long KIND_MASK = (1 << KIND_BITS) - 1;
    private static final long INDEX_MASK = (1 << LaneOrder.INDEX_BITS) - 1;
    private static final int COMMANDS_PER_PASS = 64;
    private static final int PARKED_COMMANDS = 256;
    // How often idle connections gone stale are handed back for replacing, ahead of the requests that would find them
    // so.
    private static final long RETIRE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final String name;
    private final LaneVenue<M> venue;
    private final AsyncHTTPConnections connections;
    private final AccountBudget budget;
    private final SubmitPolicy policy;
    private final LongSupplier nanoClock;
    private Caps caps = Caps.NONE;
    private final EpochClock epochClock;
    private final Timing timing;

    private final ManyToOneRingBuffer<LaneCommand> commands;
    private final ManyToOneRingBuffer<OrderContext> releasedOrderQueue;
    private final ManyToOneRingBuffer<OrderContext> noticeQueue;
    private final ReaderNotices notices;
    // Room kept in the reader's queue for what cancels and answers must tell it.
    private final int noticeReserve;
    // New orders and amends held while the reader's queue is low, in arrival order.
    private final LaneCommand[] parked;
    private int parkedHead;
    private int parkedCount;

    private final LaneOrder[] orders;
    private final int[] free;
    private int freeCount;
    private final OrderTable<LaneOrder> table;
    // By correlation id, which the reader and the venue's events know an order by: two orders must never share one.
    private final Long2ObjectHashMap<LaneOrder> byCorrelation;

    private final LongArrayQueue cancels;
    // Resends and lookups apart: they draw on different budgets, and one blocked mustn't hold up the other.
    private final LongArrayQueue resends;
    private final LongArrayQueue lookups;
    // Cancels waiting to be sent again after the venue failed to answer them; scanned each pass, as few ever wait.
    private final long[] cancelRetries;
    private int cancelRetryCount;

    private final FairQueue<LaneOrder> fair;
    // What the request just sent cost, in thousandths of a token.
    private long lastCost;

    // Submits, or cancels, the venue takes several to a request; none if it takes them one at a time.
    private final int maxBatch;
    private final LaneBatch[] batches;
    private final int[] freeBatches;
    private int freeBatchCount;
    // A batch of submits being filled from sessions' turns, and the connection it goes on.
    private LaneBatch filling;
    private int fillingConnection;
    // Whether serving sessions ran out of connections or budget.
    private boolean blocked;
    // Orders waiting out the venue's read lag before a lookup; scanned each pass, as few ever wait.
    private final long[] waiting;
    private int waitingCount;
    // Orders whose submit's outcome was left unknown; scanned each pass, as few ever are.
    private final long[] unknowns;
    private int unknownCount;
    // A session's orders, gathered before any is cancelled: cancelling one can move the others in the table.
    private final long[] sessionOrders;

    private final RequestShape shape;
    private final LaneVenue.AmendAnswer amendAnswer = new LaneVenue.AmendAnswer();
    // Held once, so the loop doesn't create a method reference on every pass.
    private final MessageConsumer<LaneCommand> commandHandler = this::onArrival;
    private final MessageConsumer<OrderContext> releaseHandler = this::onReleased;
    private int handledThisPass;
    private Ownership ownership = Ownership.NONE;
    private Admissions admissions = Admissions.NONE;
    // By connection, which carries one request at a time: when its request went, and its class, for its round trip.
    private final long[] sentAtNanos;
    private final RequestClass[] sentTypes;
    private boolean connectionsWereBusy;
    // Whether the last request held back was held by the budget rather than for want of a connection.
    private boolean heldByBudget;
    private long retireAtNanos;

    /**
     * @param maxOrders the most orders the account may have working
     * @param maxBodyLength the largest submit body a venue's orders need
     * @param maxPathLength the longest path a venue's requests need
     * @param commands where sessions' commands arrive
     * @param noticeQueue where each order is handed to the reader before it is sent, and the reader then told what
     *     only this thread sees of it
     * @param releasedOrderQueue where the reader hands back the orders it has ended, and venue ids it learned; at least
     *     twice {@code maxOrders}
     */
    public AccountLane(
            final String name,
            final LaneVenue<M> venue,
            final AsyncHTTPConnections connections,
            final AccountBudget budget,
            final Timing timing,
            final LongSupplier nanoClock,
            final EpochClock epochClock,
            final int maxOrders,
            final int maxBodyLength,
            final int maxPathLength,
            final ManyToOneRingBuffer<LaneCommand> commands,
            final ManyToOneRingBuffer<OrderContext> noticeQueue,
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue) {
        if (maxOrders > INDEX_MASK) {
            throw new IllegalArgumentException("At most " + INDEX_MASK + " orders per lane");
        }
        this.name = name;
        this.venue = venue;
        this.connections = connections;
        this.sentAtNanos = new long[connections.size()];
        this.sentTypes = new RequestClass[connections.size()];
        this.budget = budget;
        this.policy = new SubmitPolicy(timing.readSettleNanos());
        this.nanoClock = nanoClock;
        this.epochClock = epochClock;
        this.timing = timing;
        this.commands = commands;
        this.releasedOrderQueue = releasedOrderQueue;
        this.noticeQueue = noticeQueue;
        this.notices = new ReaderNotices(noticeQueue);
        this.noticeReserve = noticeQueue.remaining() / 4;
        this.parked = new LaneCommand[PARKED_COMMANDS];
        for (int i = 0; i < PARKED_COMMANDS; i++) {
            this.parked[i] = new LaneCommand();
        }
        this.orders = new LaneOrder[maxOrders];
        this.free = new int[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            this.orders[i] = new LaneOrder(i, maxBodyLength);
            this.free[i] = maxOrders - 1 - i;
        }
        this.freeCount = maxOrders;
        this.table = new OrderTable<>(maxOrders);
        this.byCorrelation = new Long2ObjectHashMap<>(maxOrders * 2, 0.5f);
        // Each holds at most every order once, plus stale entries for orders since ended.
        this.cancels = new LongArrayQueue(2 * maxOrders, -1);
        this.resends = new LongArrayQueue(2 * maxOrders, -1);
        this.lookups = new LongArrayQueue(2 * maxOrders, -1);
        this.cancelRetries = new long[maxOrders];
        this.fair = new FairQueue<>(
                this.orders,
                timing.maxQueueDelayNanos(),
                nanoClock,
                this::expire,
                Math.round(budget.newOrderTokensPerSecond() * MILLI_TOKENS));
        this.maxBatch = venue.maxBatch();
        final int batchPool = this.maxBatch > 1 ? BATCH_POOL : 0;
        this.batches = new LaneBatch[batchPool];
        this.freeBatches = new int[batchPool];
        for (int i = 0; i < batchPool; i++) {
            this.batches[i] = new LaneBatch(i, this.maxBatch);
            this.freeBatches[i] = batchPool - 1 - i;
        }
        this.freeBatchCount = batchPool;
        this.waiting = new long[maxOrders];
        this.unknowns = new long[maxOrders];
        this.sessionOrders = new long[maxOrders];
        this.shape = new RequestShape(maxPathLength);
    }

    @Override
    public int doWork() {
        retireIdleConnections();
        int work = drainReleased();
        work += pollAnswers();
        work += wakeWaiting();
        work += wakeCancelRetries();
        work += sweepUnknowns();
        work += drainCommands();
        work += sendQueued();
        return work;
    }

    /**
     * Hands back, one at a time, connections idle past half the time they may be trusted, so the connector has replaced
     * them before an order needs one: an order that finds them all stale waits for a new connect, longer than it may
     * wait, and on a quiet account every connection goes stale between orders. One per pass replaces all of them in
     * {@code connections x} {@link #RETIRE_INTERVAL_NANOS}, well inside half their max idle.
     */
    private void retireIdleConnections() {
        final long now = this.nanoClock.getAsLong();
        if (now - this.retireAtNanos >= 0) {
            this.retireAtNanos = now + RETIRE_INTERVAL_NANOS;
            this.connections.retireIdle(now, this.connections.maxIdleNanos() / 2);
        }
    }

    /** Orders working on the account, for status and tests. */
    public int workingOrders() {
        return this.table.size();
    }

    /**
     * Every order the lane holds and where it stands, for a test or a stuck order's post-mortem. Reads the lane's state
     * from another thread without its lock, so only once the lane is quiet; allocates.
     */
    public String describeWorking() {
        final long now = this.nanoClock.getAsLong();
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < this.table.capacity(); i++) {
            final LaneOrder order = this.table.at(i);
            if (order == null) {
                continue;
            }
            order.describe(text, now);
            text.append('\n');
        }
        return text.toString();
    }

    /** Amends and new orders waiting for a connection or the budget, for status and tests. */
    public int queuedRequests() {
        return this.fair.queuedRequests();
    }

    /** Set before the lane's thread starts. */
    public void ownership(final Ownership ownership) {
        this.ownership = ownership;
    }

    /**
     * Records every step of the fair share from now on, up to {@code capacity} of them, for a post-mortem. Before the
     * lane's thread starts: it reads the trace without a barrier.
     */
    public FairShareTrace traceFairShare(final int capacity) {
        return this.fair.trace(capacity);
    }

    public void admissions(final Admissions admissions) {
        this.admissions = admissions;
    }

    /**
     * Caps on what sessions may send: orders one session may hold at once and new orders it may send a second, its
     * defaults until the gateway grants it its own ({@link LaneCommand#SESSION}), and orders the whole account may
     * hold. 0 is no cap. A new order past one is refused {@code SESSION_CAP}; cancels and amends are never capped.
     */
    public record Caps(int sessionMaxOpenOrders, int sessionMaxNewPerSecond, int accountMaxOpenOrders) {
        public static final Caps NONE = new Caps(0, 0, 0);
    }

    /** Before the lane starts. */
    public void caps(final Caps caps) {
        this.caps = caps;
        this.fair.defaultCaps(caps.sessionMaxOpenOrders(), caps.sessionMaxNewPerSecond());
    }

    /**
     * Takes on again an order a process before this one sent, as its record of the account's orders had it: held under
     * its session's slot and counter like any other, never sent again. With the venue's id it can be cancelled at
     * once; without it, its submit's outcome is unknown, so it is looked for once the venue's reads have settled, and then
     * as any unknown order is.
     * Hand the same order to the account's reader, {@link group.gnometrading.gateways.account.reader.AccountReader#recover}. On the lane's thread, or before it
     * starts.
     *
     * @param src the order as recorded: its keys, terms, correlation id and the venue's id if known
     * @return false if it can't be held: its market isn't loaded, the account is full, or it is held already
     */
    public boolean recover(final OrderContext src) {
        final M market = this.venue.market(src.exchangeId, src.securityId);
        final long correlationKey = OrderKeys.of(src);
        if (market == null
                || this.freeCount == 0
                || this.table.get(src.sessionSlot, src.clientOidCounter) != null
                || this.byCorrelation.containsKey(correlationKey)) {
            return false;
        }
        final LaneOrder order = this.orders[this.free[--this.freeCount]];
        order.reset();
        order.market = market;
        final OrderContext ctx = order.ctx;
        ctx.copyFrom(src);
        ctx.registration = false;
        ctx.endsRegistered = false;
        hold(order, correlationKey);
        order.submitState = LaneOrder.SETTLED;
        this.ownership.added(ctx);
        final long now = this.nanoClock.getAsLong();
        if (this.venue.venueIdKnown(ctx)) {
            order.accepted = true;
        } else {
            // When it was last sent isn't known, and a sweep believes a miss: first looked for once the venue's reads
            // have had time to catch up even with a send just before the restart.
            order.unknownSinceNanos = now;
            order.nextSweepNanos = now + this.timing.readSettleNanos();
            this.unknowns[this.unknownCount++] = order.entry();
        }
        return true;
    }

    /**
     * Hands every order held to {@code visitor}, for rewriting the record of them; doesn't allocate but visits the whole
     * table. On the lane's thread, as from an {@link Ownership} call: the order {@link Ownership#added} names isn't
     * held yet, and the one {@link Ownership#ended} names no longer is.
     */
    public void forEachHeld(final Consumer<OrderContext> visitor) {
        for (int i = 0; i < this.table.capacity(); i++) {
            final LaneOrder order = this.table.at(i);
            if (order != null) {
                visitor.accept(order.ctx);
            }
        }
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return this.name + "-lane";
    }

    // ----- commands -----

    /** Held commands first, while the reader has room; then new ones, never more than could be held. */
    private int drainCommands() {
        this.handledThisPass = 0;
        while (this.parkedCount > 0 && readerHasRoom()) {
            final LaneCommand command = this.parked[this.parkedHead];
            this.parkedHead = (this.parkedHead + 1) % PARKED_COMMANDS;
            this.parkedCount--;
            this.handledThisPass++;
            if (command.kind != 0) {
                onCommand(command);
            }
        }
        final int limit = Math.min(COMMANDS_PER_PASS, PARKED_COMMANDS - this.parkedCount);
        if (limit > 0) {
            this.commands.read(this.commandHandler, limit);
        }
        return this.handledThisPass;
    }

    private boolean readerHasRoom() {
        return this.noticeQueue.remaining() > this.noticeReserve;
    }

    private void onArrival(final LaneCommand command) {
        this.handledThisPass++;
        if (command.kind != LaneCommand.CANCEL
                && command.kind != LaneCommand.CANCEL_SESSION
                && command.kind != LaneCommand.CANCEL_ORDERS
                && (this.parkedCount > 0 || !readerHasRoom())) {
            final int slot = (this.parkedHead + this.parkedCount) % PARKED_COMMANDS;
            this.parked[slot].copyFrom(command);
            this.parkedCount++;
            return;
        }
        onCommand(command);
    }

    private void onCommand(final LaneCommand command) {
        if (command.enqueuedNanos != 0) {
            tellTaken(command);
        }
        switch (command.kind) {
            case LaneCommand.NEW -> onNew(command);
            case LaneCommand.CANCEL -> onCancel(command);
            case LaneCommand.AMEND -> onAmend(command);
            case LaneCommand.CANCEL_SESSION -> onCancelSession(command.sessionSlot);
            case LaneCommand.CANCEL_ORDERS -> cancelOrders(command.sessionSlot, command.exchangeId, command.securityId);
            case LaneCommand.SESSION -> onSession(command);
            case LaneCommand.SESSION_ENDED -> this.fair.ended(command.sessionSlot);
            default -> this.notices.rejectUnheld(
                    command.sessionSlot,
                    command.clientOidCounter,
                    command.clientOidStrategyId,
                    command.exchangeId,
                    command.securityId,
                    RejectReason.GATEWAY_REJECTED);
        }
    }

    private void tellTaken(final LaneCommand command) {
        final RequestClass type =
                switch (command.kind) {
                    case LaneCommand.NEW -> RequestClass.NEW;
                    case LaneCommand.CANCEL -> RequestClass.CANCEL;
                    case LaneCommand.AMEND -> RequestClass.AMEND;
                    default -> null;
                };
        if (type != null) {
            this.admissions.taken(type, this.nanoClock.getAsLong() - command.enqueuedNanos);
        }
    }

    /** A new order still held here: dropped, and reported cancelled, as nothing reached the venue. */
    private boolean cancelParked(final LaneCommand cancel) {
        for (int i = 0; i < this.parkedCount; i++) {
            final LaneCommand command = this.parked[(this.parkedHead + i) % PARKED_COMMANDS];
            if (command.kind == LaneCommand.NEW
                    && command.sessionSlot == cancel.sessionSlot
                    && command.clientOidCounter == cancel.clientOidCounter) {
                command.kind = 0;
                this.notices.canceledUnheld(
                        command.sessionSlot,
                        command.clientOidCounter,
                        command.clientOidStrategyId,
                        command.exchangeId,
                        command.securityId);
                this.admissions.decided(
                        command.sessionSlot,
                        command.clientOidCounter,
                        RequestClass.NEW,
                        AdmissionAction.DROPPED_CONFLATED,
                        0,
                        -1);
                return true;
            }
        }
        return false;
    }

    private void onSession(final LaneCommand command) {
        this.fair.configure(command.sessionSlot, command.weight, command.maxQueueNanos);
        this.fair.caps(command.sessionSlot, command.maxOpenOrders, command.maxNewPerSecond);
    }

    private void onNew(final LaneCommand command) {
        final RejectReason refusal = refusal(command);
        if (refusal != null) {
            this.notices.rejectUnheld(
                    command.sessionSlot,
                    command.clientOidCounter,
                    command.clientOidStrategyId,
                    command.exchangeId,
                    command.securityId,
                    refusal);
            return;
        }
        final M market = this.venue.market(command.exchangeId, command.securityId);
        if (market == null
                || this.freeCount == 0
                || this.table.get(command.sessionSlot, command.clientOidCounter) != null) {
            // An unloaded market, a full account, or a counter the session already used: nothing is sent.
            this.notices.rejectUnheld(
                    command.sessionSlot,
                    command.clientOidCounter,
                    command.clientOidStrategyId,
                    command.exchangeId,
                    command.securityId,
                    RejectReason.GATEWAY_REJECTED);
            return;
        }
        final LaneOrder order = this.orders[this.free[--this.freeCount]];
        order.reset();
        order.market = market;
        final OrderContext ctx = order.ctx;
        ctx.sessionSlot = command.sessionSlot;
        ctx.clientOidCounter = command.clientOidCounter;
        ctx.clientOidStrategyId = command.clientOidStrategyId;
        ctx.exchangeId = command.exchangeId;
        ctx.securityId = command.securityId;
        ctx.side = command.side;
        ctx.limitPrice = command.price;
        ctx.originalQty = command.size;
        ctx.leavesQty = command.size;

        final int bodyLength = this.venue.prepare(command, market, ctx, order.body, this.epochClock.time());
        if (bodyLength < 0) {
            rejectAndFree(order, ctx.rejectReason != null ? ctx.rejectReason : RejectReason.GATEWAY_REJECTED);
            return;
        }
        order.bodyLength = bodyLength;
        final long correlationKey = OrderKeys.of(ctx);
        if (this.byCorrelation.containsKey(correlationKey)) {
            // Its events couldn't be told from the other order's.
            rejectAndFree(order, RejectReason.GATEWAY_REJECTED);
            return;
        }
        // Recorded and handed to the reader before anything is sent, so events that beat the submit's answer, or arrive
        // when that answer is lost, still find the order, and a restart still knows it.
        this.ownership.added(ctx);
        this.notices.register(ctx);
        hold(order, correlationKey);
        this.fair.enqueue(order, LaneOrder.SUBMIT);
    }

    private void onCancel(final LaneCommand command) {
        final LaneOrder order = held(command);
        if (order == null && cancelParked(command)) {
            return;
        }
        if (order == null) {
            this.notices.rejectUnknownOrder(
                    command.sessionSlot,
                    command.clientOidCounter,
                    command.clientOidStrategyId,
                    command.exchangeId,
                    command.securityId);
            return;
        }
        // Never sent: dropped unsent, and reported cancelled; an amend waiting on it is refused first.
        cancelHeld(order);
    }

    /**
     * Cancels each of the session's orders as a cancel of it would, and drops its new orders still held here. An amend
     * still held here is answered as it comes off the queue, refused, as its order is being cancelled or is gone.
     */
    private void onCancelSession(final long slot) {
        // A new order of the session's may be on the ring behind this, sent before the gateway stopped taking them.
        this.fair.fence(slot);
        cancelOrders(slot, 0, 0);
    }

    /** The session's working orders, on one listing or (exchange and security both 0) on all, each cancelled. */
    private void cancelOrders(final long slot, final int exchangeId, final long securityId) {
        dropParked(slot, exchangeId, securityId);
        int count = 0;
        for (int i = 0; i < this.table.capacity(); i++) {
            final LaneOrder order = this.table.at(i);
            if (order != null
                    && covered(
                            order.ctx.sessionSlot,
                            order.ctx.exchangeId,
                            order.ctx.securityId,
                            slot,
                            exchangeId,
                            securityId)) {
                this.sessionOrders[count++] = order.entry();
            }
        }
        for (int i = 0; i < count; i++) {
            final LaneOrder order = live(this.sessionOrders[i]);
            if (order != null && order.held && !order.cancelWanted) {
                cancelHeld(order);
            }
        }
    }

    /** Whether an order of {@code orderSlot}'s on its listing is the session's, on the listing or (0, 0) on any. */
    private static boolean covered(
            final long orderSlot,
            final int orderExchangeId,
            final long orderSecurityId,
            final long slot,
            final int exchangeId,
            final long securityId) {
        return orderSlot == slot
                && ((exchangeId == 0 && securityId == 0)
                        || (orderExchangeId == exchangeId && orderSecurityId == securityId));
    }

    private void dropParked(final long slot, final int exchangeId, final long securityId) {
        for (int i = 0; i < this.parkedCount; i++) {
            final LaneCommand parked = this.parked[(this.parkedHead + i) % PARKED_COMMANDS];
            if (parked.kind == LaneCommand.NEW
                    && covered(
                            parked.sessionSlot, parked.exchangeId, parked.securityId, slot, exchangeId, securityId)) {
                parked.kind = 0;
                this.notices.canceledUnheld(
                        parked.sessionSlot,
                        parked.clientOidCounter,
                        parked.clientOidStrategyId,
                        parked.exchangeId,
                        parked.securityId);
                this.admissions.decided(
                        parked.sessionSlot,
                        parked.clientOidCounter,
                        RequestClass.NEW,
                        AdmissionAction.DROPPED_CONFLATED,
                        0,
                        -1);
            }
        }
    }

    /** As a cancel of it: one never sent is dropped unsent, any other is cancelled once it can be. */
    private void cancelHeld(final LaneOrder order) {
        if (order.submitState == LaneOrder.QUEUED && order.inFlight == LaneOrder.NONE) {
            refuseWanted(order);
            this.notices.canceledUnsent(order.ctx);
            decided(order, RequestClass.NEW, AdmissionAction.DROPPED_CONFLATED, order.queuedAtNanos(), -1);
            free(order);
            return;
        }
        if (order.submitState != LaneOrder.SETTLED) {
            decided(order, RequestClass.CANCEL, AdmissionAction.CANCEL_HELD_UNTIL_ACK, 0, -1);
        }
        order.cancelWanted = true;
        serveWanted(order);
    }

    private void onAmend(final LaneCommand command) {
        final LaneOrder order = held(command);
        if (order == null) {
            this.notices.rejectUnknownOrder(
                    command.sessionSlot,
                    command.clientOidCounter,
                    command.clientOidStrategyId,
                    command.exchangeId,
                    command.securityId);
            return;
        }
        @SuppressWarnings("unchecked")
        final M market = (M) order.market;
        if (order.amendWanted
                || order.cancelWanted
                || !this.venue.canAmend(order.ctx, market, command.price, command.size)) {
            this.notices.cancelReject(order.ctx, RejectReason.GATEWAY_REJECTED);
            return;
        }
        if (this.fair.session(order.ctx.sessionSlot) == null) {
            this.notices.cancelReject(order.ctx, RejectReason.SESSION_CAP);
            return;
        }
        order.amendWanted = true;
        order.amendPrice = command.price;
        order.amendSize = command.size;
        order.amendPostOnly = command.postOnly;
        serveWanted(order);
    }

    private LaneOrder held(final LaneCommand command) {
        return this.table.get(command.sessionSlot, command.clientOidCounter);
    }

    /**
     * Sends what was asked of an order once nothing else of it is on the wire and its submit has settled; refuses it
     * if the submit settled without the venue's id, which a cancel or amend needs.
     */
    private void serveWanted(final LaneOrder order) {
        if (order.inFlight != LaneOrder.NONE || order.submitState != LaneOrder.SETTLED) {
            return;
        }
        if (order.cancelWanted) {
            if (order.cancelRetryAtNanos != 0) {
                // Sent again once its backoff is over.
                return;
            }
            if (this.venue.venueIdKnown(order.ctx)) {
                order.cancelQueuedAtNanos = this.nanoClock.getAsLong();
                this.cancels.offerLong(order.entry());
            } else {
                order.cancelWanted = false;
                this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN);
            }
            if (order.amendWanted) {
                order.amendWanted = false;
                this.fair.unqueue(order);
                this.notices.cancelReject(order.ctx, RejectReason.GATEWAY_REJECTED);
            }
        } else if (order.amendWanted && !order.queued()) {
            if (this.venue.venueIdKnown(order.ctx)) {
                this.fair.enqueue(order, LaneOrder.AMEND);
            } else {
                order.amendWanted = false;
                this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN);
            }
        }
    }

    // ----- sending -----

    private int sendQueued() {
        int sent = drainQueue(this.cancels, LaneOrder.CANCEL);
        sent += drainQueue(this.resends, LaneOrder.SUBMIT);
        sent += drainQueue(this.lookups, LaneOrder.LOOKUP);
        sent += serveSessions();
        if (this.fair.anyQueued()) {
            this.fair.expireDue();
        }
        return sent;
    }

    /**
     * Sends queued amends and new orders, each time from the session charged least so far, until connections or the
     * budget run out or nothing is queued. Then sends any batch it filled.
     */
    private int serveSessions() {
        this.blocked = false;
        int sent = 0;
        while (!this.blocked) {
            final AdmissionSession session = this.fair.leastServed();
            if (session == null) {
                break;
            }
            final LaneOrder order = this.orders[session.head()];
            final int kind = order.queuedKind();
            if (order.stillQueued(kind)) {
                sent += serveOne(order, kind, session);
            } else {
                this.fair.unqueue(order);
            }
        }
        if (this.filling != null) {
            sent += sendFilling();
        }
        return sent;
    }

    private int serveOne(final LaneOrder order, final int kind, final AdmissionSession session) {
        if (kind == LaneOrder.SUBMIT && batchesSubmits()) {
            if (this.filling == null && !openSubmitBatch(order)) {
                this.blocked = true;
                return 0;
            }
            addToFilling(order, session);
            return this.filling.count == this.maxBatch ? sendFilling() : 0;
        }
        // A batch being filled goes first: it holds a connection and tokens already.
        int sent = this.filling != null ? sendFilling() : 0;
        final int outcome = trySend(order, kind);
        if (outcome == BLOCKED) {
            this.blocked = true;
        } else if (outcome == TAKEN) {
            this.fair.charge(session, this.lastCost);
            this.fair.unqueue(order);
            sent++;
        }
        return sent;
    }

    /** A queued amend or new order that waited too long: refused, or dropped if it no longer stands. */
    private void expire(final LaneOrder order) {
        final int kind = order.queuedKind();
        this.fair.unqueue(order);
        if (!order.stillQueued(kind)) {
            return;
        }
        final RejectReason reason = this.heldByBudget ? RejectReason.RATE_LIMITED : RejectReason.GATEWAY_DEGRADED;
        if (!this.heldByBudget) {
            this.admissions.refusedWithoutConnection();
        }
        if (kind == LaneOrder.SUBMIT) {
            decided(order, RequestClass.NEW, AdmissionAction.REJECTED_RATE_LIMITED, order.queuedAtNanos(), -1);
            rejectAndFree(order, reason);
        } else {
            decided(order, RequestClass.AMEND, AdmissionAction.REJECTED_RATE_LIMITED, order.queuedAtNanos(), -1);
            order.amendWanted = false;
            this.notices.cancelReject(order.ctx, reason);
        }
    }

    // ----- sessions' lists -----

    private void decided(
            final LaneOrder order,
            final RequestClass type,
            final AdmissionAction action,
            final long sinceNanos,
            final long tokensLeft) {
        this.admissions.decided(
                order.ctx.sessionSlot,
                order.ctx.clientOidCounter,
                type,
                action,
                sinceNanos == 0 ? 0 : this.nanoClock.getAsLong() - sinceNanos,
                tokensLeft);
    }

    // ----- batches -----

    /** Whether submits go in batches now: the venue takes them, and one is being filled or more than one waits. */
    private boolean batchesSubmits() {
        return this.maxBatch > 1 && (this.filling != null || this.fair.queuedSubmits() > 1);
    }

    /** Takes a connection and the budget for a batch of submits headed by {@code first}; false if either is short. */
    private boolean openSubmitBatch(final LaneOrder first) {
        if (this.freeBatchCount == 0) {
            return false;
        }
        final int connection = acquireConnection();
        if (connection == AsyncHTTPConnections.NONE) {
            this.heldByBudget = false;
            return false;
        }
        this.venue.batchSubmitShape(this.shape);
        final int rule = this.budget.classify(this.shape.method, this.shape.path);
        if (!this.budget.tryAcquire(rule, this.nanoClock.getAsLong())) {
            this.heldByBudget = true;
            tellHeld(first, rule);
            return false;
        }
        this.filling = takeBatch(BATCH_SUBMIT, rule);
        this.fillingConnection = connection;
        this.fair.batchOpened();
        return true;
    }

    /**
     * Charged a full batch's share of the request while the batch fills; {@link #sendFilling} makes it up to the share
     * of the batch as sent.
     */
    private void addToFilling(final LaneOrder order, final AdmissionSession session) {
        final LaneBatch batch = this.filling;
        final int i = batch.count++;
        batch.entries[i] = order.entry();
        batch.ctxs[i] = order.ctx;
        batch.bodies[i] = order.body;
        batch.bodyLengths[i] = order.bodyLength;
        batch.chargedTo[i] = session;
        batch.charged[i] = this.budget.cost(batch.rule) * MILLI_TOKENS / this.maxBatch;
        this.fair.charge(session, batch.charged[i]);
        this.fair.unqueue(order);
        order.inFlight = LaneOrder.SUBMIT;
        order.inFlightRule = batch.rule;
    }

    private int sendFilling() {
        final LaneBatch batch = this.filling;
        this.filling = null;
        final long share = this.budget.cost(batch.rule) * MILLI_TOKENS / batch.count;
        for (int i = 0; i < batch.count; i++) {
            this.fair.topUp(batch.chargedTo[i], batch.ctxs[i].sessionSlot, share - batch.charged[i]);
        }
        this.venue.batchSubmitShape(this.shape);
        final HTTPRequestWriter request = this.connections
                .request(this.fillingConnection, this.shape.method)
                .path(this.shape.path);
        this.venue.writeBatchSubmit(
                batch.ctxs, batch.bodies, batch.bodyLengths, batch.count, this.shape, request, this.epochClock.time());
        return sendBatch(batch, this.fillingConnection);
    }

    /** Batches the cancels at the head of the queue, as many to a request as the venue takes. */
    private int drainCancelBatches() {
        int sent = 0;
        while (!this.cancels.isEmpty()) {
            final LaneOrder head = live(this.cancels.peekLong());
            if (head == null || head.inFlight != LaneOrder.NONE || stale(head, LaneOrder.CANCEL)) {
                this.cancels.pollLong();
                continue;
            }
            final LaneBatch batch = openCancelBatch();
            if (batch == null) {
                return sent;
            }
            fillCancelBatch(batch);
            final HTTPRequestWriter request = this.connections
                    .request(this.fillingConnection, this.shape.method)
                    .path(this.shape.path);
            this.venue.writeBatchCancel(batch.ctxs, batch.count, this.shape, request, this.epochClock.time());
            sent += sendBatch(batch, this.fillingConnection);
        }
        return sent;
    }

    /** A batch of cancels with a connection and the budget for it; null if either is short. */
    private LaneBatch openCancelBatch() {
        if (this.freeBatchCount == 0) {
            return null;
        }
        final int connection = acquireConnection();
        if (connection == AsyncHTTPConnections.NONE) {
            this.heldByBudget = false;
            return null;
        }
        this.venue.batchCancelShape(this.shape);
        final int rule = this.budget.classify(this.shape.method, this.shape.path);
        if (!this.budget.tryAcquire(rule, this.nanoClock.getAsLong())) {
            this.heldByBudget = true;
            return null;
        }
        this.fillingConnection = connection;
        return takeBatch(BATCH_CANCEL, rule);
    }

    private void fillCancelBatch(final LaneBatch batch) {
        while (!this.cancels.isEmpty() && batch.count < this.maxBatch) {
            final LaneOrder order = live(this.cancels.pollLong());
            if (order == null || order.inFlight != LaneOrder.NONE || stale(order, LaneOrder.CANCEL)) {
                continue;
            }
            final int i = batch.count++;
            batch.entries[i] = order.entry();
            batch.ctxs[i] = order.ctx;
            order.inFlight = LaneOrder.CANCEL;
            order.inFlightRule = batch.rule;
        }
    }

    private LaneBatch takeBatch(final int kind, final int rule) {
        final LaneBatch batch = this.batches[this.freeBatches[--this.freeBatchCount]];
        batch.kind = kind;
        batch.rule = rule;
        return batch;
    }

    private void releaseBatch(final LaneBatch batch) {
        batch.reset();
        this.freeBatches[this.freeBatchCount++] = batch.index;
    }

    /** Sends a written batch: each entry is told sent, and a send that failed at once is answered as no answer. */
    private int sendBatch(final LaneBatch batch, final int connection) {
        final long now = this.nanoClock.getAsLong();
        sending(connection, batch.kind == BATCH_SUBMIT ? RequestClass.NEW : RequestClass.CANCEL, now);
        final int result = this.connections.send(
                connection, ((long) batch.index << KIND_BITS) | batch.kind, now + this.timing.responseTimeoutNanos());
        if (result == AsyncHTTPConnections.NOT_SENT) {
            // Its connection was found dead before a byte went: every entry goes again.
            putBackAll(batch, LaneVenue.NO_FAILED_ENTRY);
            releaseBatch(batch);
            return 0;
        }
        this.admissions.sent(
                batch.kind == BATCH_SUBMIT ? RequestClass.NEW : RequestClass.CANCEL, this.nanoClock.getAsLong() - now);
        final boolean submits = batch.kind == BATCH_SUBMIT;
        for (int i = 0; i < batch.count; i++) {
            final LaneOrder order = live(batch.entries[i]);
            if (order == null) {
                continue;
            }
            if (submits) {
                order.ctx.lastSendNanos = now;
            }
            decided(
                    order,
                    this.budget.type(batch.rule),
                    AdmissionAction.SENT,
                    submits ? order.queuedAtNanos() : order.cancelQueuedAtNanos,
                    this.budget.tokensLeft(batch.rule, now));
        }
        if (result == AsyncHTTPConnections.SEND_FAILED) {
            onBatchAnswer(batch, null);
        }
        return 1;
    }

    /**
     * Every entry but {@code except} queued again as it was. Submits go back to the front of their sessions' lists, so
     * last first, which keeps them in the order they went out.
     */
    private void putBackAll(final LaneBatch batch, final int except) {
        final boolean submits = batch.kind == BATCH_SUBMIT;
        for (int n = 0; n < batch.count; n++) {
            final int i = submits ? batch.count - 1 - n : n;
            final LaneOrder order = i == except ? null : live(batch.entries[i]);
            if (order != null) {
                putBack(order, batch.kind);
            }
        }
    }

    /** An entry of a batch that never reached the venue: queued again as it was. */
    private void putBack(final LaneOrder order, final int batchKind) {
        order.inFlight = LaneOrder.NONE;
        if (batchKind == BATCH_CANCEL) {
            serveWanted(order);
        } else if (order.cancelWanted) {
            // Cancelled while it was on its way: never sent after all, so dropped.
            order.cancelWanted = false;
            refuseWanted(order);
            this.notices.canceledUnsent(order.ctx);
            free(order);
        } else if (order.releaseWanted) {
            free(order);
        } else if (!this.fair.requeueSubmit(order)) {
            rejectAndFree(order, RejectReason.SESSION_CAP);
        }
    }

    /** @param response the answer, or null if none came whole */
    private void onBatchAnswer(final LaneBatch batch, final HTTPResponse response) {
        final int failed = readBatch(batch, response);
        // One time for the batch, so cancels sent again are due together and go together.
        final long now = this.nanoClock.getAsLong();
        if (failed != LaneVenue.NO_FAILED_ENTRY) {
            putBackAll(batch, failed);
        }
        for (int i = 0; i < batch.count; i++) {
            final LaneOrder order = failed == LaneVenue.NO_FAILED_ENTRY || i == failed ? live(batch.entries[i]) : null;
            if (order == null) {
                continue;
            }
            order.inFlight = LaneOrder.NONE;
            if (batch.kind == BATCH_SUBMIT) {
                final SubmitResult result = i == failed ? SubmitResult.REJECTED : batch.submitResults[i];
                settle(order, this.policy.afterSend(order.ctx, result, this.venue.resendsAreSafe()));
            } else {
                answerBatchedCancel(order, i == failed ? CancelResult.REJECTED : batch.cancelResults[i], response, now);
            }
            afterAnswer(order);
        }
        releaseBatch(batch);
    }

    /**
     * Each entry's result from the answer; a cancel batch the venue failed to answer leaves its results unread, as
     * each is sent again or refused.
     *
     * @return the one entry the venue refused the whole batch for, or {@link LaneVenue#NO_FAILED_ENTRY}
     */
    private int readBatch(final LaneBatch batch, final HTTPResponse response) {
        final boolean answered = response != null && response.getStatusCode() < 500;
        if (batch.kind == BATCH_SUBMIT) {
            if (response != null) {
                return this.venue.readBatchSubmit(batch.ctxs, batch.count, response, batch.submitResults);
            }
            for (int i = 0; i < batch.count; i++) {
                batch.submitResults[i] = SubmitResult.UNKNOWN;
            }
        } else if (answered) {
            return this.venue.readBatchCancel(batch.ctxs, batch.count, response, batch.cancelResults);
        }
        return LaneVenue.NO_FAILED_ENTRY;
    }

    private void answerBatchedCancel(
            final LaneOrder order, final CancelResult result, final HTTPResponse response, final long now) {
        final boolean unanswered = response == null || response.getStatusCode() >= 500;
        if (unanswered && retryCancel(order, now)) {
            return;
        }
        endCancel(order);
        if (unanswered && result != CancelResult.REJECTED) {
            this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN);
        } else {
            cancelOutcome(order, result);
        }
    }

    private static final int BLOCKED = 0;
    private static final int TAKEN = 1;
    private static final int RETRY = 2;

    /** Sends the queue's requests in order while connections and the budget allow; the head waits otherwise. */
    private int drainQueue(final LongArrayQueue queue, final int kind) {
        if (kind == LaneOrder.CANCEL && this.maxBatch > 1 && queue.size() > 1) {
            return drainCancelBatches();
        }
        int sent = 0;
        while (!queue.isEmpty()) {
            final LaneOrder order = live(queue.peekLong());
            if (order == null || order.inFlight != LaneOrder.NONE || stale(order, kind)) {
                queue.pollLong();
                continue;
            }
            final int outcome = trySend(order, kind);
            if (outcome == BLOCKED) {
                return sent;
            }
            if (outcome == TAKEN) {
                queue.pollLong();
                sent++;
            }
        }
        return sent;
    }

    /** Whether what queued the entry no longer stands: a cancel or amend since answered or refused. */
    private static boolean stale(final LaneOrder order, final int kind) {
        return (kind == LaneOrder.CANCEL && !order.cancelWanted)
                || (kind == LaneOrder.AMEND && (!order.amendWanted || order.cancelWanted));
    }

    /**
     * @return {@link #TAKEN} once the request is sent or settled without one, {@link #BLOCKED} if no connection or
     *     budget is free, {@link #RETRY} if its connection was found dead before a byte went
     */
    private int trySend(final LaneOrder order, final int kind) {
        this.lastCost = 0;
        if (!shape(order, kind)) {
            // The venue can't look this order up: the lookup's answer is that it couldn't tell.
            settle(order, this.policy.afterLookUp(order.ctx, LookupResult.UNKNOWN, this.nanoClock.getAsLong()));
            if (order.held) {
                serveWanted(order);
            }
            return TAKEN;
        }
        // A connection first: finding one reserves nothing, while the budget's tokens once taken are spent.
        final int connection = acquireConnection();
        if (connection == AsyncHTTPConnections.NONE) {
            this.heldByBudget = false;
            return BLOCKED;
        }
        final long now = this.nanoClock.getAsLong();
        final int rule = this.budget.classify(this.shape.method, this.shape.path);
        if (!this.budget.tryAcquire(rule, now)) {
            this.heldByBudget = true;
            tellHeld(order, rule);
            return BLOCKED;
        }
        this.lastCost = this.budget.cost(rule) * MILLI_TOKENS;
        write(order, kind, connection);
        sending(connection, singleClass(kind), now);
        final int result =
                this.connections.send(connection, tag(order, kind), now + this.timing.responseTimeoutNanos());
        if (result == AsyncHTTPConnections.NOT_SENT) {
            return RETRY;
        }
        this.admissions.sent(singleClass(kind), this.nanoClock.getAsLong() - now);
        tellSent(order, kind, rule, now);
        if (kind == LaneOrder.SUBMIT) {
            // Once written, not when the pass began: the venue's reads lag from when it took the order.
            order.ctx.lastSendNanos = this.nanoClock.getAsLong();
        }
        order.inFlight = kind;
        order.inFlightRule = rule;
        if (result == AsyncHTTPConnections.SEND_FAILED) {
            onAnswer(order, kind, null);
        }
        return TAKEN;
    }

    /** A queued amend or new order the budget holds: told once, however many passes it waits. */
    private void tellHeld(final LaneOrder order, final int rule) {
        if (!order.heldTold() && order.queued()) {
            order.markHeldTold();
            decided(order, this.budget.type(rule), AdmissionAction.HELD, order.queuedAtNanos(), -1);
        }
    }

    private void tellSent(final LaneOrder order, final int kind, final int rule, final long now) {
        final long since;
        if (kind == LaneOrder.CANCEL) {
            since = order.cancelQueuedAtNanos;
        } else {
            since = order.queued() ? order.queuedAtNanos() : 0;
        }
        decided(order, this.budget.type(rule), AdmissionAction.SENT, since, this.budget.tokensLeft(rule, now));
    }

    private boolean shape(final LaneOrder order, final int kind) {
        @SuppressWarnings("unchecked")
        final M market = (M) order.market;
        switch (kind) {
            case LaneOrder.SUBMIT -> this.venue.submitShape(order.ctx, market, this.shape);
            case LaneOrder.LOOKUP -> {
                return this.venue.lookupShape(order.ctx, market, this.shape);
            }
            case LaneOrder.CANCEL -> this.venue.cancelShape(order.ctx, market, this.shape);
            default -> this.venue.amendShape(order.ctx, market, this.shape);
        }
        return true;
    }

    private void write(final LaneOrder order, final int kind, final int connection) {
        @SuppressWarnings("unchecked")
        final M market = (M) order.market;
        final HTTPRequestWriter request =
                this.connections.request(connection, this.shape.method).path(this.shape.path);
        final long nowMillis = this.epochClock.time();
        switch (kind) {
            case LaneOrder.SUBMIT -> this.venue.writeSubmit(
                    order.ctx, this.shape, order.body, order.bodyLength, request, nowMillis);
            case LaneOrder.LOOKUP -> this.venue.writeLookup(order.ctx, market, this.shape, request, nowMillis);
            case LaneOrder.CANCEL -> this.venue.writeCancel(order.ctx, market, this.shape, request, nowMillis);
            default -> this.venue.writeAmend(
                    order.ctx,
                    market,
                    order.amendPrice,
                    order.amendSize,
                    order.amendPostOnly,
                    this.shape,
                    request,
                    nowMillis);
        }
    }

    private int acquireConnection() {
        final int connection = this.connections.acquire();
        if (connection != AsyncHTTPConnections.NONE) {
            this.connectionsWereBusy = false;
        } else if (!this.connectionsWereBusy) {
            this.connectionsWereBusy = true;
            this.admissions.connectionsBusy();
        }
        return connection;
    }

    private void sending(final int connection, final RequestClass type, final long now) {
        this.sentAtNanos[connection] = now;
        this.sentTypes[connection] = type;
    }

    private static RequestClass singleClass(final int kind) {
        return switch (kind) {
            case LaneOrder.SUBMIT -> RequestClass.NEW;
            case LaneOrder.CANCEL -> RequestClass.CANCEL;
            case LaneOrder.LOOKUP -> RequestClass.CATCH_UP;
            default -> RequestClass.AMEND;
        };
    }

    // ----- answers -----

    private int pollAnswers() {
        int answered = 0;
        while (this.connections.poll()) {
            answered++;
            final HTTPCompletion completion = this.connections.completion();
            final int connection = completion.connection();
            this.admissions.answered(
                    this.sentTypes[connection],
                    this.nanoClock.getAsLong() - this.sentAtNanos[connection],
                    completion.outcome() == HTTPCompletion.RESPONSE);
            final long tag = completion.tag();
            final int kind = (int) (tag & KIND_MASK);
            if (kind == BATCH_SUBMIT || kind == BATCH_CANCEL) {
                final LaneBatch batch = this.batches[(int) (tag >>> KIND_BITS)];
                final HTTPResponse response =
                        completion.outcome() == HTTPCompletion.RESPONSE ? completion.response() : null;
                if (response != null) {
                    this.budget.onResponse(batch.rule, response, this.nanoClock.getAsLong());
                }
                onBatchAnswer(batch, response);
                continue;
            }
            final LaneOrder order = live(tag >>> KIND_BITS);
            if (order == null) {
                continue;
            }
            final HTTPResponse response =
                    completion.outcome() == HTTPCompletion.RESPONSE ? completion.response() : null;
            if (response != null) {
                this.budget.onResponse(order.inFlightRule, response, this.nanoClock.getAsLong());
            }
            onAnswer(order, kind, response);
        }
        return answered;
    }

    /** @param response the answer, or null if none came whole (the connection failed or the deadline passed) */
    private void onAnswer(final LaneOrder order, final int kind, final HTTPResponse response) {
        order.inFlight = LaneOrder.NONE;
        final OrderContext ctx = order.ctx;
        switch (kind) {
            case LaneOrder.SUBMIT -> {
                final SubmitResult result =
                        response == null ? SubmitResult.UNKNOWN : this.venue.readSubmit(ctx, response);
                settle(order, this.policy.afterSend(ctx, result, this.venue.resendsAreSafe()));
            }
            case LaneOrder.LOOKUP -> {
                final LookupResult result =
                        response == null ? LookupResult.UNKNOWN : this.venue.readLookup(ctx, response);
                settle(order, this.policy.afterLookUp(ctx, result, this.nanoClock.getAsLong()));
            }
            case LaneOrder.CANCEL -> onCancelAnswer(order, response);
            default -> onAmendAnswer(order, response);
        }
        afterAnswer(order);
    }

    /** An order whose answer came: freed if the reader ended it meanwhile, else what waited on it served. */
    private void afterAnswer(final LaneOrder order) {
        if (order.releaseWanted && order.inFlight == LaneOrder.NONE && order.held) {
            free(order);
            return;
        }
        if (order.held) {
            serveWanted(order);
        }
    }

    private void settle(final LaneOrder order, final SubmitPolicy.Action action) {
        final OrderContext ctx = order.ctx;
        switch (action) {
            case ACCEPT -> {
                order.submitState = LaneOrder.SETTLED;
                order.accepted = true;
                order.unknownSinceNanos = 0;
                if (this.venue.venueIdKnown(ctx)) {
                    this.ownership.venueIdLearned(ctx);
                    if (this.venue.readerNeedsVenueId()) {
                        this.notices.venueOrderId(ctx);
                    }
                }
                if (ctx.awaitsFinalFill) {
                    this.notices.finalFill(ctx, this.venue.finalFillQty(ctx));
                }
            }
            case REJECT -> rejectAndFree(
                    order, ctx.rejectReason != null ? ctx.rejectReason : RejectReason.EXCHANGE_REJECTED);
            case REJECT_RATE_LIMITED -> rejectAndFree(order, RejectReason.RATE_LIMITED);
            case RESEND -> {
                order.submitState = LaneOrder.RESOLVING;
                this.resends.offerLong(order.entry());
            }
            case LOOK_UP -> {
                order.submitState = LaneOrder.RESOLVING;
                if (ctx.lookupReason == SubmitPolicy.LookupReason.NO_RESEND) {
                    // The one lookup a venue that can't take a resend gets: made once its reads have caught up, so
                    // an order that did land is found.
                    ctx.lookUpNotBeforeNanos = ctx.lastSendNanos + this.timing.readSettleNanos();
                }
                if (ctx.lookUpNotBeforeNanos != 0 && ctx.lookUpNotBeforeNanos - this.nanoClock.getAsLong() > 0) {
                    this.waiting[this.waitingCount++] = order.entry();
                } else {
                    this.lookups.offerLong(order.entry());
                }
            }
            default -> {
                // Unknown: it may be live, so it stays for the venue's events to settle, and is looked for again.
                order.submitState = LaneOrder.SETTLED;
                if (order.unknownSinceNanos == 0) {
                    final long now = this.nanoClock.getAsLong();
                    order.unknownSinceNanos = now;
                    order.nextSweepNanos = now + this.timing.sweepIntervalNanos();
                    this.unknowns[this.unknownCount++] = order.entry();
                }
            }
        }
    }

    private void onCancelAnswer(final LaneOrder order, final HTTPResponse response) {
        if ((response == null || response.getStatusCode() >= 500) && retryCancel(order, this.nanoClock.getAsLong())) {
            return;
        }
        endCancel(order);
        if (response == null) {
            this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN);
            return;
        }
        cancelOutcome(order, this.venue.readCancel(order.ctx, response));
    }

    private static void endCancel(final LaneOrder order) {
        order.cancelWanted = false;
        order.cancelRetries = 0;
        order.cancelFailedSinceNanos = 0;
    }

    /** An accepted cancel keeps the order until the reader sees it end on the venue's events. */
    private void cancelOutcome(final LaneOrder order, final CancelResult result) {
        if (result == CancelResult.REJECTED) {
            this.notices.cancelReject(order.ctx, RejectReason.EXCHANGE_REJECTED);
        } else if (result == CancelResult.RATE_LIMITED) {
            this.notices.cancelReject(order.ctx, RejectReason.RATE_LIMITED);
        }
    }

    /**
     * Sends a cancel the venue failed to answer again after a backoff, unless it has been tried enough or for long
     * enough. A cancel is safe to repeat: one that landed the first time is refused or ignored the second.
     */
    private boolean retryCancel(final LaneOrder order, final long now) {
        if (order.cancelRetries == 0) {
            order.cancelFailedSinceNanos = now;
        }
        if (order.cancelRetries >= MAX_CANCEL_RETRIES
                || now - order.cancelFailedSinceNanos >= CANCEL_RETRY_WINDOW_NANOS) {
            return false;
        }
        order.cancelRetries++;
        order.cancelRetryAtNanos = now + order.cancelRetries * CANCEL_RETRY_BACKOFF_NANOS;
        this.cancelRetries[this.cancelRetryCount++] = order.entry();
        return true;
    }

    private int wakeCancelRetries() {
        if (this.cancelRetryCount == 0) {
            return 0;
        }
        final long now = this.nanoClock.getAsLong();
        int woken = 0;
        int kept = 0;
        for (int i = 0; i < this.cancelRetryCount; i++) {
            final long entry = this.cancelRetries[i];
            final LaneOrder order = live(entry);
            if (order == null || order.cancelRetryAtNanos == 0) {
                continue;
            }
            if (order.cancelRetryAtNanos - now > 0) {
                this.cancelRetries[kept++] = entry;
                continue;
            }
            order.cancelRetryAtNanos = 0;
            woken++;
            if (order.cancelWanted) {
                serveWanted(order);
            }
        }
        this.cancelRetryCount = kept;
        return woken;
    }

    private void onAmendAnswer(final LaneOrder order, final HTTPResponse response) {
        order.amendWanted = false;
        if (response == null) {
            this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN);
            return;
        }
        this.amendAnswer.fillCount = OrderContext.QTY_ABSENT;
        this.amendAnswer.remaining = OrderContext.QTY_ABSENT;
        final RejectReason refusal = this.venue.readAmend(order.ctx, order.amendSize, response, this.amendAnswer);
        if (refusal != null) {
            this.notices.cancelReject(order.ctx, refusal);
            return;
        }
        order.ctx.originalQty = order.amendSize;
        order.ctx.limitPrice = order.amendPrice;
        this.ownership.amended(order.ctx);
        this.notices.amendAccepted(order.ctx, order.amendSize, this.amendAnswer.fillCount, this.amendAnswer.remaining);
    }

    private int wakeWaiting() {
        if (this.waitingCount == 0) {
            return 0;
        }
        final long now = this.nanoClock.getAsLong();
        int woken = 0;
        int kept = 0;
        for (int i = 0; i < this.waitingCount; i++) {
            final long entry = this.waiting[i];
            final LaneOrder order = live(entry);
            if (order == null) {
                continue;
            }
            if (order.ctx.lookUpNotBeforeNanos - now > 0) {
                this.waiting[kept++] = entry;
            } else {
                this.lookups.offerLong(entry);
                woken++;
            }
        }
        this.waitingCount = kept;
        return woken;
    }

    /** Looks for each unknown order again when its time comes, and gives up on one waited on too long. */
    private int sweepUnknowns() {
        if (this.unknownCount == 0) {
            return 0;
        }
        final long now = this.nanoClock.getAsLong();
        int work = 0;
        int kept = 0;
        for (int i = 0; i < this.unknownCount; i++) {
            final long entry = this.unknowns[i];
            final LaneOrder order = live(entry);
            if (order == null || order.unknownSinceNanos == 0) {
                continue;
            }
            if (sweep(order, entry, now)) {
                work++;
            }
            if (order.unknownSinceNanos == 0 || !order.held) {
                continue;
            }
            this.unknowns[kept++] = entry;
        }
        this.unknownCount = kept;
        return work;
    }

    /** @return whether it did anything: looked the order up again, or gave up on it */
    private boolean sweep(final LaneOrder order, final long entry, final long now) {
        if (order.inFlight != LaneOrder.NONE) {
            return false;
        }
        final boolean lookable = this.venue.canLookUp(order.ctx);
        final long giveUp = lookable ? this.timing.giveUpNanos() : this.timing.giveUpUnlookableNanos();
        if (now - order.unknownSinceNanos >= giveUp) {
            refuseWanted(order);
            this.notices.abandon(order.ctx);
            free(order);
            return true;
        }
        if (lookable && now - order.nextSweepNanos >= 0) {
            order.ctx.lookupReason = SubmitPolicy.LookupReason.SWEEP;
            order.nextSweepNanos = now + this.timing.sweepIntervalNanos();
            this.lookups.offerLong(entry);
            return true;
        }
        return false;
    }

    // ----- releases -----

    private int drainReleased() {
        this.handledThisPass = 0;
        this.releasedOrderQueue.read(this.releaseHandler, Integer.MAX_VALUE);
        return this.handledThisPass;
    }

    private void onReleased(final OrderContext released) {
        this.handledThisPass++;
        final LaneOrder order = this.table.get(released.sessionSlot, released.clientOidCounter);
        if (order == null) {
            return;
        }
        if (released.venueOrderIdNotice) {
            // An event named the order its submit's answer never did: it can be cancelled and amended now.
            if (!this.venue.venueIdKnown(order.ctx)) {
                order.ctx.exchangeOrderIdLength = released.exchangeOrderIdLength;
                System.arraycopy(
                        released.exchangeOrderIdBytes,
                        0,
                        order.ctx.exchangeOrderIdBytes,
                        0,
                        released.exchangeOrderIdLength);
                this.ownership.venueIdLearned(order.ctx);
            }
            return;
        }
        if (order.inFlight != LaneOrder.NONE) {
            order.releaseWanted = true;
            return;
        }
        free(order);
    }

    // ----- order slots -----

    /**
     * Ends an order with a reject: one handed to the reader so that the reader drops it if the venue's events ended it
     * already. A cancel or amend still waiting on it is refused first, so every command has its answer.
     */
    private void rejectAndFree(final LaneOrder order, final RejectReason reason) {
        if (order.held) {
            refuseWanted(order);
            this.notices.rejectRegistered(order.ctx, reason);
        } else {
            this.notices.reject(order.ctx, reason);
        }
        free(order);
    }

    private void refuseWanted(final LaneOrder order) {
        if (order.cancelWanted) {
            order.cancelWanted = false;
            this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN_ORDER);
        }
        if (order.amendWanted) {
            order.amendWanted = false;
            this.fair.unqueue(order);
            this.notices.cancelReject(order.ctx, RejectReason.UNKNOWN_ORDER);
        }
    }

    /**
     * Why a new order of the session's can't be taken on, or null: its session is fenced behind a cancel, or has no
     * place, or the order is past the session's caps or the account's.
     */
    private RejectReason refusal(final LaneCommand command) {
        if (this.fair.fenced(command.sessionSlot)) {
            return RejectReason.GATEWAY_REJECTED;
        }
        final AdmissionSession session = this.fair.session(command.sessionSlot);
        if (session == null
                || (this.caps.accountMaxOpenOrders() > 0 && this.table.size() >= this.caps.accountMaxOpenOrders())
                || !session.admitNew(this.nanoClock.getAsLong())) {
            return RejectReason.SESSION_CAP;
        }
        return null;
    }

    /** The lane holds the order from now until it ends: in its table, and counted against its session's cap. */
    private void hold(final LaneOrder order, final long correlationKey) {
        this.table.put(order.ctx.sessionSlot, order.ctx.clientOidCounter, order);
        this.byCorrelation.put(correlationKey, order);
        order.held = true;
        // A recovered order's session may not be introduced yet: it takes its place now, so the order counts.
        final AdmissionSession session = this.fair.session(order.ctx.sessionSlot);
        if (session != null) {
            session.opened();
        }
    }

    private void free(final LaneOrder order) {
        this.fair.unqueue(order);
        if (order.held) {
            final AdmissionSession session = this.fair.existing(order.ctx.sessionSlot);
            if (session != null) {
                session.closed();
            }
            this.table.remove(order.ctx.sessionSlot, order.ctx.clientOidCounter);
            this.byCorrelation.remove(OrderKeys.of(order.ctx));
            this.ownership.ended(order.ctx);
        }
        this.venue.onReleased(order.ctx);
        order.generation++;
        order.reset();
        this.free[this.freeCount++] = order.index;
    }

    /** The order a queue entry or tag names, if it is still the same order; null if it has ended since. */
    private LaneOrder live(final long entry) {
        final LaneOrder order = this.orders[(int) (entry & INDEX_MASK)];
        return (order.generation & LaneOrder.GENERATION_MASK) == (int) (entry >>> LaneOrder.INDEX_BITS)
                        && (order.held || order.inFlight != LaneOrder.NONE)
                ? order
                : null;
    }

    private static long tag(final LaneOrder order, final int kind) {
        return (order.entry() << KIND_BITS) | kind;
    }
}
