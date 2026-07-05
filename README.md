# KiraDB

> **Ki**·**ra**·DB — *Key-value Intelligence, Replication & Availability*

[![Build](https://github.com/kiranukamath/kiradb/actions/workflows/ci.yml/badge.svg)](https://github.com/kiranukamath/kiradb/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-orange.svg)](https://openjdk.org/)
[![Status](https://img.shields.io/badge/status-active%20development-yellow.svg)]()

<!--
  TODO once published (do not add these badges until the artifact actually
  exists — a badge pointing at a 404 is worse than no badge):
  - Docker Hub pulls/version:   https://hub.docker.com/r/kiradb/kiradb
  - Maven/GitHub Packages:      Java SDK (kiradb-client) publish target
  See docs/internals/phase12-golive.md "Kiran must do this" list for the
  exact commands to run before adding these.
-->

⚠️ **Status: Active Development — not production ready yet.** Single-node
today; Raft consensus is implemented and tested at the module level but is
**not yet wired into the server's bootstrap** (`KiraDBServer.main()` starts
one standalone node — no peer discovery, no cluster). See
[docs/deployment.md](docs/deployment.md) for the honest current state and
[docs/internals/phase12-golive.md](docs/internals/phase12-golive.md) for the
gap and how to close it.

**KiraDB** is an open-source distributed database built from the ground up in Java.

The name says it all:
- **Ki** — *Key-value Intelligence* — a storage engine that understands your data access patterns and automatically moves data between memory, SSD, and object storage
- **Ra** — *Replication & Availability* — a Raft-based consensus layer that keeps your data safe and your cluster alive through failures

*Kira* also means "ray of light" in Sanskrit and "glitter" in Japanese — a database that brings clarity to infrastructure that is usually painfully complex.

---

## The Problem

Modern engineering teams stitch together 5 or more systems to handle what should be one concern — data infrastructure:

| Need | Typical Solution | Pain |
|---|---|---|
| Fast key-value store | Redis | Expensive, memory-only, OSS license restricted |
| Persistent storage | Postgres / MySQL | Wrong tool for key-value at scale |
| Feature flags | LaunchDarkly | $400/month for a flag toggle |
| Rate limiting | Custom Redis scripts | Breaks under distribution |
| Config management | Vault + Consul | Operationally complex |
| LLM response caching | DIY | Nobody does it right |

KiraDB ships all of this as **one system**, **one deployment**, **zero external dependencies**.

---

## What KiraDB Is

A distributed database with:

- **Adaptive Tiered Storage** — hot data in memory (MemCache, 35% of heap), warm on NVMe SSD via LSM Tree. Pluggable `TierOrchestrator` interface so promotion/eviction policy can be swapped (rule-based today, AI-driven later). S3 cold tier reserved for backups, not the read path.
- **Raft Consensus** — strong consistency across nodes. Survives leader failures. Quorum-based writes.
- **CRDTs** — conflict-free data structures for high-write workloads where coordination is too expensive: `GCounter`, `PNCounter`, `LWWRegister`, `MVRegister`, `ORSet`.
- **Built-in Feature Flags** — sticky percentage rollout via SHA-256 bucketing, instant kill switch, per-cohort impression/conversion metrics. AI rollout (multi-armed bandit) deferred to a later phase but the metrics that feed it are collected from day one.
- **Distributed Rate Limiter** — sliding-window counter algorithm (Cloudflare/Stripe-style) over CRDT counters. Enforced across the cluster with no coordination on the hot path.
- **Config Store** — append-only history, version-stamped, with live server-push to subscribers via Netty. Subscribers receive `["CFG.NOTIFY", scope, key, value, version, timestamp]` push frames; auto-cleanup on disconnect.
- **Semantic Cache** — cache LLM responses by meaning, not exact string match via vector embeddings + cosine-similarity ANN search. Pluggable embedding provider (local lexical hashing by default, Ollama for real embeddings). `SC.SET` / `SC.GET` / `SC.DEL` / `SC.STATS`.
- **Dashboard** — React + Vite operator UI (cluster overview, storage tiers, live command throughput, flags, rate limiters, config history, semantic cache stats) backed by a read-only HTTP/JSON API on port 8080.
- **Java SDK** — hand-rolled RESP3 client (`kiradb-client`, zero dependencies) with connection pooling and fluent facades: `db.flags()`, `db.rateLimiter()`, `db.config()`, `db.semanticCache()`.
- **Redis/Valkey Compatible** — speaks RESP3. Any existing Redis client works. Drop-in replacement; KiraDB-specific commands (`CRDT.*`, `FLAG.*`, `RL.*`, `CFG.*`, `SC.*`) work via the same `sendCommand` escape hatch every Redis SDK provides — same path RedisJSON, RedisBloom, and RediSearch use.

> **What works today:** v0.1.0–v0.7.0 are shipped (Phases 1–11 of the development plan): RESP3 server, LSM storage engine, Raft consensus (module-level, not yet wired into server bootstrap — see below), tiered storage, CRDTs, feature flags/rate limiter/config store, semantic cache, dashboard, Java SDK, and a first benchmark pass. See [Roadmap](#roadmap) for the version-by-version breakdown, [BENCHMARKS.md](BENCHMARKS.md) for real measured numbers, and [docs/deployment.md](docs/deployment.md) for exactly what "cluster" does and doesn't mean today.

---

## Quick Start

### Docker (single node)

```bash
docker build -f docker/Dockerfile -t kiradb/kiradb:latest .
docker run -p 6379:6379 -p 8080:8080 kiradb/kiradb:latest

# Connect with any Redis client
redis-cli -p 6379 SET hello world
redis-cli -p 6379 GET hello

# Dashboard JSON API
curl http://localhost:8080/api/overview
```

> A published `kiradb/kiradb` image on Docker Hub is a go-live TODO — build
> locally from the Dockerfile until then (see
> [docs/internals/phase12-golive.md](docs/internals/phase12-golive.md)).

```bash
# Or via Docker Compose (same single-node image, add a bind-mounted volume)
docker compose -f docker/docker-compose.yml up
```

### From source (local dev)

```bash
git clone https://github.com/kiranukamath/kiradb.git
cd kiradb
./gradlew build
./gradlew :kiradb-server:run
# or: ./gradlew :kiradb-server:installDist && ./kiradb-server/build/install/kiradb-server/bin/kiradb-server
```

See [Getting Started](docs/getting-started.md) for the full 5-minute walkthrough.

---

## Redis Compatible — Zero Migration Cost

KiraDB speaks **RESP3**, the same protocol as Redis and Valkey.
Your existing clients work without changing a single line of code.

```java
// Jedis
Jedis jedis = new Jedis("localhost", 6379);
jedis.set("user:123", data);

// Lettuce
RedisClient client = RedisClient.create("redis://localhost:6379");

// redis-py
r = redis.Redis(host='localhost', port=6379)

// ioredis (Node.js)
const redis = new Redis({ host: 'localhost', port: 6379 });
```

KiraDB-specific features (semantic cache, feature flags, rate limiter) are available via extended commands and the native Java SDK.

---

## Native Java SDK

```java
KiraDB db = KiraDB.builder()
    .nodes("localhost:6379")
    .build();

// Adaptive key-value (auto-tiered storage)
db.set("session:abc", payload, Duration.ofHours(2));
Optional<String> val = db.get("session:abc");

// Feature flags with AI rollout
boolean enabled = db.flags().isEnabled("new-checkout", userId);

// Distributed rate limiter
RateLimitResult r = db.rateLimiter("payments-api")
    .allow("user:123", 100, Duration.ofMinutes(1));

// Config with live push
db.config().watch("payment-service", change ->
    log.info("Config updated: {} → {}", change.key(), change.newValue()));

// Semantic cache for LLM workloads
db.semanticCache()
    .threshold(0.92f)
    .set(prompt, llmResponse);
Optional<String> cached = db.semanticCache().get(userQuery);
```

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│           CLIENT (Redis CLI / Jedis / SDK)           │
└──────────────────────┬──────────────────────────────┘
                       │  RESP3 over TCP (port 6379)
┌──────────────────────▼──────────────────────────────┐
│              KIRADB SERVER (Netty)                   │
│  ┌──────────────────────────────────────────────┐   │
│  │           BUILT-IN SERVICES                  │   │
│  │  Feature Flags · Rate Limiter · Config Store │   │
│  └──────────────────────┬───────────────────────┘   │
│  ┌──────────────────────▼───────────────────────┐   │
│  │           SEMANTIC CACHE LAYER               │   │
│  │      Vector Embeddings · ANN Search          │   │
│  └──────────────────────┬───────────────────────┘   │
│  ┌──────────────────────▼───────────────────────┐   │
│  │      QUERY ENGINE · MVCC · CRDT MERGE        │   │
│  └──────────────────────┬───────────────────────┘   │
│  ┌──────────────────────▼───────────────────────┐   │
│  │           RAFT CONSENSUS LAYER               │   │
│  │   Leader Election · Log Replication · Quorum │   │
│  └──────────────────────┬───────────────────────┘   │
│  ┌──────────────────────▼───────────────────────┐   │
│  │        ADAPTIVE STORAGE ENGINE               │   │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────┐  │   │
│  │  │  Memory  │→ │   SSD    │→ │    S3     │  │   │
│  │  │  (hot)   │  │  (warm)  │  │  (cold)   │  │   │
│  │  └──────────┘  └──────────┘  └───────────┘  │   │
│  │        WAL · LSM Tree · Bloom Filters        │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

---

## Roadmap

| Version | Milestone | Status |
|---|---|---|
| v0.1.0 | RESP3 server, GET/SET/DEL/PING, in-memory, Docker | ✅ Done |
| v0.2.0 | WAL + LSM Tree persistence (Bloom filters, compaction) | ✅ Done |
| v0.3.0 | Raft 3-node cluster (leader election, log replication) | ✅ Done |
| v0.4.0 | Adaptive tiered storage (MemCache + LSM, pluggable orchestrator) | ✅ Done |
| v0.5.0 | CRDTs (GCounter, PNCounter, LWWRegister, MVRegister, ORSet) | ✅ Done |
| v0.6.0 | Feature flags + distributed rate limiter + config store with live push | ✅ Done |
| v0.7.0 | Semantic cache (vector embeddings, ANN search) | ✅ Done |
| v0.8.0 | Dashboard + Java SDK | ✅ Done |
| v0.9.0 | Benchmarks (JMH + load generator, honest Redis comparison) | ✅ Done |
| v1.0.0 | Docs, Docker/Compose packaging, production hardening pass | 🔨 In Progress (Phase 12) |
| v1.x | Deferred hardening backlog + AI rollout bandit (trigger-driven) | 📋 Planned |

See [CHANGELOG.md](CHANGELOG.md) for the detailed per-phase history and [docs/index.md](docs/index.md) for the full documentation map.

---

## Documentation

| | |
|---|---|
| [docs/index.md](docs/index.md) | Documentation home — where to start |
| [docs/getting-started.md](docs/getting-started.md) | Build and run in 5 minutes |
| [docs/deployment.md](docs/deployment.md) | Docker/Compose, JVM tuning, honest multi-node status |
| [docs/commands/reference.md](docs/commands/reference.md) | Every RESP3 command KiraDB supports |
| [BENCHMARKS.md](BENCHMARKS.md) | Real measured throughput/latency numbers vs. Redis |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Contributing

KiraDB is actively looking for contributors. Whether you are an experienced distributed systems engineer or someone learning the internals for the first time — there is a place for you here.

Read [CONTRIBUTING.md](CONTRIBUTING.md) to get started.
Join the conversation on [Discord](#) and [GitHub Discussions](https://github.com/kiranukamath/kiradb/discussions).

Good first issues are labeled [`good first issue`](https://github.com/kiranukamath/kiradb/labels/good%20first%20issue).

---

## Built With

- **Java 25** — virtual threads, records, sealed classes, `--enable-preview`
- **Netty** — non-blocking TCP server (RESP3) and HTTP server (dashboard API)
- **Custom LSM Tree** — WAL + MemTable + SSTables + Bloom filters + compaction, built from scratch
- **Raft** — consensus algorithm implemented from scratch (module-complete; not yet wired into server bootstrap, see [docs/deployment.md](docs/deployment.md))
- **Vector embeddings + flat cosine ANN** — semantic cache, pluggable embedding provider (local lexical / Ollama)
- **React + Vite** — operator dashboard
- **JMH** — microbenchmarks (see [BENCHMARKS.md](BENCHMARKS.md))

---

## License

Apache 2.0 — see [LICENSE](LICENSE)

KiraDB is free to use in personal and commercial projects.