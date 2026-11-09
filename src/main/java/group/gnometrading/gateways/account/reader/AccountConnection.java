package group.gnometrading.gateways.account.reader;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewaySupervisor;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.transport.FrameReader;
import group.gnometrading.gateways.transport.RawDataSink;
import group.gnometrading.gateways.transport.Transport;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;

/**
 * An account's event socket: one connection subscribed to every order on the account, its reader an {@link
 * AccountReader}. A connect holds the reader only while it opens and authenticates the socket, subscribes
 * account-wide and copies the orders held; the reader then goes on reporting what the lane tells it while the
 * supervisor fetches over REST what happened since the socket was last heard, and reads nothing from the new socket
 * until it has applied that. It is never reconnected on a schedule, only when it fails or goes silent.
 *
 * <p>A venue's REST listings lag its event stream, so what happened in the moments before a connect (cancels sent
 * while the socket was down, say) may not be in them yet, nor will its events come on the new socket. So once the
 * venue's reads have caught up, {@code readSettleNanos} after each connect, catch-up runs a second time over the same
 * window. Applying it twice is harmless: events carry an order's whole state, and ended orders are skipped.
 *
 * @param <C> what the venue's protocol sends through
 */
public final class AccountConnection<C> {

    // Far enough off never to come: a scheduled reconnect would only open a window for missed events.
    private static final Duration NO_SCHEDULED_RECONNECT = Duration.ofDays(36_500);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final String name;
    private final Logger logger;
    private final Transport<C> transport;
    private final AccountProtocol<C> protocol;
    private final AccountReader accountReader;
    private final EpochNanoClock nanoClock;
    private final ReaderPauseControl pause = new ReaderPauseControl();
    private final FrameReader reader;
    private final Supervisor supervisor;
    private final long createdAtNanos;
    private final long readSettleNanos;
    private volatile AccountState state = AccountState.CONNECTING;
    // Supervisor thread: when the settle catch-up is due (0 when none is), and the window it covers.
    private long settleCatchUpAtNanos;
    private long settleSinceMillis;

