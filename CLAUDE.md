# CLAUDE.md

gnome-gateways is the Java library that talks to trading venues: the feed layer (`feed/`) reads venues' market data,
many markets multiplexed per connection, into `Mbp10Schema` records on each stream's `SequencedRingBuffer`, and
outbound gateways send the OMS's orders to the venue and report fills and acks back. It has no process or deployment of
its own; gnome-orchestrator wires it up, both in a strategy session's JVM and in the market data shards
(`collectors/ShardOrchestrator`), which run the same feeds.

## Commands

- Build and test: `mvn verify` (CI runs `mvn verify -B -s settings.xml`, which needs `GITHUB_ACTOR`/`GITHUB_TOKEN` for
  the GitHub Packages repo).
- Unit tests: `mvn test`
- Single test, full class name: `mvn test -Dtest=group.gnometrading.gateways.venues.kalshi.KalshiProtocolTest`
- Always pass `-Dsurefire.timeout=120`: feed tests start real reader and supervisor threads, and a test that calls
  `FeedConnection.connect()` without a running reader would wait on its pause forever.
- Feed latency benchmark (prints per-venue reader latency, supervisor/tick/prober pass times and allocation; not part of
  `mvn test`): `mvn test-compile surefire:test@benchmark`. Compare runs on the same machine before and after a change.
- Format: `mvn spotless:apply`. Spotless (palantir) and checkstyle both run `check` in `validate`, so unformatted code
  fails the build.
- Maven only, never Gradle. Java 17 (set by `gnome-parent`).
- Polymarket signer reference vectors: `scripts/pm_v2_vectors.py` (setup in its docstring); rerun only when the
  CLOB V2 order format changes.

## Layout

All code is under `src/main/java/group/gnometrading/gateways/`.
- `GatewaySupervisor`, `SocketConnectController`, `ConnectLimiter`, `GatewayConfig`, `ReaderPauseControl`: connect,
  reconnect, keep-alive and silence detection shared by feeds and outbound.
