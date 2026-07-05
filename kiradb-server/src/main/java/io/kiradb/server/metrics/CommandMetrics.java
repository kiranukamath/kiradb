package io.kiradb.server.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe per-command execution metrics: call counts, error counts, and
 * latency (average + approximate percentiles).
 *
 * <h2>Design</h2>
 * <p>Counters use {@link LongAdder} — under contention it stripes across cells
 * instead of CAS-spinning on a single {@code AtomicLong}, so the hot path cost
 * stays near a plain increment.
 *
 * <h2>Percentile approximation — honest disclosure</h2>
 * <p>Percentiles come from a fixed-size <em>reservoir sample</em> (Vitter's
 * Algorithm&nbsp;R, {@value #RESERVOIR_SIZE} samples per command). Every recorded
 * latency has an equal probability of being in the reservoir, so the sampled
 * distribution is statistically representative of the full stream — but it is an
 * approximation:
 * <ul>
 *   <li>Tail percentiles (p99) of a 1024-sample reservoir have meaningful variance;
 *       a single outlier can move p99 noticeably.</li>
 *   <li>The reservoir covers the <em>whole lifetime</em> of the process, not a recent
 *       window — old samples are never aged out.</li>
 *   <li>Reads race benignly with writes: a snapshot may mix samples from slightly
 *       different instants. No locking is used on the hot path by design.</li>
 * </ul>
 * <p>For a dashboard refreshed every 2 seconds this is entirely adequate. A production
 * system that alerts on latency SLOs would use HdrHistogram (bounded-error buckets,
 * lock-free recording, windowed snapshots) or export raw histograms to Prometheus.
 */
public final class CommandMetrics {

    /** Number of latency samples retained per command for percentile estimation. */
    public static final int RESERVOIR_SIZE = 1024;

    private final Map<String, PerCommand> byName = new ConcurrentHashMap<>();

    /**
     * Record one command execution.
     *
     * @param name   the upper-cased command name (e.g. {@code "SET"})
     * @param micros wall-clock execution time of the handler, in microseconds
     * @param error  whether the execution produced an error response
     */
    public void record(final String name, final long micros, final boolean error) {
        PerCommand stats = byName.computeIfAbsent(name, n -> new PerCommand());
        stats.record(micros, error);
    }

    /**
     * Snapshot all per-command metrics. Safe to call concurrently with
     * {@link #record}; values are weakly consistent (see class Javadoc).
     *
     * @return one {@link CommandMetric} per command seen so far, sorted by name
     */
    public List<CommandMetric> snapshot() {
        List<CommandMetric> result = new ArrayList<>(byName.size());
        byName.forEach((name, stats) -> result.add(stats.snapshot(name)));
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }

    /**
     * Mutable per-command state. Package-private — only touched via the outer class.
     */
    private static final class PerCommand {
        private final LongAdder count = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder totalMicros = new LongAdder();
        private final AtomicLong seen = new AtomicLong();
        private final long[] reservoir = new long[RESERVOIR_SIZE];

        void record(final long micros, final boolean error) {
            count.increment();
            totalMicros.add(micros);
            if (error) {
                errors.increment();
            }
            long index = seen.getAndIncrement();
            if (index < RESERVOIR_SIZE) {
                reservoir[(int) index] = micros;
            } else {
                // Algorithm R: keep the new sample with probability RESERVOIR_SIZE/(index+1)
                long slot = ThreadLocalRandom.current().nextLong(index + 1);
                if (slot < RESERVOIR_SIZE) {
                    reservoir[(int) slot] = micros;
                }
            }
        }

        CommandMetric snapshot(final String name) {
            long total = count.sum();
            long err = errors.sum();
            long sumMicros = totalMicros.sum();
            long avg = total == 0 ? 0 : sumMicros / total;

            int filled = (int) Math.min(seen.get(), RESERVOIR_SIZE);
            long p50 = 0;
            long p95 = 0;
            long p99 = 0;
            if (filled > 0) {
                long[] sorted = Arrays.copyOf(reservoir, filled);
                Arrays.sort(sorted);
                p50 = percentile(sorted, 0.50);
                p95 = percentile(sorted, 0.95);
                p99 = percentile(sorted, 0.99);
            }
            return new CommandMetric(name, total, err, avg, p50, p95, p99);
        }

        private static long percentile(final long[] sorted, final double quantile) {
            int index = (int) Math.ceil(quantile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }
    }
}
