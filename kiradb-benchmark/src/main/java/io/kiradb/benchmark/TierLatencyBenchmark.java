package io.kiradb.benchmark;

import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.core.storage.tier.RuleBasedOrchestrator;
import io.kiradb.core.storage.tier.TierManager;
import io.kiradb.core.storage.tier.TieredStorageEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Read latency of {@link TieredStorageEngine}: a MemCache (Tier 1, "hot") hit versus an
 * LSM-only (Tier 2, "cold") read that is deliberately kept out of MemCache.
 *
 * <h2>How "cold" is achieved</h2>
 * <p>{@link TieredStorageEngine#put} always warms MemCache on write (see its Javadoc — every
 * write is immediately hot). To measure a genuinely cold read, this benchmark writes the cold
 * key directly to the underlying {@link LsmStorageEngine} (Tier 2), bypassing
 * {@code TieredStorageEngine.put()} entirely, so the key has never touched MemCache.
 *
 * <p><b>Honest caveat:</b> every {@code coldGet} still goes through the real read path, which
 * fires an async on-demand promotion check (see {@link TieredStorageEngine#get}) after every
 * Tier 2 hit. Repeatedly reading the same cold key across thousands of JMH invocations would
 * eventually accumulate enough {@code AccessTracker} score to cross the promotion threshold and
 * warm the key mid-benchmark, silently turning "cold" measurements into "hot" ones. To keep the
 * measurement honest, this benchmark constructs the orchestrator with a promotion threshold of
 * {@link Double#MAX_VALUE} — promotion is mathematically unreachable, so every {@code coldGet}
 * measures the real Tier 2 path for the full run. The background {@link TierManager} sweep
 * (default every 5 minutes) also cannot fire within a single JMH trial's runtime.
 *
 * <p><b>Second honest caveat -- "cold" here means "not in MemCache," not "on physical disk."</b>
 * The cold key is written directly to {@link LsmStorageEngine}'s active MemTable
 * ({@code DEFAULT_THRESHOLD_BYTES} = 4 MiB), and a single small key does not fill it, so
 * the key is served from the in-memory MemTable, not an on-disk SSTable -- this benchmark does
 * not exercise the bloom-filter/sparse-index/file-read path at all. The measured
 * {@code coldGet} number is therefore a lower bound on real Tier 2 latency, not a
 * representative one; a key that has actually been flushed to an SSTable and evicted from the
 * page cache would be meaningfully slower. See {@code BENCHMARKS.md} for the honest numbers.
 *
 * <h2>JMH configuration</h2>
 * <p>{@link Mode#AverageTime} reported in microseconds — latency, not throughput, is the
 * question this benchmark answers. 3 warmup + 5 measurement iterations, 1 fork.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class TierLatencyBenchmark {

    private static final byte[] HOT_KEY = "bench:hot".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COLD_KEY = "bench:cold".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "tier-latency-value".getBytes(StandardCharsets.UTF_8);

    /** A {@link TieredStorageEngine} with one hot key (in MemCache) and one cold key (LSM-only). */
    @State(Scope.Benchmark)
    public static class TierState {

        private Path dataDir;
        private LsmStorageEngine tier2;
        private TieredStorageEngine tiered;

        /**
         * Build the tiered engine, then plant a hot key via the normal write path (warms
         * MemCache) and a cold key by writing straight to Tier 2, skipping MemCache.
         *
         * @throws Exception if the engine cannot be opened
         */
        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataDir = Files.createTempDirectory("kiradb-bench-tier");
            tier2 = new LsmStorageEngine(dataDir);
            // Promotion threshold set unreachably high so repeated coldGet() calls can never
            // promote the cold key mid-benchmark — see the class Javadoc "Honest caveat".
            tiered = new TieredStorageEngine(
                    tier2, 1000, new RuleBasedOrchestrator(Double.MAX_VALUE, 2.0),
                    TierManager.DEFAULT_INTERVAL_MS);

            // Hot path: goes through TieredStorageEngine.put(), which warms MemCache.
            tiered.put(HOT_KEY, VALUE);

            // Cold path: written directly to Tier 2, never touches MemCache or AccessTracker.
            tier2.put(COLD_KEY, VALUE);
        }

        /**
         * Close the engine and remove the temp directory.
         */
        @TearDown(Level.Trial)
        public void tearDown() {
            tiered.close();
            deleteRecursively(dataDir);
        }
    }

    /**
     * MemCache (Tier 1) hit — the fast path, expected to be microseconds.
     *
     * @param state the shared tiered engine state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    public void hotGet(final TierState state, final Blackhole blackhole) {
        blackhole.consume(state.tiered.get(HOT_KEY));
    }

    /**
     * LSM-only (Tier 2) read — the slow path, expected to be milliseconds relative to hot.
     *
     * @param state the shared tiered engine state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    public void coldGet(final TierState state, final Blackhole blackhole) {
        blackhole.consume(state.tiered.get(COLD_KEY));
    }

    private static void deleteRecursively(final Path path) {
        if (path == null) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
