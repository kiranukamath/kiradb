# Phase 9 — Dashboard & HTTP Ops API

Phase 9 adds an operator-facing view of a running KiraDB node: a read-only
HTTP/JSON API on port **8080** and a React dashboard that polls it. This document
explains the design decisions the way a principal engineer would defend them in
review — including the shortcuts we took and exactly why they are acceptable
*here* and not in general.

---

## Why a separate HTTP port instead of extending RESP3?

Port 6379 is the **data plane**: a protocol optimized for high-frequency
key-value traffic, spoken by Redis clients. The dashboard is the **ops plane**:
a browser wants HTTP, JSON, and CORS headers — none of which belong in a RESP3
decoder.

Mixing the two on one port would mean sniffing the first bytes of every
connection to decide which decoder to install. That is fragile, complicates the
Netty pipeline, and — most importantly — couples the two planes operationally:
an operator debugging an incident should never compete with client traffic for
the same accept queue, and a firewall rule should be able to say "clients reach
6379, only the ops network reaches 8080." Redis made the same call: RESP on
6379, observability via separate exporters. So does almost every serious
database (Postgres wire protocol vs. pg_exporter, Kafka protocol vs. JMX/HTTP).

```
Port 6379  — data plane   (RESP3: SET/GET, FLAG.*, RL.*, CFG.*, SC.*)
Port 7379  — cluster plane (Raft RPCs — module exists, not wired yet)
Port 8080  — ops plane    (HTTP/JSON, read-only; -Dkiradb.http.port to change)
```

## How the HTTP pipeline differs from the RESP3 pipeline

Both servers are Netty, but the pipelines are built differently — and the
difference is instructive:

| | RESP3 (6379) | HTTP (8080) |
|---|---|---|
| Decoder | Hand-written `Resp3Decoder` | Stock `HttpServerCodec` |
| Framing | Streaming, per-connection buffer state | `HttpObjectAggregator` assembles one `FullHttpRequest` |
| Handler sharing | Decoder **not** sharable (per-connection state) | Handler `@Sharable` (stateless per request) |
| Connection | Long-lived, pipelined commands | One request/response, then close |

Aggregating full requests in memory would be wrong for a data plane (a client
could stream megabytes), but dashboard requests are tiny GETs — aggregation
buys a dramatically simpler handler for zero practical cost. Knowing *when*
aggregation is acceptable is the transferable lesson.

## CommandMetrics: reservoir sampling vs. HdrHistogram

