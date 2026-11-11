# CLAUDE.md

gnome-gateways is the Java library that talks to trading venues: the feed layer (`feed/`) reads venues' market data,
many markets multiplexed per connection, into `Mbp10Schema` records on each stream's `SequencedRingBuffer`, and the
account path (`account/`) sends every session's orders for one venue account and reports fills and acks back. It has no
process or deployment of its own; gnome-orchestrator wires it up, in the market data shards (`shard/ShardOrchestrator`)
and in the order gateway (`gateway/`).

## Commands

- Build and test: `mvn verify` (CI runs `mvn verify -B -s settings.xml`, which needs `GITHUB_ACTOR`/`GITHUB_TOKEN` for
  the GitHub Packages repo).
- Unit tests: `mvn test`
- Single test, full class name: `mvn test -Dtest=group.gnometrading.gateways.venues.kalshi.KalshiProtocolTest`
- Always pass `-Dsurefire.timeout=120`: feed tests start real reader and supervisor threads, and a test that calls
  `FeedConnection.connect()` without a running reader would wait on its pause forever.
- Feed latency benchmark (prints per-venue reader latency, supervisor/tick/prober pass times and allocation; not part of
  `mvn test`): `mvn test-compile surefire:test@benchmark`. Compare runs on the same machine before and after a change.
  `OrderPathBenchmark` does the same for each venue's order path (build, sign, answers, events):
  `mvn test-compile surefire:test@benchmark -Dtest=OrderPathBenchmark`. `AccountLatencyBenchmark` times the account
  path per venue: a lane pass from NEW to request on the wire, and a reader's frame to report.
- Format: `mvn spotless:apply`. Spotless (palantir) and checkstyle both run `check` in `validate`, so unformatted code
  fails the build.
- Maven only, never Gradle. Java 17 (set by `gnome-parent`).
- Polymarket signer reference vectors: `scripts/pm_v2_vectors.py` (setup in its docstring); rerun only when the
  CLOB V2 order format changes.

## Layout

All code is under `src/main/java/group/gnometrading/gateways/`.
- `GatewaySupervisor`, `SocketConnectController`, `ConnectLimiter`, `GatewayConfig`, `ReaderPauseControl`: connect,
  reconnect, keep-alive and silence detection shared by feeds and the account reader.
- `feed/`: the market data layer, generic over transport and venue.
  - `Subject`: one venue market, its full-depth `book/Book` (sides grow to 65,536 levels), state machine
    (`SUBSCRIBING -> LIVE <-> SUSPECT -> STALE`, plus `EMPTY`, `QUARANTINED`, `REMOVING`) and liveness; publishes to
    each `StreamOutput` through its `book/Projection` (IDENTITY, or COMPLEMENT for a binary market's NO / short
    listing).
  - `FeedConnection`: one connection's reader (hot path), supervisor and command queue: subscribes in batches paced by
    `RateBudget` (frames a minute) and the writer's room, resyncs, snapshot timeouts (then `EMPTY`), quarantine after
    3 resyncs in 10 minutes (the probe's don't count: those are updates the venue skipped, which a snapshot fixes); a
    roll's release and adopt run on its supervisor. Each `VenueProtocol` declares
    `books()`: CHANGES (Kalshi, Lighter, Polymarket Intl; probed) or WHOLE (Hyperliquid, Polymarket US; not probed).
  - `ConnectionGroup`: a source's connections in a process, packed up to `max_per_connection`, closed when empty for
    5 minutes, rolled make-before-break every 12 h. `Feeds`: streams to markets (shared subjects), groups, the prober,
    quarantine retries.
  - `probe/`: `BookProber` (REST check of the top 5 levels, silence -> `SUSPECT`), `BookProbeClient`, `JsonLevels`.
  - `VenueFeed`: what a venue tells the feed about itself (market mapping, depth, URL property, probe budget).
- `transport/`: `Transport` (`WebSocketTransport` now; FIX later), `FrameReader`, `FrameWriter`/`WebSocketWriter` (MPSC
  queues), shared by feeds and the account reader.
- `orders/`: order state and rules under the account path: `OrderContext`, `OrderReports`, `ReaderNotices`,
  `SubmitPolicy`, `OrderEventSink`, `VenueRejectText`, the result types, `recovery/` (venue order lookup).
