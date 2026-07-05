# Deployment Guide

This guide covers running KiraDB as a single node (the only mode that exists
today), via Docker/Compose, JVM/heap tuning, and the honest current state of
multi-node/cluster deployment.

---

## Single-node quickstart

### Option A — run the jar/distribution directly

```bash
./gradlew :kiradb-server:installDist
./kiradb-server/build/install/kiradb-server/bin/kiradb-server
```

or, for local iteration:

```bash
./gradlew :kiradb-server:run
```

Both paths run `io.kiradb.server.KiraDBServer#main`, which:

1. Opens (or creates) an `LsmStorageEngine` at `-Dkiradb.data.dir` (default `./data`).
2. Wraps it in a `TieredStorageEngine` (MemCache + LSM).
3. Creates a single-node `CrdtStore`.
4. Registers `FLAG.*`, `RL.*`, `CFG.*`, `SC.*` command handlers.
5. Starts the RESP3 listener on port **6379** and the HTTP dashboard API on
   port **8080**.

No flags are required for a working single node.

### Option B — Docker

```bash
docker build -f docker/Dockerfile -t kiradb/kiradb:latest .
docker run -p 6379:6379 -p 8080:8080 -v kiradb-data:/data kiradb/kiradb:latest
```

See [`docker/Dockerfile`](../docker/Dockerfile) for what's inside — a
multi-stage build (JDK 25 to compile, JRE 25 to run) producing a
non-root-user image built from Gradle's `installDist` output.

### Option C — Docker Compose

```bash
docker compose -f docker/docker-compose.yml up
```

This starts the same single-node image with a named volume for `/data`. See
the next section for why this is the *only* real service in that file today.

---

## Multi-node: the honest current state

**KiraDB does not run as a cluster today.** This is the most important thing
to understand before deploying it anywhere that assumes otherwise.

What exists:

- `kiradb-raft` is a complete, independently-tested Raft implementation:
  leader election with randomized timeouts, `RequestVote`/`AppendEntries`
  RPCs, log replication, commit-index advancement, and its own log
  persistence. [`docs/internals/raft-manual-test.md`](internals/raft-manual-test.md)
  walks through running a 3-node Raft cluster **in one JVM process**, driven
  entirely from a `main()` method in the `kiradb-raft` test sources.
- CRDTs (`kiradb-crdt`) prove convergence in-process — two replicas that
  independently update the same counter/set/register merge to an identical
  state when `merge()` is called directly.

What does **not** exist:

- `KiraDBServer.main()` — the actual server entry point — never constructs a
  `RaftNode`, never opens port 7379, and never reads a peer list. Grep
  confirms it: there is no `KIRA_PEERS`, no `KIRA_NODE_ID` environment
  variable, and no Raft class referenced anywhere in
  `kiradb-server/src/main/java/io/kiradb/server/KiraDBServer.java`. The only
  identity knob it reads is the `-Dkiradb.node.id` **system property**,
  which feeds `CrdtStore`'s node id for CRDT slot ownership on that single
  process — it has no effect on clustering because there is nothing to
  cluster with.
- There is no gossip wire, no cross-process CRDT state exchange, and no
  request forwarding. Running three KiraDB containers today gives you three
  completely independent, non-communicating single-node databases that
  happen to share a Docker network.

The `docker/docker-compose.yml` file's commented-out 3-node block reflects
the *target* topology from the original project plan (`KIRA_NODE_ID` /
`KIRA_PEERS` env vars, port 7379 for Raft RPCs) — it is included so the
intended shape is visible, but it is explicitly documented in that file as
non-functional for real clustering until the bootstrap wiring described in
[`docs/internals/phase12-golive.md`](internals/phase12-golive.md) is built.

**If you need a real cluster today**, the closest thing that exists is
manually running the `RaftClusterDemo`-style setup from
`docs/internals/raft-manual-test.md` and wiring your own bridge from
committed Raft entries into a `TieredStorageEngine` — that is genuinely a
coding task, not a configuration one.

---