The `/api/commands` endpoint needs p50/p95/p99 latency per command. Exact
percentiles require keeping every sample (unbounded memory) or a
bucketed histogram. We chose a third option: a **fixed 1024-sample reservoir
per command** (Vitter's Algorithm R), where every recorded latency has equal
probability of being retained.

Honest tradeoffs, also documented in the class Javadoc:

- **Tail variance.** p99 of 1024 samples is roughly "the 10 worst samples" — a
  single outlier visibly moves it. Fine for a human glancing at a dashboard,
  not fine for alerting on an SLO.
- **Lifetime window.** The reservoir spans the whole process lifetime; a latency
  regression that started five minutes ago is diluted by hours of old samples.
- **Benign races.** Reads and writes to the reservoir are deliberately unlocked.
  A snapshot may mix samples from slightly different instants — statistically
  irrelevant, and it keeps the hot path to two `LongAdder` increments plus one
  array write.

Production-grade systems use **HdrHistogram**: bounded relative error (e.g.
every value within 1% of truth), lock-free recording, and *interval* snapshots
(windowed percentiles). We didn't, because it's a dependency and an API to
learn for a dashboard refreshed every 2 seconds — but the moment KiraDB alerts
on latency (Phase 11 benchmarks), reservoirs are the wrong tool. The
`CommandMetrics` seam makes the swap local: `CommandRouter` only calls
`record(name, micros, error)`.

Counters use `LongAdder`, not `AtomicLong`: under contention `LongAdder`
stripes across cache lines instead of CAS-spinning, so 32 threads hammering
`SET` don't serialize on one counter.

**Opt-in wiring.** `CommandRouter.setCommandMetrics(...)` defaults to null —
existing tests and metric-less embeddings pay literally zero overhead (one
volatile read and a branch). `KiraDBServer.main()` always wires it.

## CORS: `Access-Control-Allow-Origin: *`

The Vite dev server runs on `localhost:5173`; the API on `localhost:8080`.
Different origins, so the browser demands CORS headers. We send the wildcard
because the API is **read-only and carries no credentials** — there is nothing
a malicious cross-origin page can mutate, and nothing secret it can exfiltrate
that it couldn't get by connecting to 6379 directly. The moment this API grows
a POST (flag toggles, config rollback from the UI) the wildcard must be
replaced by an allowlist *and* authentication — see "How to make this better."

## Polling instead of WebSocket — a deliberate deviation

The original Phase 9 plan said "WebSocket connection to port 8080 for live data
streaming." We shipped a 2-second polling hook (`useApi`) instead:

- The HTTP API had to exist anyway (curl-ability is an ops feature in itself).
- At dashboard refresh rates, 2s polling is indistinguishable from push. Push
  earns its complexity when events must arrive *now* (log tail, election
  events) or when thousands of clients would otherwise poll.
- WebSocket adds a session registry, heartbeats, reconnect logic, and a
  fan-out path on the server — real code that would have diluted this phase.

The upgrade trigger: when the Raft inspector needs election events in real
time, add a `WebSocketServerProtocolHandler` route on the same port and keep
polling as the fallback. The `useApi` hook is the seam — pages don't know
where data comes from.

## What a reviewer should scrutinize

- **Metrics overhead on the hot path.** Every routed command now passes through
  `System.nanoTime()` twice, two `LongAdder` increments, and one
  `ThreadLocalRandom` draw once the reservoir is full. That's tens of
  nanoseconds against handlers that touch a WAL — negligible, but Phase 11
  benchmarks should confirm rather than assume.
- **Unbounded per-command map.** `CommandMetrics` allocates ~8 KB per distinct
  command *name*. Names come from a fixed registry — unknown commands are
  rejected by the router but still recorded (an error is a data point). A
  client spraying random command names grows the map without bound. Acceptable
  today (single-tenant, trusted network); a hard cap or "only registered
  commands get reservoirs" is the fix if that assumption changes.
- **`/api/config/scopes` does a prefix scan** of `cfg:*` on every request, then
  re-reads each record for its latest version. Fine at tens of config entries;
  at thousands this needs a cached index.
- **Snapshot consistency.** Endpoints read live structures without a global
  lock — `memCacheSize` and `trackedKeys` may be from instants microseconds
  apart. For observability this is correct engineering, not sloppiness; just
  never feed these numbers into control logic.
- **No auth on the ops port** — see below. This is the one that must not ship
  past a trusted-network deployment.

## How to make this better

- **WebSocket push** for the Commands and Raft pages once event-shaped data
  exists (elections, tier migrations). Keep polling as fallback.
- **Micrometer + Prometheus export** — already a Phase 13 backlog item
  ("Metric publication"). The right end state: `CommandMetrics` and the tier
  stats become Micrometer meters, `/metrics` serves the Prometheus text format,
  and the JSON API becomes a thin view over the same registry instead of a
  parallel bookkeeping system.
- **Auth on the ops port.** Even read-only telemetry leaks (key counts, command
  mix, config values — config *values* are the sensitive one). Minimum viable:
  a bearer token via system property checked in the handler; real answer:
  mTLS between dashboard and node, and drop the CORS wildcard.
- **Historical time-series.** Every endpoint reports *instantaneous* state; the
  throughput chart is really "cumulative counts right now." A small ring buffer
  of per-second snapshots server-side (or Prometheus scraping, above) enables
  real rate charts — `rate(command_count[1m])` — which is what operators
  actually reason about.
- **Windowed percentiles.** Replace the lifetime reservoir with two alternating
  reservoirs flipped every N seconds, or HdrHistogram interval recorders, so
  p99 reflects *recent* behavior.

## File map

| File | Role |
|---|---|
| `kiradb-server/.../metrics/CommandMetrics.java` | Per-command counters + latency reservoir |
| `kiradb-server/.../metrics/CommandMetric.java` | Immutable snapshot record |
| `kiradb-server/.../http/HttpApiServer.java` | Netty HTTP server, all endpoints |
| `kiradb-server/.../http/DashboardContext.java` | Record bundling stores + metrics for the API |
| `kiradb-server/.../command/CommandRouter.java` | `setCommandMetrics()` + timed dispatch |
| `kiradb-core/.../tier/TieredStorageEngine.java` | Read-only observability accessors |
| `kiradb-dashboard/` | Vite + React 18 + Tailwind + Recharts app |
| `kiradb-dashboard/src/useApi.ts` | The polling hook (the WebSocket seam) |