- `ratelimit/`: per-account REST limiter (`VenueRateLimiter`, `VenueRateLimits`) shared by every listing on an account;
  `AccountBudget`, the thread-safe budget the account lane draws on without waiting, and `BudgetGate`, a blocking
  `RequestGate` over it at a fixed `RequestClass` for clients reading the account beside the lane (catch-up,
  listings, market loads).
- `account/`: the order gateway's per-account path, in two halves that talk only through `orders/ReaderNotices` (one
  ordered queue) and the released queue back.
  - `account/lane/`: `AccountLane`, one thread sending every session's orders over `AsyncHTTPConnections`, settled
    by `SubmitPolicy`, unknown outcomes swept and in the end given up on; cancels, resends and lookups first, then
    each session's amends and new orders as `FairQueue` decides; each queued request refused after its session's
    wait; a cancel the venue failed to answer sent again up to 3 times in 2 s; every decision told to its
    `Admissions`. `FairQueue` is start-time fair queuing over `LaneSession`s (the queued session charged least, cost
    over weight, goes next; one back after over a second idle starts level; one back sooner, as when its queued
    orders all expired between two of the venue's slots, keeps its place, but never more than a second of the budget
    behind the level), at most `AccountLane.MAX_SESSIONS` per account. A session's `CANCEL_SESSION` fences it: its new
    orders are refused until its next `SESSION`, so none queued behind the cancel reaches the venue. A kill's
    `CANCEL_ORDERS` cancels a session's orders (all, or on one listing) without fencing it. `AccountLane.Caps` caps
    what each session may hold at once and send a second (a one-second token bucket) and what the account may hold;
    a session's `SESSION` may ask for less than the defaults, never more; past a cap a new order is refused
    `SESSION_CAP`, and cancels and amends are never capped. `LaneVenue` per venue; `FairShareTrace` records the
    fair share for a post-mortem.
  - `account/reader/`: `AccountReader` + `AccountConnection` (one account-wide event socket, catch-up over REST
    fetched beside the reader and repeated once the venue's reads settle) with an `AccountProtocol` per venue.
  - `orders/OrderReports` holds the order state and builds every report for this reader.
- `venues/<venue>/`: everything specific to one venue, on top of the generic layers above, which never depend on it.
  `<Venue>Venue` holds its facts (`FEED`, `subjectOf`, and where it has order entry `rateLimits()` and
  `crossMarketOf`: the market a listing's orders can trade against others on, for the order gateway's self-trade
  check); `<Venue>Protocol` its market data (auth, subscribe/unsubscribe/resync, keep-alive, frame decoding); `<Venue>BookProbe` its REST probe
  if it sends book changes; `<Venue>LaneVenue`, `<Venue>AccountProtocol`, signers, requests and order queries its order entry. Venues:
  `kalshi`, `polymarket/intl`, `polymarket/us` (market data and orders), `hyperliquid`, `lighter` (market data only).
- `fix/`: generic FIX transport; nothing in this repo or gnome-orchestrator uses it now that Binance is gone.
- The test jar is published: gnome-orchestrator's order gateway tests use the account path's fakes
  (`account/lane/FakeVenueServer`, `account/lane/TestLaneVenue`, `account/reader/FakeAccountTransport`,
  `account/reader/TextAccountProtocol`), so keep them public and stable.
- Tests mirror the code: generic tests under `feed/`, `orders/` and `account/{lane,reader}/` (whole
  account path tests, which use both halves, in `account/`), each venue's under `venues/<venue>/`.
- `LayoutTest` (ArchUnit) keeps the layers apart: lane and reader use neither each other, `orders/` and `ratelimit/`
  don't use `account/`, nothing outside `venues/` uses it, and `feed/` uses no order code.
- `venues/polymarket/intl/PolymarketIntlCaptureReplay` is a `main()` tool, not a test.

## Conventions and invariants

- Hot path is zero-allocation: feed readers and protocols' `onFrame`, writers' `doWork`, the prober's pass, `Feeds.tick`
  (a session runs it in its JVM), signers, `VenueRejectText`. No boxing, lambdas or string building per message; hold
  method references in fields. Allocation only on connect, error, log, subscribe and roll paths
  (`SocketConnectController` allocates on every connect by design). `FeedAllocationTest` proves the feed's;
  `<Venue>OrderPathAllocationTest` the order path's codecs, signers, event parsers and `SubmitPolicy`, driven through
  the `<Venue>OrderPath` fixtures the benchmark shares.
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
  (3) failed cycles it throws `GatewayConnectFailedException` to the error handler. Forced reconnect when nothing
  arrives for `maxSilentInterval` (30s), and every 12h for order entry; a feed connection is rolled instead.
  `GatewayConfig` validates timeout relationships; keep them valid.
- `ConnectLimiter.forSource` spaces connects per source across the process: Kalshi 2/s, others 5/s.
- Every venue snapshots over its socket. Gap detection: a bad book goes `STALE`, is published once flagged badData, and
  only that market is resynced on its connection. Kalshi checks `seq` over every frame of the connection (a gap
  resyncs all its markets, hence 250 per connection), Lighter each market's nonce chain, Polymarket Intl the venue's
  best bid/ask against the book (wrong only when two completed matches in a row disagree). Hyperliquid and Polymarket
  US send whole books. The REST probe catches the rest.
- Kalshi: `:yes`/`:no` listings share one subject (the ticker); a `:no` listing's book and trades are its complement
  (price `1 - p`, sides flipped), as are its orders. Market orders are refused (Kalshi needs a price; the OMS sends
  priced IOCs).
- Polymarket US: `:long`/`:short` listings share one subject; `:short` is the complement. Feeds hold at most 10
  subscriptions a connection, so markets go in up to 5 groups of 20, a changed group replaced under new ids. No client
  order id and no dedup, so a submit is never resent; events are matched by GTD stamp or (IOC) side+price. FOK refused.
  The account lane batches PM US creates and cancels (`LaneVenue.maxBatch`, 20) whenever two or more wait:
  `POST /v1/orders/batched` answers only ids in request order and `/v1/orders/batched/cancel` only echoes the ids, so
  outcomes come from the order stream as for singles; a 400 naming `order[i]` created nothing, so that entry is
  refused and the rest go again. One alone goes as a single request, whose answer says more.
- Polymarket Intl: `exchangeSecurityId` is `{condition_id}:{token_id}`. Orders are EIP-712 signed (signature types
  0-3, incl. 3 = deposit wallet) and immutable, so no modify; market orders refused.
- Submits with no clear answer: resent only where the venue dedups (`resendsAreSafe`), else looked up. Every OMS
  message must get exactly one report; do not add a path that drops or doubles one.
- Rate limits (`VenueRateLimits`): Kalshi Advanced tier read/write buckets (300/s, burst 900; a by-id lookup costs 2)
  plus a balance bucket, Polymarket Intl per-signer order (held to 36/s of the documented 40) + cancel buckets,
  Polymarket US one bucket at 15/s, burst 6, and order lookups by id on their own at 2/s, burst 2 (its 25/s is counted
  per source IP by Cloudflare over short windows, so every bucket's rate plus burst stay within 25; a breach is a 429
  "error code: 1015"); lookups are on their own because they are refused at 15/s alone, and at 5/s beside 15 cancels.
  Each request falls in a `RequestClass`
  (CANCEL, CATCH_UP, AMEND, NEW, BACKGROUND), which sets the tokens it must leave: cancels none, catch-up half the
  cancel reserve, orders the reserve, background more. The shared limiter (`VenueRateLimiter`) starts below the venue
  rate, halves on 429 and recovers slowly, refuses orders rather than delaying them, and treats catch-up as
  background; `AccountBudget` is the gateway's exact, non-blocking one.
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
  maps to MARKET_CLOSED; Kalshi 409 is a duplicate client id; Kalshi 404 `insufficient_shard_balance` (no cash on
  the exchange index the market trades on) maps to INSUFFICIENT_FUNDS. Polymarket Intl 503 means trading disabled, cancel-only
  or post-only. Polymarket US refusals are all reported as plain venue rejects (no captured texts yet).
- Rate limits: `VENUE_RATE_LIMITED` (venue 429), `RATE_LIMIT_THROTTLING` / `RATE_LIMIT_RECOVERED` (local budget).
  Other processes on the same account share the venue budget.
- "Order context pool exhausted" means more in-flight orders than the reader's pool (the gateway's `gateway.max.orders`).
- Release: every push to `main` runs gnome-build-tools' bump-and-deploy (`mvn verify`, then maven-release-plugin to
  GitHub Packages). Commit prefix picks the bump: `major:` major, `feat:` minor, anything else patch. The release
  fails on SNAPSHOT dependencies, so pin released versions of gnome-core/gnome-networking first.
- Consumed by gnome-orchestrator via the `gnome-gateways` version in its `pom.xml`; a gateway change reaches sessions
  only after that version is bumped and the orchestrator is released.