## JVM / heap tuning

The tiered storage design target (see `CLAUDE.md` Phase 5) is a MemCache
(Tier 1, hot data) sized at roughly **35% of the JVM heap**. As implemented
today, that 35%-of-heap figure is a *design target*, not a live
computation — `MemCache`'s actual capacity knob is an **entry count**, not a
byte budget:

```java
// kiradb-core/src/main/java/io/kiradb/core/storage/tier/MemCache.java
public static final int DEFAULT_MAX_ENTRIES = 1_000_000;
```

wired from `KiraDBServer.main()` via a system property:

```bash
java --enable-preview \
     -Dkiradb.memcache.max.entries=2000000 \
     -jar kiradb-server/build/libs/kiradb-server.jar
```

or, in Docker, via `JAVA_OPTS`:

```bash
docker run -e JAVA_OPTS="-Dkiradb.memcache.max.entries=2000000" \
    -p 6379:6379 kiradb/kiradb:latest
```

Because capacity is entry-count-based rather than byte-based, the actual
percentage of heap MemCache occupies depends entirely on your average
value size — this is exactly the gap tracked as **"Byte-based MemCache
capacity"** in `CLAUDE.md`'s Phase 13 (Deferred Hardening Backlog). Until
that lands, size `-Dkiradb.memcache.max.entries` yourself:

```
max.entries ≈ (0.35 × -Xmx) / avg_entry_size_bytes
```

where `avg_entry_size_bytes` for a HOT entry is roughly `78 + ceil8(K+16) +
ceil8(V+16)` per `CLAUDE.md`'s memory footprint reference (K = key bytes, V =
value bytes).

Other JVM flags worth setting explicitly in production:

| Flag | Why |
|---|---|
| `-Xmx` / `-Xms` | Set both to the same value to avoid heap resize pauses. Budget MemCache at ~35% of `-Xmx` per above. |
| `--enable-preview` | Required — KiraDB compiles against Java 25 preview features. Already baked into the Gradle `application` launch script and the Dockerfile; only needed manually if you build your own classpath/launcher. |
| `-XX:+UseZGC` (or G1) | Not yet benchmarked by KiraDB itself — see [`BENCHMARKS.md`](../BENCHMARKS.md) for what *has* been measured (Apple M2, default collector). Treat GC tuning as untested territory for now. |

---

## Health checks for orchestrators

There is no dedicated `/health` or `/ready` endpoint today. The closest
substitute is:

```bash
curl -f http://localhost:8080/api/overview
```

which returns node id, uptime, version, and role (`"role":"standalone"`
today — see the multi-node section above for why). It is a reasonable
Kubernetes liveness probe in the short term (a non-2xx or connection refusal
means the HTTP server thread is dead) but is **not** a true readiness
probe — it does not check storage-engine health, disk space, or WAL write
capability. See
[`docs/internals/phase12-golive.md`](internals/phase12-golive.md#how-to-make-this-better)
for why a dedicated `/health` endpoint is a real gap and what it should check.

---

## Graceful shutdown

`KiraDBServer.main()` registers JVM shutdown hooks
(`Runtime.getRuntime().addShutdownHook(...)`) that close the
`TieredStorageEngine` (which in turn stops the `TierManager` background
thread and closes the underlying LSM engine, flushing the WAL) and the
`HttpApiServer`. This means a `SIGTERM` (what `docker stop` and Kubernetes
pod termination send by default) triggers an orderly shutdown rather than a
hard kill — but see
[`docs/internals/phase12-golive.md`](internals/phase12-golive.md#how-to-make-this-better)
for what's *not* covered (in-flight RESP3 connections are not drained before
the storage engine closes underneath them).

---

## Logging

KiraDB uses SLF4J + Logback with the default (non-JSON) console pattern. For
container/Kubernetes deployments where log aggregators expect structured
JSON, see
[`docs/internals/phase12-golive.md`](internals/phase12-golive.md#how-to-make-this-better) —
this is called out there as a real, unaddressed gap rather than something
this phase solved.
