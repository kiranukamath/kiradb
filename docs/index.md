# KiraDB Documentation

> A distributed database with adaptive tiered storage, built-in feature flags,
> rate limiting, config management, and semantic caching for AI workloads.
> Redis/Valkey protocol compatible — any Redis client works out of the box.

---

## Where to Start

| I want to... | Go here |
|---|---|
| Run KiraDB in 5 minutes | [Getting Started](getting-started.md) |
| Understand the overall design | [Architecture](architecture.md) |
| Understand how Netty works in KiraDB | [Netty Deep Dive](internals/netty.md) |
| Understand the RESP3 protocol | [RESP3 Protocol](internals/resp3-protocol.md) |
| Understand the storage engine | [Storage Engine](internals/storage-engine.md) |
| Understand distributed consensus | [Raft](internals/raft.md) |
| See all supported commands | [Command Reference](commands/reference.md) |
| Contribute to KiraDB | [Contributing](contributing.md) |

---

## Project Status

| Version | Status | What's in it |
|---|---|---|
| v0.1.0 | In development | RESP3 server, GET/SET/DEL/PING, in-memory storage |
| v0.2.0 | Planned | WAL + LSM Tree, persistence |
| v0.3.0 | Planned | Raft consensus, 3-node cluster |
| v0.4.0 | Planned | Tiered storage (SSD + S3) |

---

## Docs Structure

```
docs/
├── index.md                  ← you are here
├── getting-started.md        ← build and run in 5 minutes
├── architecture.md           ← high-level system design
├── contributing.md           ← how to contribute
│
├── internals/                ← deep dives into each subsystem
│   ├── netty.md              ← networking layer (Netty event loop, pipeline)
│   ├── resp3-protocol.md     ← RESP3 wire protocol
│   ├── storage-engine.md     ← WAL, MemTable, SSTable, LSM Tree
│   ├── raft.md               ← distributed consensus
│   └── crdts.md              ← conflict-free data structures
│
└── commands/
    └── reference.md          ← all supported RESP3 commands
```
