# Changelog

All notable changes to KiraDB are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
KiraDB does not yet follow strict Semantic Versioning tags in git (no `v0.x.0`
tags have been cut as of this writing) — versions below map to the
`## Version Roadmap` table in `CLAUDE.md` and to the phase each set of changes
was built in. Dates are the actual commit dates from `git log`, grouped by the
phase/PR that introduced them; where a version's functionality spans multiple
commits, the date range covers the whole span.

---

## [Unreleased] — Phase 12: Documentation + Go-Live Prep

### Added
- `docker/Dockerfile` — multi-stage build (JDK 25 build stage → JRE 25 runtime
  stage), non-root user, `installDist`-based distribution.
- `docker/docker-compose.yml` — single-node service (real, working) plus a
  clearly-labeled aspirational 3-node cluster profile matching the original
  Phase 12 plan (Raft/peer-discovery wiring for that layout is not yet built —
  see `docs/internals/phase12-golive.md`).
- `docs/deployment.md` — deployment guide covering single-node quickstart,
  Docker/Compose usage, JVM/heap tuning for the tiered storage MemCache, and
  the honest state of multi-node bootstrap.
- `mkdocs.yml` — MkDocs Material site configuration with nav matching the
  `docs/` tree, so `docs/architecture.md` etc. can deploy to GitHub Pages.
- `docs/internals/phase12-golive.md` — design notes for this phase, including
  a "How to make this better" section covering health check endpoints,
  graceful shutdown, structured logging, and image vulnerability scanning.
- `CHANGELOG.md` (this file).

### Changed
- `README.md` — refreshed to describe the actually-shipped feature set
  (Phases 1–11), added Quick Start via the new Dockerfile, linked docs/
  BENCHMARKS.md/CHANGELOG.md, corrected the roadmap table.

---

## v0.7.0 — Phase 11: Benchmarks & Hardening
*(2026-07-05)*

### Added
- `kiradb-benchmark` module: JMH microbenchmarks (`KvThroughputBenchmark`,
  `TierLatencyBenchmark`, `SemanticCacheOverheadBenchmark`), a correctness
  test (`RateLimiterAccuracyTest`), and a manual `LoadGenerator` tool.
- `BENCHMARKS.md` — real measured throughput/latency numbers with methodology
  and an honest comparison against Redis (`docs/internals/phase11-benchmarks.md`
  has the reviewer-facing design rationale).

## v0.6.0 — Phase 9 + 10: Dashboard & Java SDK
*(2026-07-05)*

### Added
- Read-only HTTP/JSON dashboard API on port 8080 (`HttpApiServer`):
  `/api/overview`, `/api/commands`, `/api/storage`, `/api/flags`,
  `/api/ratelimit`, `/api/config/scopes`, `/api/semantic-cache`.
- `CommandMetrics` — per-command-type latency/throughput tracking feeding the
  dashboard's Commands page.
- `kiradb-dashboard` — React + Vite operator UI: Cluster Overview, Storage,
  Commands, Feature Flags, Rate Limiters, Config Store, Semantic Cache, and a
  Raft Inspector page.
- `kiradb-client` — hand-rolled, zero-dependency Java RESP3 SDK: connection
  pooling (`ConnectionPool`), core KV methods, and fluent facades
  (`FlagsClient`, `RateLimiterClient`, `ConfigClient` with `CFG.WATCH`
  callback support via `WatchDispatcher`, `SemanticCacheClient`).

(See `docs/internals/phase9-dashboard.md` and `docs/internals/phase10-sdk.md`.)

## v0.5.0 — Phase 8: Semantic Cache
*(2026-07-05)*

### Added
- `kiradb-semantic-cache` module and `SC.SET` / `SC.GET` / `SC.DEL` /
  `SC.STATS` RESP3 commands.
- Pluggable `EmbeddingProvider` interface: `LexicalEmbeddingProvider` (default,
  no external dependency, hashing-based) and `OllamaEmbeddingProvider` (opt-in,
  calls a local Ollama daemon for real sentence embeddings).
- `FlatCosineIndex` — exact cosine-similarity ANN search, adequate at the
  cache sizes KiraDB targets (see `docs/internals/semantic-cache-math.md` for
  why flat/exact was chosen over HNSW or a Weaviate sidecar at this stage).
- Configurable similarity threshold (default 0.85) and TTL support on cached
  entries.

(See `docs/internals/phase8-semantic-cache.md`.)

## v0.4.0 — Phase 7: Built-in Services
*(2026-04-27 – 2026-04-28)*

