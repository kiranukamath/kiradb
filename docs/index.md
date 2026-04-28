# KiraDB Documentation

> A distributed database with adaptive tiered storage, built-in feature flags,
> rate limiting, config management, and semantic caching for AI workloads.
> Redis/Valkey protocol compatible — any Redis client works out of the box.

---

## Where to Start

| I want to... | Go here |
|---|---|
| Run KiraDB in 5 minutes | [Getting Started](getting-started.md) |
| Use feature flags / rate limiter / config store | [Built-in Services](services.md) |
| Understand CRDTs (counters, sets, registers) | [CRDTs Deep Dive](internals/crdts.md) |
| Understand how Netty works in KiraDB | [Netty Deep Dive](internals/netty.md) |
| Manually verify Raft cluster behavior | [Raft Manual Test](internals/raft-manual-test.md) |
| See all supported commands | [Command Reference](commands/reference.md) |
| Contribute to KiraDB | [CONTRIBUTING.md](../CONTRIBUTING.md) |

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
| v0.7.0 | 🔨 In Progress | Semantic cache (vector embeddings, ANN search) |
| v0.8.0 | 📋 Planned | Dashboard + Java SDK |
| v1.0.0 | 📋 Planned | Benchmarks, production hardening, full docs |

---

## Docs Structure

```
docs/
├── index.md                       ← you are here
├── getting-started.md             ← build and run in 5 minutes
├── services.md                    ← practical guide to FLAG.*, RL.*, CFG.*
│
├── internals/                     ← deep dives into each subsystem
│   ├── netty.md                   ← networking layer (Netty event loop, pipeline)
│   ├── treemap.md                 ← TreeMap behavior used by MemTable
│   ├── raft-manual-test.md        ← steps to manually verify Raft cluster behaviour
│   └── crdts.md                   ← state-based CRDTs as implemented in kiradb-crdt
│
└── commands/
    └── reference.md               ← all supported RESP3 commands (incl. CRDT.*, FLAG.*, RL.*, CFG.*)
```
