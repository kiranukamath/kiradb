# KiraDB Documentation

> A distributed database with adaptive tiered storage, built-in feature flags,
> rate limiting, config management, and semantic caching for AI workloads.
> Redis/Valkey protocol compatible — any Redis client works out of the box.

---

## Where to Start

| I want to... | Go here |
|---|---|
| Run KiraDB in 5 minutes | [Getting Started](getting-started.md) |
| Deploy with Docker/Compose, tune the JVM, know the real multi-node status | [Deployment Guide](deployment.md) |
| Use feature flags / rate limiter / config store | [Built-in Services](services.md) |
| Understand CRDTs (counters, sets, registers) | [CRDTs Deep Dive](internals/crdts.md) |
| Understand the math behind semantic caching | [Semantic Cache Math](internals/semantic-cache-math.md) |
| Understand the semantic cache implementation | [Phase 8 — Semantic Cache](internals/phase8-semantic-cache.md) |
| Understand how Netty works in KiraDB | [Netty Deep Dive](internals/netty.md) |
| Use the dashboard / HTTP ops API | [Phase 9 — Dashboard & HTTP API](internals/phase9-dashboard.md) |
| Use the Java SDK | [SDK Guide](sdk.md) / [Phase 10 — SDK Internals](internals/phase10-sdk.md) |
| Manually verify Raft cluster behavior | [Raft Manual Test](internals/raft-manual-test.md) |
| See real measured performance numbers | [BENCHMARKS.md](../BENCHMARKS.md) / [Phase 11 — Benchmarks](internals/phase11-benchmarks.md) |
| See all supported commands | [Command Reference](commands/reference.md) |
| Understand Docker packaging, go-live prep, and open production gaps | [Phase 12 — Documentation & Go-Live](internals/phase12-golive.md) |
| Understand the Phase 13 hardening backlog work (flag index, CFG.ROLLBACK, byte-based MemCache, AccessTracker cap, token bucket, CRDT.MERGE, metrics) | [Phase 13 — Deferred Hardening](internals/phase13-hardening.md) |
| Contribute to KiraDB | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| See version-by-version history | [CHANGELOG.md](../CHANGELOG.md) |

---

## Project Status

| Version | Status | What's in it |
|---|---|---|
| v0.1.0 | ✅ Done | RESP3 server: GET/SET/DEL/PING/EXISTS/EXPIRE/TTL |
| v0.2.0 | ✅ Done | Storage engine: WAL + MemTable + SSTables + LSM Tree + Bloom Filters + Compaction |
| v0.3.0 | ✅ Done | Raft consensus: leader election, log replication, AppendEntries RPCs |
| v0.4.0 | ✅ Done | Adaptive tiered storage (MemCache + LSM, pluggable `TierOrchestrator`) |
| v0.5.0 | ✅ Done | CRDTs: GCounter, PNCounter, LWWRegister, MVRegister, ORSet (+ `CRDT.*` commands) |
| v0.6.0 | ✅ Done | Built-in services: feature flags, distributed rate limiter, config store with server-push |
| v0.7.0 | ✅ Done | Semantic cache (vector embeddings, ANN search) |
| v0.8.0 | ✅ Done | Dashboard + Java SDK |
| v0.9.0 | ✅ Done | Benchmarks (JMH + load generator, honest Redis comparison) |
| v1.0.0 | 🔨 In Progress | Docs, Docker/Compose packaging, production hardening (Phase 12) |

> Raft consensus (v0.3.0) is implemented and tested at the module level but
> is **not wired into `KiraDBServer.main()`** — see
> [Deployment Guide](deployment.md) for the honest current state.

---

## Docs Structure

```
docs/
├── index.md                       ← you are here
├── getting-started.md             ← build and run in 5 minutes
├── deployment.md                  ← Docker/Compose, JVM tuning, honest multi-node status (Phase 12)
├── services.md                    ← practical guide to FLAG.*, RL.*, CFG.*
├── sdk.md                         ← Java SDK usage guide (Phase 10)
│
├── internals/                     ← deep dives into each subsystem
│   ├── netty.md                   ← networking layer (Netty event loop, pipeline)
│   ├── treemap.md                 ← TreeMap behavior used by MemTable
│   ├── raft-manual-test.md        ← steps to manually verify Raft cluster behaviour
│   ├── crdts.md                   ← state-based CRDTs as implemented in kiradb-crdt
│   ├── semantic-cache-math.md     ← embeddings, cosine similarity, ANN search (Phase 8)
│   ├── phase8-semantic-cache.md   ← semantic cache design + implementation (SC.* commands)
│   ├── phase9-dashboard.md        ← HTTP ops API (port 8080) + React dashboard (Phase 9)
│   ├── phase10-sdk.md             ← hand-rolled RESP3 client, pooling, watch design (Phase 10)
│   ├── phase11-benchmarks.md      ← JMH methodology, what the numbers imply (Phase 11)
│   ├── phase12-golive.md          ← Docker packaging, go-live prep, honest gaps (Phase 12)
│   └── phase13-hardening.md       ← deferred hardening backlog: what shipped, what's still deferred (Phase 13)
│
└── commands/
    └── reference.md               ← all supported RESP3 commands (incl. CRDT.*, FLAG.*, RL.*, CFG.*, SC.*)
```

Repo-root docs referenced above but not under `docs/`: [`../BENCHMARKS.md`](../BENCHMARKS.md), [`../CHANGELOG.md`](../CHANGELOG.md).