    public AccountConnection(
            final String name,
            final Logger logger,
            final Transport<C> transport,
            final AccountProtocol<C> protocol,
            final AccountReader accountReader,
            final GatewayConfig config,
            final EpochClock clock,
            final EpochNanoClock nanoClock,
            final ConnectLimiter connects,
            final long readSettleNanos) {
        this.name = name;
        this.logger = logger;
        this.transport = transport;
        this.protocol = protocol;
        this.accountReader = accountReader;
        this.nanoClock = nanoClock;
        this.reader = new FrameReader(name, "account-reader", logger, transport, this.pause, nanoClock, accountReader);
        this.supervisor = new Supervisor(logger, config.withReconnectInterval(NO_SCHEDULED_RECONNECT), clock, connects);
        this.createdAtNanos = nanoClock.nanoTime();
        this.readSettleNanos = readSettleNanos;
        try {
            transport.configure(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String name() {
        return this.name;
    }

    public GnomeAgent reader() {
        return this.reader;
    }

    public GnomeAgent writer() {
        return this.transport.writer();
    }

    public GatewaySupervisor supervisor() {
        return this.supervisor;
    }

    /** For shutdown: stopping it releases the reader for good. */
    public ReaderPauseControl pauseControl() {
        return this.pause;
    }

    /**
     * DOWN as soon as the reader has stopped itself on a broken socket, before the supervisor reconnects it; READY only
     * once the reader has applied the catch-up, not merely once it was fetched, so every report it brings is out.
     */
    public AccountState state() {
        final AccountState current = this.state;
        if (current != AccountState.READY) {
            return current;
        }
        if (this.pause.pausedItself()) {
            return AccountState.DOWN;
        }
        return this.accountReader.mayRead() ? AccountState.READY : AccountState.CATCHING_UP;
    }

    /** Where every frame read is also written raw, for disputes and replays. */
    public void setRawSink(final RawDataSink sink) {
        this.reader.setRawSink(sink);
    }

    /**
     * Supervisor thread, through the connect controller. Catch-up covers from when the socket was last heard: every
     * event before then was read, and orders sent since, held or still queued for the reader, came after. On the first
     * connect it reaches back to the oldest order held, for orders recovered from an earlier process.
     */
    void connect() throws IOException {
        this.state = AccountState.CONNECTING;
        this.pause.pause();
        final List<OrderContext> held = this.accountReader.copyHeld();
        final long sinceMillis = sinceMillis(held);
        try {
            synchronized (this.transport.writer().socketLock()) {
                this.transport.close();
                this.transport.writer().newSocket();
                this.protocol.authenticate(this.transport.channel());
                this.transport.open();
                this.protocol.subscribe(this.transport.channel());
            }
        } catch (IOException e) {
            this.state = AccountState.DOWN;
            throw e;
        } catch (Exception e) {
            this.state = AccountState.DOWN;
            throw new IOException(e);
        }
        // Silence is measured from the new socket, or the old one's silence would bring another reconnect at once.
        this.reader.trafficAt(this.nanoClock.nanoTime());
        this.accountReader.holdFramesForCatchUp();
        this.state = AccountState.CATCHING_UP;
        this.pause.resume();
        try {
            this.protocol.fetchCatchUp(sinceMillis, held);
        } catch (IOException e) {
            this.state = AccountState.DOWN;
            throw e;
        } catch (Exception e) {
            this.state = AccountState.DOWN;
            throw new IOException(e);
        }
        this.accountReader.catchUpFetched();
        this.settleSinceMillis = sinceMillis;
        this.settleCatchUpAtNanos = this.nanoClock.nanoTime() + this.readSettleNanos;
        this.state = AccountState.READY;
    }

    /**
     * Supervisor thread: the second catch-up, fetched while the reader runs on; the reader is held only to copy the
     * orders it holds. Applied after events the new socket has delivered since, which is safe: events carry an
     * order's whole state, or (Polymarket's trades) are kept until the order's matched total is reached. A failure
     * reconnects, which catches up afresh.
     */
    private int settleCatchUp() {
        this.settleCatchUpAtNanos = 0;
        this.pause.pause();
        final List<OrderContext> held = this.accountReader.copyHeld();
        this.pause.resume();
        try {
            this.protocol.fetchCatchUp(this.settleSinceMillis, held);
            this.accountReader.catchUpFetched();
        } catch (final Exception e) {
            this.logger.logf(LogMessage.SOCKET_RECONNECTING, "%s: catch-up after settling failed: %s", this.name, e);
            this.state = AccountState.DOWN;
            this.supervisor.forceReconnect();
        }
        return 1;
    }

    /**
     * From when the socket was last heard; before it ever was, from when this connection was made, or from the oldest
     * order held if earlier: orders recovered from an earlier process have events since they were sent, and catching
     * up on all of them keeps each order's fills whole.
     */
    private long sinceMillis(final List<OrderContext> held) {
        final long lastHeard = this.reader.lastTrafficNanos();
        if (lastHeard > 0) {
            return lastHeard / NANOS_PER_MILLI;
        }
        long since = this.createdAtNanos / NANOS_PER_MILLI;
        for (final OrderContext ctx : held) {
            if (ctx.submittedAtMillis > 0) {
                since = Math.min(since, ctx.submittedAtMillis);
            }
        }
        return since;
    }

    private final class Supervisor extends GatewaySupervisor {

        Supervisor(
                final Logger logger,
                final GatewayConfig config,
                final EpochClock clock,
                final ConnectLimiter connects) {
            super(logger, AccountConnection.this::connect, config, clock, nanoClock, connects);
        }

        @Override
        protected void doDisconnect() throws Exception {
            state = AccountState.DOWN;
            pause.pause();
            synchronized (transport.writer().socketLock()) {
                transport.close();
            }
        }

        /** Only on a socket believed open: keep-alives on one known to be down fail and say nothing new. */
        @Override
        protected void doKeepAlive() throws Exception {
            if (state() == AccountState.READY) {
                protocol.keepAlive(transport.channel());
            }
        }

        @Override
        protected long recvTimestamp() {
            return reader.lastTrafficNanos();
        }

        @Override
        protected int doControl() throws Exception {
            if (state == AccountState.READY && pause.pausedItself()) {
                // The reader stopped on a broken socket: reconnect now rather than wait for the silence check.
                state = AccountState.DOWN;
                logger.logf(LogMessage.SOCKET_RECONNECTING, "%s: reader stopped, reconnecting", name);
                forceReconnect();
                return 1;
            }
            if (state == AccountState.READY
                    && settleCatchUpAtNanos != 0
                    && nanoClock.nanoTime() - settleCatchUpAtNanos >= 0) {
                return settleCatchUp();
            }
            if (protocol.wantsFollowUp()) {
                protocol.fetchFollowUp();
                accountReader.followUpFetched();
                return 1;
            }
            return 0;
        }

        @Override
        public String roleName() {
            return "account-supervisor";
        }
    }
}