### Added
- **Feature flags**: `FLAG.SET` / `GET` / `LIST` / `KILL` / `UNKILL` /
  `CONVERT` / `STATS`, backed by `LWWRegister` CRDT state plus four
  `GCounter`s per flag for per-cohort impression/conversion metrics. Sticky
  SHA-256 bucketing for deterministic percentage rollout.
- **Distributed rate limiter**: `RL.ALLOW` / `STATUS` / `RESET`, sliding-window
  algorithm over `GCounter` CRDT state. Verified with a 3-node enforcement
  test (`distributedEnforcementAcrossThreeNodes`).
- **Config store**: `CFG.SET` / `GET` / `HIST` / `WATCH` / `UNWATCH`,
  append-only version history, live server-push notifications via
  `ConfigSubscriptionRegistry` (RESP3 `CFG.NOTIFY` push frame, auto-cleanup on
  disconnect).

### Fixed
- LWW same-node-write bug: successive writes from the same node within one
  millisecond used to lose to the LWW tiebreaker because they shared a
  timestamp. Fixed by giving local writes monotonically-advancing timestamps;
  regression test `rapidSameNodeRewritesAllSucceed` added.

(See `docs/services.md` and `CLAUDE.md`'s Phase 7 section.)

## v0.3.0 — Phase 6: CRDTs
*(2026-04-26 – 2026-04-27)*

### Added
- Five CRDT types with `merge()`: `GCounter`, `PNCounter`, `LWWRegister`,
  `ORSet`, `MVRegister`.
- CRDT storage namespace (`crdt:g:`, `crdt:pn:`, `crdt:lww:`, `crdt:mv:`,
  `crdt:or:`) and RESP3 commands: `CRDT.INCR`, `CRDT.GET`, `CRDT.MERGE`,
  `CRDT.PNADD`/`PNGET`, `CRDT.LWWSET`/`LWWGET`, `CRDT.MVSET`/`MVGET`,
  `CRDT.SADD`/`SREM`/`SMEMBERS`.
- Convergence test proving two independently-updated replicas merge to the
  same state.

(See `docs/internals/crdts.md`.)

## v0.2.0 — Phase 5: Adaptive Tiered Storage
*(2026-04-25)*

### Added
- `TierOrchestrator` interface with a `RuleBasedOrchestrator` implementation
  (static access-score thresholds); designed so an AI-driven orchestrator can
  be a drop-in replacement later.
- `AccessTracker` — per-key, time-decayed access score.
- `MemCache` (Tier 1, bounded, evicts lowest-score entry when full) and
  `TieredStorageEngine` orchestrating MemCache + the LSM tree (Tier 2).
  Reads never block on promotion; promotion happens asynchronously after the
  client's read is already satisfied.
- `TierManager` background thread re-evaluating tiering decisions
  periodically.

(See `CLAUDE.md`'s Phase 5 section for the full terminology and memory-sizing
tables that came out of this phase.)

## v0.1.1 — Phase 4: Raft Consensus
*(2026-04-05 – 2026-04-19)*

### Added
- `kiradb-raft` module: `RaftState` (FOLLOWER/CANDIDATE/LEADER), randomized
  election timeout, `RequestVote`/`AppendEntries` RPCs, leader heartbeats,
  commit index advancement, Raft log persistence separate from the storage
  WAL.
- Manual 3-node cluster demo and testing guide
  (`docs/internals/raft-manual-test.md`) proving leader election, log
  replication, and failover entirely in-process.

> **Note:** Raft is implemented and tested at the module level. As of this
> writing it is **not wired into `KiraDBServer.main()`** — the server process
> boots a single standalone node. See `docs/deployment.md` and
> `docs/internals/phase12-golive.md` for the gap and what closing it requires.

## v0.1.0 — Phases 1–3: Skeleton, RESP3 Server, Storage Engine
*(2026-03-29 – 2026-04-03)*

### Added
- Multi-module Gradle project (Java 25 toolchain, `--enable-preview`,
  Checkstyle Google style), CI on GitHub Actions.
- Netty-based RESP3 server: `PING`, `SET`/`GET`/`DEL`/`EXISTS`, `EXPIRE`/`TTL`
  and millisecond variants (`PSETEX`/`PEXPIRE`/`PTTL`).
- `StorageEngine` abstraction with a from-scratch LSM tree: write-ahead log
  (CRC32-checksummed), `MemTable`, immutable `SSTable`s, a from-scratch Bloom
  filter, and background compaction. Crash-recovery and compaction-integrity
  tests.

(See `docs/internals/netty.md` and `docs/internals/treemap.md`.)