- `feed/`: the market data layer, generic over transport and venue.
  - `Subject`: one venue market, its full-depth `book/Book`, state machine (`SUBSCRIBING -> LIVE <-> SUSPECT ->
    STALE`, plus `EMPTY`, `QUARANTINED`, `REMOVING`) and liveness; publishes to each `StreamOutput` through its
    `book/Projection` (IDENTITY, or COMPLEMENT for a binary market's NO / short listing).
  - `FeedConnection`: one connection's reader (hot path), supervisor and command queue: subscribes in batches paced by
    `RateBudget` (frames a minute) and the writer's room, resyncs, snapshot timeouts (then `EMPTY`), quarantine after
    3 resyncs in 10 minutes; a roll's release and adopt run on its supervisor. Each `VenueProtocol` declares
    `books()`: CHANGES (Kalshi, Lighter, Polymarket Intl; probed) or WHOLE (Hyperliquid, Polymarket US; not probed).
  - `ConnectionGroup`: a source's connections in a process, packed up to `max_per_connection`, closed when empty for
    5 minutes, rolled make-before-break every 12 h. `Feeds`: streams to markets (shared subjects), groups, the prober,
    quarantine retries.
  - `transport/`: `Transport` (`WebSocketTransport` now; FIX later), `FrameWriter`/`WebSocketWriter` (MPSC queues).
  - `probe/`: `BookProber` (REST check of the top 5 levels, silence -> `SUSPECT`), `BookProbeClient`, `JsonLevels`.
  - `VenueFeed`: what a venue tells the feed about itself (market mapping, depth, URL property, probe budget).
- `orders/`: generic order entry: `OutboundGateway`, `OutboundSocketWriter` (HTTP), `OutboundSocketReader` (private
  socket for order events), `OrderContext`, `IdleConnections`, `VenueRejectText`, `recovery/` (venue order lookup).
- `ratelimit/`: per-account REST limiter (`VenueRateLimiter`, `VenueRateLimits`) shared by every listing on an account.
- `venues/<venue>/`: everything specific to one venue, on top of the generic layers above, which never depend on it.
  `<Venue>Venue` holds its facts (`FEED`, `subjectOf`, and `rateLimits()` where it has order entry); `<Venue>Protocol`
  its market data (auth, subscribe/unsubscribe/resync, keep-alive, frame decoding); `<Venue>BookProbe` its REST probe
  if it sends book changes; `<Venue>Outbound*`, signers, requests and order queries its order entry. Venues:
  `kalshi`, `polymarket/intl`, `polymarket/us` (market data and orders), `hyperliquid`, `lighter` (market data only).
- `fix/`: generic FIX transport; nothing in this repo or gnome-orchestrator uses it now that Binance is gone.
- Tests mirror the code: generic feed and outbound tests under `feed/` and `orders/`, each venue's under
  `venues/<venue>/`. `venues/polymarket/intl/PolymarketIntlCaptureReplay` is a `main()` tool, not a test.

## Conventions and invariants

- Hot path is zero-allocation: feed readers and protocols' `onFrame`, writers' `doWork`, the prober's pass, `Feeds.tick`
  (a session runs it in its JVM), signers, `VenueRejectText`. No boxing, lambdas or string building per message; hold
  method references in fields. Allocation only on connect, error, log, subscribe and roll paths
  (`SocketConnectController` allocates on every connect by design). `FeedAllocationTest` proves each.
- Allocation tests are `@Tag("allocation")` and run in their own surefire execution, one JVM per class, because a
  Mockito inline mock of `WebSocketClient`/`WebSocketResponse` makes real instances allocate. Never mock those in an
  allocation test's JVM.
- Fixed point: prices x1e9 (`Statics.PRICE_SCALING_FACTOR`), sizes x1e6 (`SIZE_SCALING_FACTOR`).
- Threads per connection: reader (`ThreadProfile.HOT_PATH`), socket writer, and supervisor. The supervisor pauses the
  reader through `ReaderPauseControl` before any connect/disconnect; never touch the socket from the supervisor while
  the reader runs.
- Only a connection's reader publishes its markets' books (other threads queue `requestStale`/`requestRepublish`). A
  roll moves a market to a new connection's reader, so stream rings are built with
  `SequencedRingBuffer.Producers.TAKING_TURNS`.
- Connect: one attempt plus `maxReconnectAttempts` (5) per cycle, backoff 1s doubling to 10s; after `maxConnectCycles`
  (3) failed cycles it throws `GatewayConnectFailedException` to the error handler. Forced reconnect every 12h and when
  nothing arrives for `maxSilentInterval` (30s). `GatewayConfig` validates timeout relationships; keep them valid.
- `ConnectLimiter.forSource` spaces connects per source across the process: Kalshi 2/s, others 5/s. Outbound uses
  `UNLIMITED` (one connect per venue).
- Every venue snapshots over its socket. Gap detection: a bad book goes `STALE`, is published once flagged badData, and
  only that market is resynced on its connection. Kalshi checks `seq` over every frame of the connection (a gap
  resyncs all its markets, hence 250 per connection), Lighter each market's nonce chain, Polymarket Intl the venue's
  best bid/ask against the book. Hyperliquid and Polymarket US send whole books. The REST probe catches the rest.
- Kalshi: `:yes`/`:no` listings share one subject (the ticker); a `:no` listing's book and trades are its complement
  (price `1 - p`, sides flipped), as are its orders. Market orders are refused (Kalshi needs a price; the OMS sends
  priced IOCs).
- Polymarket US: `:long`/`:short` listings share one subject; `:short` is the complement. No client order id and no
  dedup, so a submit is never resent; events are matched by GTD stamp or (IOC) side+price. FOK refused.
- Polymarket Intl: `exchangeSecurityId` is `{condition_id}:{token_id}`. Orders are EIP-712 signed (signature types
  0-3, incl. 3 = deposit wallet) and immutable, so no modify; market orders refused.
- Submits with no clear answer: resent only where the venue dedups (`resendsAreSafe`), else looked up. Every OMS
  message must get exactly one report; do not add a path that drops or doubles one.
- Rate limits (`VenueRateLimits`): Kalshi Basic tier read/write buckets, Polymarket Intl order + cancel buckets,
  Polymarket US one bucket per key. Budgets start below the venue rate, halve on 429, recover slowly. Cancels may use a
  reserve; new orders are refused rather than delayed.
- Credentials are passed in, never read here. The orchestrator loads them from Secrets Manager: market data from
  `gnome/market-data/kalshi` and `gnome/market-data/polymarket-us`, trading from the venue account's
  `tradingSecretName`. Signatures are recomputed on every connect so timestamps are never stale.
- `VenueRejectText` only holds phrases seen in real venue answers; don't add guessed ones.

## Operations

- No stacks or infrastructure of its own. Logs come out through the orchestrator, in the session's CloudWatch log
  group `/gnome/orchestrator/<region>`.
- Disconnect: `SOCKET_DISCONNECTED` then `SOCKET_RECONNECTING`/`SOCKET_CONNECTED` is normal self-healing.
  `SOCKET_CONNECT_FAILED` logs the cause chain; a 401/403 on Kalshi or Polymarket US usually means a bad or revoked key
  in the secret. `GatewayConnectFailedException` means all connect cycles failed; check venue status and credentials.
- Stale book: `SOCKET_SILENCE_TIMED_OUT` forces a reconnect after a connection's silence. "resyncing <market> (<reason>)"
  is one market resynced; "quarantined <market> after 3 resyncs" takes it off for 5, 15, then 60 minutes; "sent no
  book" means it is marked `EMPTY` and rechecked every 15 minutes. The prober logs mismatches against the venue's REST
  book. A shard's `HEARTBEAT` lines and a session's heartbeat (`reconnecting`, `resyncsLast5m` per listing) show it.
- Rejected orders: `ORDER_REJECTED_BY_VENUE` carries the venue's own text. Kalshi 503 while its shard isn't trading
  maps to MARKET_CLOSED; Kalshi 409 is a duplicate client id. Polymarket Intl 503 means trading disabled, cancel-only
  or post-only. Polymarket US refusals are all reported as plain venue rejects (no captured texts yet).
- Rate limits: `VENUE_RATE_LIMITED` (venue 429), `RATE_LIMIT_THROTTLING` / `RATE_LIMIT_RECOVERED` (local budget).
  Other processes on the same account share the venue budget.
- "Order context pool exhausted" means more in-flight orders than `OrderContext.MAX_IN_FLIGHT_ORDERS`.
- Release: every push to `main` runs gnome-build-tools' bump-and-deploy (`mvn verify`, then maven-release-plugin to
  GitHub Packages). Commit prefix picks the bump: `major:` major, `feat:` minor, anything else patch. The release
  fails on SNAPSHOT dependencies, so pin released versions of gnome-core/gnome-networking first.
- Consumed by gnome-orchestrator via the `gnome-gateways` version in its `pom.xml`; a gateway change reaches sessions
  only after that version is bumped and the orchestrator is released.
