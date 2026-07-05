# Phase 11 — Benchmarks & Hardening: Design Notes & Deep Dive

> Written during the autonomous build. Real measured numbers, full methodology,
> and the honest Redis comparison live in [`BENCHMARKS.md`](../../BENCHMARKS.md) at the repo
> root — read that first for the actual data. This document is the reviewer-facing "why did we
> measure it this way" companion.

---

## What was built

| Piece | Location | Role |
|---|---|---|
| `KvThroughputBenchmark` | `kiradb-benchmark/.../KvThroughputBenchmark.java` | GET/SET throughput, storage-engine-direct vs. full round-trip |
| `TierLatencyBenchmark` | same package | MemCache hit vs. LSM-only read latency |
| `SemanticCacheOverheadBenchmark` | same package | Plain GET vs. embed+ANN-search `SC.GET` cost |
| `RateLimiterAccuracyTest` | `kiradb-benchmark/src/test/.../RateLimiterAccuracyTest.java` | Correctness-under-load, not a JMH benchmark |
| `LoadGenerator` | `kiradb-benchmark/.../LoadGenerator.java` | Manual tool: sustained load against a live server, reports p50/p95/p99 |
| `BENCHMARKS.md` | repo root | Real numbers, methodology, honest Redis comparison |

---

## Why JMH instead of a hand-rolled timing loop

The tempting shortcut is:

```java
long start = System.nanoTime();
for (int i = 0; i < 1_000_000; i++) {
    engine.get(key);
}
long elapsed = System.nanoTime() - start;
System.out.println(1_000_000.0 / (elapsed / 1e9) + " ops/sec");
```

This produces a number. It is very likely a *wrong* number, for reasons specific to how the
JVM executes code:

1. **JIT warmup contaminates the measurement.** The JVM interprets bytecode first, then
   profiles hot methods, then JIT-compiles them (C1, then C2) once they're called enough times.
   A hand-rolled loop that includes the first few thousand calls is measuring interpreter
   speed blended with compiled speed — not a number anyone can act on. JMH's `@Warmup`
   iterations exist specifically to run the code until the JIT has done its work *before* the
   clock starts on `@Measurement` iterations.

2. **Dead-code elimination can make the "hot" path disappear entirely.** If the JIT can prove
   the result of `engine.get(key)` is never used, it is legally allowed to skip calling it at
   all — you'd be timing an empty loop and calling it "5 million ops/sec." This is exactly why
   every read-returning benchmark method in `KvThroughputBenchmark`,
   `TierLatencyBenchmark`, and `SemanticCacheOverheadBenchmark` takes a `Blackhole` parameter
   and calls `blackhole.consume(result)` — this is JMH's documented mechanism for telling the
   JIT "this value is observed, you may not optimize the call away." Every benchmark method in
   this phase that returns a value uses a `Blackhole`; the ones that don't (`engineSet`,
   `serverSet`) have an externally observable side effect (the write itself) that the JIT
   cannot prove away.

