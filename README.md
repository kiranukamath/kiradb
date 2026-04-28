# kiradb
> A distributed database with adaptive tiered storage (memory → SSD → S3),
> built-in feature flags, rate limiting, config management,
> and semantic caching for AI workloads. Redis/Valkey protocol compatible.

⚠️ Status: Active Development — not production ready yet

# KiraDB

> **Ki**·**ra**·DB — *Key-value Intelligence, Replication & Availability*

[![Build](https://github.com/kiranukamath/kiradb/actions/workflows/ci.yml/badge.svg)](https://github.com/kiranukamath/kiradb/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-orange.svg)](https://openjdk.org/)
[![Status](https://img.shields.io/badge/status-active%20development-yellow.svg)]()

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
- **Semantic Cache** — cache LLM responses by meaning, not exact string match. Reduces LLM API costs by 60–80%. *(In progress — Phase 8)*
- **Redis/Valkey Compatible** — speaks RESP3. Any existing Redis client works. Drop-in replacement; KiraDB-specific commands (`CRDT.*`, `FLAG.*`, `RL.*`, `CFG.*`) work via the same `sendCommand` escape hatch every Redis SDK provides — same path RedisJSON, RedisBloom, and RediSearch use.

> **What works today:** v0.1.0–v0.6.0 are shipped (Phases 1–7 in our development plan). Phase 8 (Semantic Cache) is up next. See [Roadmap](#roadmap) below for status and [docs/services.md](docs/services.md) for working examples of the Phase 7 services.

---

## Quick Start

```bash
# Single node
docker run -p 6379:6379 -p 8080:8080 kiradb/kiradb:latest

# Connect with any Redis client
redis-cli -p 6379 SET hello world
redis-cli -p 6379 GET hello

# 3-node cluster
curl -O https://raw.githubusercontent.com/kiranukamath/kiradb/main/docker/docker-compose.yml
docker compose up
```

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
| v0.7.0 | Semantic cache (vector embeddings, ANN search) | 🔨 In Progress |
| v0.8.0 | Dashboard + Java SDK | 📋 Planned |
| v1.0.0 | Benchmarks, production hardening, full docs | 📋 Planned |

---

## Contributing

KiraDB is actively looking for contributors. Whether you are an experienced distributed systems engineer or someone learning the internals for the first time — there is a place for you here.

Read [CONTRIBUTING.md](CONTRIBUTING.md) to get started.
Join the conversation on [Discord](#) and [GitHub Discussions](https://github.com/kiranukamath/kiradb/discussions).

Good first issues are labeled [`good first issue`](https://github.com/kiranukamath/kiradb/labels/good%20first%20issue).

---

## Built With

- **Java 25** — virtual threads, records, sealed classes
- **Netty** — non-blocking TCP server
- **RocksDB** — embedded storage engine
- **Raft** — consensus algorithm (implemented from scratch)
- **Weaviate** — vector search for semantic cache
- **OpenTelemetry** — distributed tracing

---

## License

Apache 2.0 — see [LICENSE](LICENSE)

KiraDB is free to use in personal and commercial projects.