# KiraDB Benchmarks

> Phase 11. All numbers on this page were measured by actually running the benchmarks in
> this repository on the machine described below — nothing here is estimated or fabricated.
> Re-run them yourself with the commands given in each section; your numbers will differ from
> the ones on this page (different machine, different day, different JVM warm state), and that
> is expected. Treat the *shape* of the results (which operations are fast, which are slow, by
> roughly what multiple) as the durable takeaway, not the exact digits.

---

## Methodology

### Machine

| | |
|---|---|
| Model | MacBook Air (Apple Silicon) |
| CPU | Apple M2 |
| OS | macOS (Darwin 25.5.0, arm64) |
| JVM | OpenJDK 25 (Homebrew build), `--enable-preview` |

Obtained via `uname -a` and `sysctl -n machdep.cpu.brand_string` on the machine that produced
every number in this document.

### Tooling

- **JMH 1.37** (`org.openjdk.jmh:jmh-core` / `jmh-generator-annprocess`) for the throughput and
  latency micro-benchmarks. No external JMH Gradle plugin is used — see
  `kiradb-benchmark/build.gradle` for why (it would fight the root project's
  `--enable-preview` toolchain flag) and how the `jmh` Gradle task is wired instead.
- **JUnit 5** for the rate limiter accuracy test — that question ("does enforcement stay within
  tolerance under concurrent load") is a correctness assertion, not a performance number, so
  JMH's statistical machinery doesn't apply and a plain test is the more honest tool.
- **JMH configuration used for every benchmark in this document:** 3 warmup iterations, 5
  measurement iterations, 1 second per iteration, 1 fork. This is a deliberately
  laptop-friendly configuration — the full suite runs in about 75 seconds. See
  `docs/internals/phase11-benchmarks.md` for what a more rigorous configuration (more
  iterations, more forks, longer iterations) would change and why it wasn't used here.

### How to reproduce

```bash
# JMH throughput + latency benchmarks (~75s on the reference machine)
./gradlew :kiradb-benchmark:jmh

# Run a subset by class/method name regex
./gradlew :kiradb-benchmark:jmh -Pjmh.include=KvThroughput

# Rate limiter accuracy (JUnit, not JMH)
./gradlew :kiradb-benchmark:test --tests "io.kiradb.benchmark.RateLimiterAccuracyTest"

# Manual load generator against a real running server (not part of the automated suite)
./gradlew :kiradb-benchmark:runLoadGenerator -Dtarget=5000 -Dduration=10 -Dport=6379
```

---

## 1. KV throughput — storage engine direct vs. full round-trip

`KvThroughputBenchmark` measures GET/SET throughput two ways so the cost of each layer is
visible on its own:

- **`engine*`** — calls `LsmStorageEngine` directly, in-process. No network, no RESP3 parsing.
  This is the ceiling: whatever the storage engine itself can do.
- **`server*`** — a real `KiraDBServer` bound to a loopback port, driven by the real `KiraDB`
  SDK client over a pooled TCP connection. This is what an application actually experiences.

4 JMH threads, `Mode.Throughput`.

| Benchmark | Throughput (ops/sec) | Error (99.9% CI) |
|---|---:|---:|
| `engineGet` (storage-direct) | 5,518,034 | ± 1,872,222 |
| `engineSet` (storage-direct) | 31,181 | ± 7,623 |
| `serverGet` (full round-trip) | 69,399 | ± 4,772 |
| `serverSet` (full round-trip) | 26,803 | ± 4,809 |

**What this says about where KiraDB's cost lives:**

- **`engineGet` is ~80x faster than `engineSet`.** GET is a lock-free read-lock path over an
  in-memory MemTable; SET synchronously appends to the WAL (`Wal.append`) before returning,
  and that fsync-adjacent write is the dominant cost. This is the expected LSM tree shape:
  writes are durable-before-ack, reads are not gated on disk at all when data is still in the
  MemTable.
- **`serverGet` (69k ops/sec) is *slower* than `engineGet` (5.5M ops/sec) by two orders of
  magnitude** — this gap is the RESP3 + Netty + loopback TCP tax on a read that itself costs
  next to nothing at the storage layer. For a GET, essentially all of the round-trip's latency
  is protocol and network, not storage.
- **`serverSet` (26.8k ops/sec) is actually *slightly slower* than `engineSet` (31.2k ops/sec)** —
  for SET, the WAL fsync-adjacent cost is large enough that it's a meaningful fraction of the
  total, so the network/protocol tax on top of it moves throughput down only modestly rather
  than by orders of magnitude, unlike the GET case.
- **`serverGet` > `serverSet` but not by 80x** — over the network, the WAL cost on the SET path
  is still there, but it's now competing with (and partially overlapped by) per-request
  RESP3/Netty overhead that both GET and SET pay equally. The relative gap compresses because
  a large, roughly-constant per-request tax is added to both numbers.

This confirms the standard LSM tree story: **reads are cheap until they miss the MemTable;
writes are always gated by WAL durability.** The `serverGet`/`engineGet` gap says the network
layer, not the storage engine, is the bottleneck for reads at this concurrency level.

---

## 2. Tier latency — MemCache (hot) vs LSM-only (cold)

`TierLatencyBenchmark` measures a `TieredStorageEngine` MemCache hit against an LSM-only read
that is deliberately kept out of MemCache. `Mode.AverageTime`, single-threaded.

| Benchmark | Latency (µs/op) | Error (99.9% CI) |
|---|---:|---:|
| `hotGet` (MemCache hit) | 0.031 | ± 0.002 |
| `coldGet` (LSM-only) | 0.216 | ± 0.010 |

Hot is ~7x faster than cold — real, but a much smaller gap than "microseconds vs
milliseconds" framing in the original design doc would suggest.

**Honest caveat — read this before citing these numbers anywhere:** the "cold" key in this
benchmark is written directly to `LsmStorageEngine`'s active MemTable and never triggers a
flush (a single small key doesn't fill the 4 MiB flush threshold). So `coldGet` measures a
**MemTable lookup that skipped MemCache**, not a real on-disk SSTable read through the bloom
filter → sparse index → file-read path. It is a valid measurement of "the cost of going through
`TieredStorageEngine`'s Tier-2 delegation instead of a MemCache hit," but it is **not** a
measurement of real disk I/O latency, and the true hot/cold gap on a dataset that has actually
been flushed and evicted from the OS page cache would be substantially larger — likely by
orders of magnitude, not single digits. See `docs/internals/phase11-benchmarks.md` for what a
follow-up benchmark that forces a real SSTable flush first would need to look like.

---

## 3. Semantic cache overhead — is it worth it?

`SemanticCacheOverheadBenchmark` compares a plain direct `GET` against a full `SC.GET`-style
lookup (embed the query text, then ANN-search a 500-entry `FlatCosineIndex`) using
`LexicalEmbeddingProvider` — the zero-network-dependency default embedder (feature hashing, not
a neural model). `Mode.AverageTime`, single-threaded.

| Benchmark | Latency (µs/op) | Error (99.9% CI) |
|---|---:|---:|
| `directGet` (plain KV read) | 0.042 | ± 0.003 |
| `semanticGet` (embed + ANN search) | 223.337 | ± 3.062 |

The semantic cache path costs **~5,300x** more than a plain key lookup at the storage-engine
level — but the absolute number, ~223 microseconds, is still far below the tens-to-hundreds of
*milliseconds* a real LLM API call costs. Even paying this tax on every request, a semantic
cache hit that avoids a real LLM round-trip is a clear net win by 2-3 orders of magnitude,
*provided the hit rate is meaningfully above zero*.

**What is not measured here:** `LexicalEmbeddingProvider` is in-process and network-free. The
alternative provider, `OllamaEmbeddingProvider`, calls out to a local Ollama HTTP server for a
real neural embedding — that adds genuine network round-trip latency (typically tens of
milliseconds for a small local model) on top of the ~223µs measured here. The 223µs number is a
floor for "structural" overhead (the embed-then-search shape), not a ceiling on total SC.GET
latency with every provider.

---

## 4. Rate limiter accuracy under concurrent load

`RateLimiterAccuracyTest` (JUnit, not JMH) hammers a single sliding-window rate limiter — 20
concurrent virtual threads, 2,000 total requests for one key, limit configured at 1,000
requests per 5-second window — and checks how close the number of admitted requests lands to
the configured limit.

**Observed result on the reference machine (reproduced across repeated runs):**

```
concurrency=20 totalRequests=2000 allowed=1000 denied=1000 limit=1000 overshoot=0.00% wallClockMs=82-84
```

**0.00% overshoot** — the limiter allowed exactly 1,000 of the 2,000 requests, exactly matching
the configured limit, with no over-admission under 20-way concurrency. This is a single-node
test (one `RateLimiterStore` instance, one `CrdtStore`, no gossip/merge lag involved) — the
`RateLimiterStore` Javadoc's documented "eventual-consistency trade-off" (brief over-allowance
during cross-node gossip lag) does not apply here since there is only one node. This result
should be read as "the local increment-then-read algorithm is exact under single-node
concurrency," not as a claim about distributed accuracy — the flagship distributed test
(`RateLimiterStoreTest.distributedEnforcementAcrossThreeNodes`, Phase 7) is the one that
exercises cross-node merge behavior, and that test uses a tolerant assertion for exactly the
reason documented in `RateLimiterStore`'s Javadoc.

---

## 5. Redis comparison — manual step, not automated

This benchmark suite does **not** shell out to `redis-benchmark` and does not assume Redis is
installed on the machine running these benchmarks — that would make the suite depend on an
environment precondition it can't guarantee or verify. No Redis comparison numbers appear
anywhere in this document because none were run.

To get an apples-to-apples comparison yourself:

```bash
# 1. Start KiraDB on port 6379 (see docs/getting-started.md)

# 2. In another terminal, install redis-benchmark if you don't have it
brew install redis   # macOS; apt-get install redis-tools on Debian/Ubuntu

# 3. Run the same shape of workload redis-benchmark defaults to against KiraDB
redis-benchmark -p 6379 -t get,set -n 100000 -q

# 4. For a real Redis baseline, run the same command against an actual Redis server
#    on the same machine (e.g. `redis-server --port 6380 &`) and compare.
redis-benchmark -p 6380 -t get,set -n 100000 -q
```

Because KiraDB speaks RESP3 and is Redis/Valkey protocol compatible, `redis-benchmark` should
work against it without modification. Expect KiraDB's SET path to be visibly slower than
Redis's — Redis has no WAL fsync on the hot path by default (`appendfsync everysec` is
async-batched), while KiraDB's `LsmStorageEngine.put` durably appends to the WAL before
acknowledging every write. See the honesty section below for the full explanation.