3. **A hand-rolled loop can't tell you your confidence interval.** JMH runs multiple
   measurement iterations and reports mean ± error at a stated confidence level (99.9% in this
   suite's default report). A single elapsed-time number has no way to tell you whether it's
   representative or a fluke — see the `engineGet` result in `BENCHMARKS.md`
   (5.5M ± 1.87M ops/sec): that error bar is real variance across 5 one-second iterations on a
   laptop with other processes running, and JMH surfaces it instead of hiding it behind a
   single deceptively-precise number.

## Why storage-engine-direct AND full-round-trip are measured separately

`KvThroughputBenchmark` has both an `EngineState` (calls `LsmStorageEngine` in-process) and a
`ServerState` (real `KiraDBServer` + real `KiraDB` SDK client over loopback TCP). This is
deliberate, not redundant: **a single "GET is X ops/sec" number cannot tell you where to
optimize.** Measured separately:

- `engineGet` ≈ 5.5M ops/sec — the storage engine ceiling.
- `serverGet` ≈ 69k ops/sec — what an application actually experiences.

The ~80x gap between them is the network + RESP3 protocol tax. If KiraDB needed to get faster
at reads, this decomposition tells you immediately: **don't touch the storage engine, it's not
the bottleneck** — look at the Netty pipeline, RESP3 encode/decode allocation, or connection
pooling instead. Without the split, you'd only know "GET is slow" and could easily waste a
sprint optimizing the wrong layer. This is the same reasoning that motivates splitting
`TierLatencyBenchmark` into hot/cold and `SemanticCacheOverheadBenchmark` into direct/semantic:
each pair isolates one specific added cost so the number that changes is attributable to one
cause.

## What the numbers imply about where KiraDB's bottlenecks are

See `BENCHMARKS.md` sections 1-4 for the full numbers and per-benchmark discussion. At a
glance, in priority order for anyone looking to make KiraDB faster:

1. **The network/protocol layer, not the storage engine, dominates read latency** at current
   concurrency (69k server ops/sec vs. 5.5M engine ops/sec for GET). Netty pipeline tuning,
   RESP3 allocation reduction, or connection pipelining would move this number more than any
   storage engine change.
2. **WAL durability is the dominant cost on the write path** (`engineSet` at 31k ops/sec vs.
   `engineGet` at 5.5M). This is a deliberate durability-over-throughput trade-off (see
   `BENCHMARKS.md` honesty section, point 1) — not a bug, but the lever to pull if a caller
   needs higher write throughput and can tolerate `appendfsync`-style relaxed durability
   (not currently configurable — a real gap, listed below).
3. **The semantic cache's structural overhead (~223µs) is real but small relative to what it
   saves** (avoiding a real LLM call, typically tens to hundreds of milliseconds). Only a
   concern if hit rate is near zero, in which case the cache is pure overhead with no offsetting
   benefit — a reason `SC.STATS`-driven hit-rate monitoring matters operationally.
4. **`TieredStorageEngine`'s hot/cold gap (0.031µs vs 0.216µs) is real but was measured without
   ever touching a real SSTable** — see the caveat below and in `TierLatencyBenchmark`'s
   Javadoc. This is the biggest methodological gap in this phase's numbers and the first thing
   a follow-up should fix.

## What a reviewer should scrutinize

Read this before trusting any number in `BENCHMARKS.md` at face value:

1. **`Fork(1)` was used, not `Fork(0)` or a higher fork count.** `Fork(0)` runs benchmarks in
   the same JVM as the harness — faster to iterate on while writing benchmarks, but the
   harness's own class loading and JIT state can leak into the measurement. `Fork(1)` (one
   fresh JVM per benchmark class) is the minimum JMH configuration considered trustworthy for a
   published number; JMH's own documentation recommends `Fork(3)`+ for numbers going into a
   paper or a performance regression gate. This suite uses `Fork(1)` as a laptop-friendly
   compromise — real variance exists between runs (the CI-widths in `BENCHMARKS.md` are not
   negligible, e.g. `engineGet`'s ±1.87M on a mean of 5.5M), and a `Fork(3)` run averaging
   across three independent JVM instances would produce a tighter, more defensible number. If
   you need to cite these numbers for a real capacity-planning decision, re-run with
   `@Fork(3)` and more measurement iterations first.
2. **Single-machine, single-run numbers with no historical baseline.** There is no CI job that
   runs these benchmarks and no committed history of past runs to compare against — every
   number in `BENCHMARKS.md` is a snapshot from one session on one MacBook Air. A background
   process (Spotlight indexing, another IDE's language server, thermal throttling) could shift
   any of these numbers by a meaningful percentage without it reflecting a real KiraDB change.
   Treat the *relative* shape (GET fast, SET slow because of WAL, network dominates over
   storage) as trustworthy; treat the *absolute* ops/sec numbers as one data point, not a
   guarantee.
3. **The `TierLatencyBenchmark` "cold" read never touched disk.** Flagged prominently in both
   the benchmark's Javadoc and `BENCHMARKS.md` section 2: the cold key sits in the LSM
   MemTable (never grew past the 4 MiB flush threshold), so `coldGet` measures "MemCache
   bypass into MemTable," not "read from an SSTable file via bloom filter + sparse index." The
   true hot-vs-cold gap on a dataset that's been flushed and evicted from the OS page cache is
   almost certainly larger by orders of magnitude, not the ~7x measured here. This is the
   single biggest thing to fix before trusting this benchmark's number for anything.
4. **The rate limiter accuracy test is single-node.** 0.00% overshoot at 20-way concurrency is
   a genuinely good result, but it says nothing about the documented cross-node
   eventual-consistency trade-off (see `RateLimiterStore` Javadoc) — that's covered by
   `RateLimiterStoreTest.distributedEnforcementAcrossThreeNodes` from Phase 7, a separate test
   with a deliberately tolerant assertion for exactly that reason.
5. **No Redis baseline was run.** `BENCHMARKS.md` section 5 gives the exact `redis-benchmark`
   commands but does not run them — there is no Redis instance in this sandboxed build
   environment and no attempt was made to assume one exists. Anyone who wants a real
   side-by-side needs to run both sides themselves, on the same machine, same day.

## How to make this better

1. **Continuous benchmark tracking in CI, with regression detection.** Right now these numbers
   are a one-time snapshot. A real system would run a reduced JMH suite (fewer iterations, to
   keep CI fast) on every merge to `main`, store results (e.g. as a CI artifact or in a small
   time-series store), and fail the build — or at least flag a PR — on a statistically
   significant regression (not just any decrease, since single-run noise is real; a tool like
   [JMH's `jmh-result`
   JSON output](https://github.com/openjdk/jmh) feeding a regression-detection script, or a
   dedicated tool like `hyperfine`-for-JMH, would be the right shape).
2. **Flame graph profiling with `async-profiler`.** The original Phase 11 plan called for this;
   it is not done here because `async-profiler` requires attaching a native agent JAR to the
   JVM (`-agentpath:/path/to/libasyncProfiler.so`), which is an external download this
   sandboxed environment can't assume is present, and profiling meaningfully changes what you'd
   want to run (longer, steady-state loads rather than JMH's short iterations) — it deserves
   its own follow-up session with the agent actually installed, not a token gesture here. When
   done, the highest-value target based on this phase's numbers is the RESP3 encode/decode +
   Netty pipeline path, since that's where the `engineGet`/`serverGet` gap says the real cost
   is.
3. **Realistic multi-key-distribution workloads (Zipfian) instead of uniform random.** Every
   benchmark and the `LoadGenerator` in this phase picks keys uniformly at random (or reads a
   single fixed key). Real workloads are almost never uniform — a small number of keys receive
   most of the traffic (Zipfian / power-law distribution), which is exactly the assumption
   `TieredStorageEngine`'s hot/cold promotion logic (Phase 5) is designed around. A uniform
   benchmark structurally cannot exercise or validate that promotion logic's benefit — every
   key looks equally cold, so nothing ever earns promotion under realistic traffic shape versus
   this benchmark's degenerate single-key-repeated-forever hot case. A `ZipfianKeyGenerator`
   feeding `LoadGenerator` and a variant of `TierLatencyBenchmark` that reads from a realistic
   distribution instead of one fixed key each for hot/cold would directly validate (or
   invalidate) Phase 5's promotion thresholds against something resembling production traffic.
4. **A network benchmark isolating just RESP3 parse/encode cost.** `KvThroughputBenchmark`'s
   `serverGet`/`serverSet` numbers bundle together RESP3 parsing, Netty pipeline dispatch, TCP
   loopback overhead, and connection pool borrow/release cost — useful for "what does the user
   see" but useless for isolating which of those four costs the most. A benchmark that
   round-trips a `Resp3Decoder`/`Resp3Encoder` pair directly on an in-memory buffer, with no
   socket at all, would isolate the protocol cost specifically and answer whether RESP3
   parsing itself is a meaningful fraction of the ~80x server/engine gap, or whether it's
   almost entirely Netty scheduling and TCP.
5. **Configurable write durability.** `BENCHMARKS.md`'s honesty section identifies synchronous
   WAL append as the dominant SET-path cost, and today it is not configurable — every write
   pays it. A `WalSyncPolicy` (e.g. `ALWAYS` / `EVERY_SECOND` / `NEVER`, mirroring Redis's
   `appendfsync` knob) would let a caller trade durability for throughput explicitly instead of
   KiraDB making that choice for them unconditionally. This is a real gap relative to Redis,
   not just a benchmark-methodology gap — flagged here because the benchmark numbers are what
   surfaced it clearly.
6. **Force a real SSTable flush before measuring "cold" reads.** Directly actionable
   follow-up to the biggest caveat in this phase: write enough data past the 4 MiB MemTable
   threshold to force `LsmStorageEngine` to flush to an actual `.sst` file, then measure a read
   that must go through bloom filter → sparse index → file I/O. That is the number the original
   Phase 5 "microseconds vs milliseconds" framing was describing, and this phase did not
   measure it.
