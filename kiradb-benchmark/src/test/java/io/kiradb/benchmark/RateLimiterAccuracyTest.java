package io.kiradb.benchmark;

import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.services.ratelimit.RateLimitDecision;
import io.kiradb.services.ratelimit.RateLimiterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness-under-load test for {@link RateLimiterStore}'s sliding-window algorithm — not a
 * JMH microbenchmark. This hammers a single limiter from many concurrent virtual threads at a
 * target aggregate request rate and asserts the number of requests actually let through stays
 * within a documented tolerance of the configured limit.
 *
 * <h2>Why a tolerance, not exact equality</h2>
 * <p>The sliding-window-counter algorithm (see {@link RateLimiterStore} Javadoc) is an
 * <em>estimate</em> blended from the previous and current time buckets, not an exact count.
 * Under concurrent load, multiple threads can also race between "read current count" and
 * "increment" — the real implementation increments first then reads back, so the window can
 * briefly admit a handful more than the nominal limit before the estimate catches up. This is
 * documented, expected behavior (see {@code RateLimiterStore} Javadoc's "eventual-consistency
 * trade-off" section for the single-node concurrent case), not a bug.
 *
 * <p>Actual observed accuracy from a real run of this test is recorded in {@code BENCHMARKS.md}
 * — do not assume the tolerance below equals the observed number; it is a safety margin.
 */
class RateLimiterAccuracyTest {

    private static final int CONCURRENCY = 20;
    private static final long TARGET_TOTAL_REQUESTS = 2000;
    private static final long LIMIT = 1000;
    private static final long PERIOD_SECONDS = 5;

    /**
     * Fire {@value #TARGET_TOTAL_REQUESTS} requests for one key across
     * {@value #CONCURRENCY} concurrent virtual threads against a limiter configured for
     * {@value #LIMIT} requests per {@value #PERIOD_SECONDS}-second window, then assert the
     * number of allowed requests is close to the configured limit.
     *
     * @param dataDir JUnit-managed temp directory for the backing storage engine
     * @throws Exception if the storage engine cannot be opened or a thread is interrupted
     */
    @Test
    void concurrentLoadStaysWithinToleranceOfLimit(@TempDir final Path dataDir) throws Exception {
        try (LsmStorageEngine storage = new LsmStorageEngine(dataDir)) {
            CrdtStore crdtStore = new CrdtStore(storage, "accuracy-test-node");
            RateLimiterStore rateLimiter = new RateLimiterStore(crdtStore);

            AtomicLong allowed = new AtomicLong();
            AtomicLong denied = new AtomicLong();
            CountDownLatch done = new CountDownLatch(CONCURRENCY);
            long perThread = TARGET_TOTAL_REQUESTS / CONCURRENCY;

            long startNanos = System.nanoTime();
            for (int t = 0; t < CONCURRENCY; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        for (long i = 0; i < perThread; i++) {
                            RateLimitDecision decision =
                                    rateLimiter.allow("accuracy-api", "user:load", LIMIT, PERIOD_SECONDS);
                            if (decision.allowed()) {
                                allowed.incrementAndGet();
                            } else {
                                denied.incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            long totalIssued = allowed.get() + denied.get();
            double overshootPct = (allowed.get() - LIMIT) * 100.0 / LIMIT;

            System.out.printf(
                    "RateLimiterAccuracyTest: concurrency=%d totalRequests=%d allowed=%d denied=%d "
                            + "limit=%d overshoot=%.2f%% wallClockMs=%d%n",
                    CONCURRENCY, totalIssued, allowed.get(), denied.get(), LIMIT, overshootPct, elapsedMillis);

            assertTrue(totalIssued == TARGET_TOTAL_REQUESTS,
                    "expected all requests to receive a decision, got " + totalIssued);

            // Tolerance: sliding-window blending plus increment-then-read races under 20-way
            // concurrency. 15% margin over the configured limit — see class Javadoc.
            long tolerance = (long) (LIMIT * 0.15);
            assertTrue(allowed.get() <= LIMIT + tolerance,
                    "allowed=" + allowed.get() + " exceeds limit=" + LIMIT
                            + " by more than tolerance=" + tolerance);
            assertTrue(allowed.get() >= LIMIT * 0.5,
                    "allowed=" + allowed.get() + " is suspiciously far below limit=" + LIMIT
                            + " — limiter may be over-denying");
        }
    }
}