---

## Honest assessment: where KiraDB is slower than Redis, and why

This section exists because a benchmarks document that only shows favorable numbers is not
useful to anyone deciding whether to depend on this system.

1. **Write durability costs real latency Redis's default config doesn't pay.** Every
   `LsmStorageEngine.put()` appends to the WAL synchronously before the write is acknowledged
   (`engineSet` at ~31k ops/sec vs `engineGet` at ~5.5M ops/sec in this document is that cost,
   directly). Redis's default persistence (`appendfsync everysec`) batches fsyncs once a
   second, trading a window of potential data loss on crash for much higher write throughput.
   KiraDB chose the safer default; the honest cost is visible above.

2. **JVM warmup and GC pauses are a tax Redis (written in C) simply doesn't have.** Every
   number in this document benefited from JIT warmup (JMH's whole reason for existing — see
   `docs/internals/phase11-benchmarks.md`). A freshly started KiraDB server's first thousand or
   so requests will be slower than steady-state numbers shown here, sometimes considerably so,
   until the JIT compiles hot paths. Redis has no equivalent cold-start tax. GC pauses (even
   with modern collectors) are also a source of tail-latency variance Redis does not have to
   reason about.

3. **RESP3 parsing + Netty's event loop model add per-request overhead a single-threaded
   C event loop doesn't.** The `serverGet`/`engineGet` gap in section 1 (69k vs 5.5M ops/sec)
   is almost entirely this: object allocation for parsed commands, the Netty pipeline, and
   context-switching across the boss/worker event loop groups. Redis's protocol parser is a
   tight C loop with none of the JVM's allocation or virtual-dispatch overhead.

4. **Single-writer LSM tree vs. Redis's single-threaded in-memory model.** Both systems
   serialize writes, but for different reasons: Redis is single-threaded by design (no locking
   needed, ever); KiraDB's LSM tree uses a `ReentrantReadWriteLock` with an exclusive write
   lock, meaning writes still serialize but reads can proceed concurrently under a shared lock.
   This is a genuine trade-off, not a straightforward "KiraDB is worse" — it buys concurrent
   reads at the cost of lock overhead Redis's cooperative single-thread model doesn't pay.

5. **No comparison baseline is committed to this repository.** Every number above is only
   comparable to *itself over time* (i.e., useful for regression detection on this machine) —
   there is no Redis number sitting next to it in this document because none was run here (see
   section 5). Anyone citing this document should re-run both sides themselves before drawing
   a conclusion about relative performance.

For the full reviewer-facing discussion of JMH methodology choices (fork count, why
storage-direct and full-round-trip are measured separately, what a reviewer should scrutinize
about single-machine, non-CI benchmark numbers), see
[`docs/internals/phase11-benchmarks.md`](docs/internals/phase11-benchmarks.md).
