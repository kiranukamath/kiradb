package io.kiradb.benchmark;

import io.kiradb.client.KiraDB;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone manual load generator against a <em>real, already-running</em> KiraDB server.
 *
 * <p>This is a developer tool, not part of the automated test suite — it is deliberately not
 * wired into {@code ./gradlew test} or {@code build}. Run it by hand against a server you have
 * started separately, configuring via system properties, e.g.:
 *
 * <pre>{@code
 * ./gradlew :kiradb-benchmark:runLoadGenerator -Dtarget=5000 -Dduration=10 -Dport=6379
 * }</pre>
 *
 * <h2>Configuration (system properties, all optional)</h2>
 * <ul>
 *   <li>{@code host} — server host, default {@code localhost}</li>
 *   <li>{@code port} — server port, default {@code 6379}</li>
 *   <li>{@code target} — target aggregate ops/sec across all worker threads, default {@code 1000}</li>
 *   <li>{@code duration} — run duration in seconds, default {@code 10}</li>
 *   <li>{@code keyspace} — number of distinct keys cycled through, default {@code 10000}</li>
 *   <li>{@code readRatio} — fraction of ops that are GET vs SET, in {@code [0,1]}, default {@code 0.9}</li>
 *   <li>{@code threads} — number of worker (virtual) threads, default {@code 32}</li>
 * </ul>
 *
 * <h2>Latency measurement</h2>
 * <p>Every op's latency (nanos) is recorded into a lock-free queue drained at the end — simpler
 * than {@code CommandMetrics}'s reservoir sampling since this tool runs once and exits rather
 * than serving a live dashboard; keeping every sample (not a fixed-size reservoir) gives exact
 * percentiles for run sizes this tool is meant for (tens of thousands to low millions of ops).
 * For much larger runs, switch to a bounded reservoir or streaming histogram (see
 * {@code CommandMetrics} in kiradb-server for that approach) to bound memory.
 */
public final class LoadGenerator {

    private LoadGenerator() {
    }

    /**
     * Entry point. See the class Javadoc for configuration via system properties.
     *
     * @param args unused; configuration is via system properties
     * @throws InterruptedException if the run is interrupted while waiting for workers
     */
    public static void main(final String[] args) throws InterruptedException {
        String host = System.getProperty("host", "localhost");
        int port = Integer.getInteger("port", 6379);
        long targetOpsPerSec = Long.getLong("target", 1000);
        int durationSeconds = Integer.getInteger("duration", 10);
        int keyspaceSize = Integer.getInteger("keyspace", 10_000);
        double readRatio = Double.parseDouble(System.getProperty("readRatio", "0.9"));
        int threads = Integer.getInteger("threads", 32);

        System.out.printf(
                "LoadGenerator: host=%s port=%d targetOpsPerSec=%d durationSeconds=%d "
                        + "keyspace=%d readRatio=%.2f threads=%d%n",
                host, port, targetOpsPerSec, durationSeconds, keyspaceSize, readRatio, threads);

        try (KiraDB db = KiraDB.builder()
                .nodes(host + ":" + port)
                .connectionPool(threads)
                .build()) {

            // Pre-populate the keyspace so GETs have something to find from the first second.
            for (int i = 0; i < keyspaceSize; i++) {
                db.set(keyOf(i), "load-generator-seed-value");
            }

            ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
            AtomicLong opsIssued = new AtomicLong();
            AtomicLong errors = new AtomicLong();

            long perThreadOpsPerSec = Math.max(1, targetOpsPerSec / threads);
            long nanosPerOpPerThread = 1_000_000_000L / perThreadOpsPerSec;

            CountDownLatch done = new CountDownLatch(threads);
            long endAtNanos = System.nanoTime() + Duration.ofSeconds(durationSeconds).toNanos();

            for (int t = 0; t < threads; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        long nextOpAt = System.nanoTime();
                        while (System.nanoTime() < endAtNanos) {
                            if (System.nanoTime() < nextOpAt) {
                                continue; // spin-wait to pace to the target rate
                            }
                            nextOpAt += nanosPerOpPerThread;

                            String key = keyOf(rnd.nextInt(keyspaceSize));
                            long start = System.nanoTime();
                            try {
                                if (rnd.nextDouble() < readRatio) {
                                    db.get(key);
                                } else {
                                    db.set(key, "load-generator-value-" + start);
                                }
                                latenciesNanos.add(System.nanoTime() - start);
                                opsIssued.incrementAndGet();
                            } catch (RuntimeException e) {
                                errors.incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();

            report(opsIssued.get(), errors.get(), durationSeconds, latenciesNanos);
        }
    }

    private static String keyOf(final int i) {
        return "loadgen:key:" + i;
    }

    private static void report(
            final long opsIssued, final long errors, final int durationSeconds,
            final ConcurrentLinkedQueue<Long> latenciesNanos) {
        long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double throughput = opsIssued / (double) durationSeconds;

        System.out.println();
        System.out.println("=== LoadGenerator results ===");
        System.out.printf("ops issued      : %d%n", opsIssued);
        System.out.printf("errors          : %d%n", errors);
        System.out.printf("throughput      : %.1f ops/sec%n", throughput);
        if (sorted.length > 0) {
            System.out.printf("latency p50     : %.3f ms%n", percentile(sorted, 0.50) / 1_000_000.0);
            System.out.printf("latency p95     : %.3f ms%n", percentile(sorted, 0.95) / 1_000_000.0);
            System.out.printf("latency p99     : %.3f ms%n", percentile(sorted, 0.99) / 1_000_000.0);
            System.out.printf("latency max     : %.3f ms%n",
                    sorted[sorted.length - 1] / 1_000_000.0);
        } else {
            System.out.println("no successful ops recorded");
        }
    }

    private static long percentile(final long[] sortedNanos, final double quantile) {
        int index = (int) Math.ceil(quantile * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))];
    }
}
