package io.kiradb.benchmark;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.semanticcache.SemanticCacheStore;
import io.kiradb.semanticcache.embedding.LexicalEmbeddingProvider;
import io.kiradb.semanticcache.index.FlatCosineIndex;
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
 * Quantifies the "is the semantic cache worth it" latency tax: an {@code SC.GET}-style lookup
 * (embed the query, then ANN-search the vector index) versus a plain direct key-value
 * {@code GET} against the same underlying {@link StorageEngine}.
 *
 * <p>Uses {@link LexicalEmbeddingProvider} — the default, zero-network-dependency embedder
 * (feature hashing, not a neural model). This isolates the <em>structural</em> cost of the
 * semantic cache's extra steps (embed + vector search) from any provider-specific cost.
 * A neural provider such as {@code OllamaEmbeddingProvider} would add real network round-trip
 * latency (typically tens of milliseconds) on top of what is measured here — not measured in
 * this benchmark; see {@code BENCHMARKS.md} for the honest caveat.
 *
 * <h2>JMH configuration</h2>
 * <p>{@link Mode#AverageTime} in microseconds. 3 warmup + 5 measurement iterations, 1 fork.
 * The vector index is pre-populated with a fixed number of entries so search cost reflects a
 * realistic, non-empty cache rather than a degenerate single-entry index.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SemanticCacheOverheadBenchmark {

    /** Number of distinct prompts pre-loaded into the cache before measurement starts. */
    private static final int PRELOADED_ENTRIES = 500;

    private static final String QUERY_PROMPT = "what is the capital of France?";
    private static final String DIRECT_KEY = "bench:direct:key";
    private static final byte[] DIRECT_VALUE = "direct-value".getBytes(StandardCharsets.UTF_8);

    /** Shared storage engine plus a semantic cache pre-populated with {@value #PRELOADED_ENTRIES} entries. */
    @State(Scope.Benchmark)
    public static class CacheState {

        private Path dataDir;
        private StorageEngine storage;
        private SemanticCacheStore semanticCache;

        /**
         * Open a storage engine, build a semantic cache over it, and pre-populate both a
         * plain key and a batch of semantically distinct prompts.
         *
         * @throws Exception if the storage engine cannot be opened
         */
        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataDir = Files.createTempDirectory("kiradb-bench-sc");
            storage = new LsmStorageEngine(dataDir);
            storage.put(DIRECT_KEY.getBytes(StandardCharsets.UTF_8), DIRECT_VALUE);

            semanticCache = new SemanticCacheStore(
                    storage, new LexicalEmbeddingProvider(), new FlatCosineIndex(), 0.80);
            semanticCache.set(QUERY_PROMPT, "Paris is the capital.", 0);
            for (int i = 0; i < PRELOADED_ENTRIES; i++) {
                semanticCache.set("unrelated filler prompt number " + i,
                        "filler response " + i, 0);
            }
        }

        /**
         * Close the storage engine and remove the temp directory.
         */
        @TearDown(Level.Trial)
        public void tearDown() {
            storage.close();
            deleteRecursively(dataDir);
        }
    }

    /**
     * Plain direct GET against the storage engine — the baseline with no embedding or
     * vector search involved.
     *
     * @param state the shared cache state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    public void directGet(final CacheState state, final Blackhole blackhole) {
        blackhole.consume(state.storage.get(DIRECT_KEY.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Semantic cache lookup: embed the query text, then ANN-search the vector index for the
     * best match above threshold. This is the full {@code SC.GET} cost.
     *
     * @param state the shared cache state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    public void semanticGet(final CacheState state, final Blackhole blackhole) {
        blackhole.consume(state.semanticCache.get(QUERY_PROMPT));
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
