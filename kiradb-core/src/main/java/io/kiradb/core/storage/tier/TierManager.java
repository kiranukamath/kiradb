package io.kiradb.core.storage.tier;

import io.kiradb.core.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;

/**
 * Background manager that periodically re-evaluates every tracked key and
 * executes promotion or eviction decisions.
 *
 * <h2>Cycle (runs every {@code intervalMs})</h2>
 * <ol>
 *   <li>Ask {@link AccessTracker} for a snapshot of all tracked keys.</li>
 *   <li>For each key, determine its current {@link TierLocation} (HOT or WARM).</li>
 *   <li>Ask {@link TierOrchestrator} for a {@link TierDecision}.</li>
 *   <li>Execute: PROMOTE → load from Tier 2, insert into MemCache.
 *                EVICT   → remove from MemCache (key stays on SSD, no data loss).
 *                KEEP    → nothing.</li>
 *   <li>Purge tracker entries whose score is below the minimum tracking threshold
 *       (prevents unbounded memory growth for keys that were accessed once and never again).</li>
 * </ol>
 *
 * <h2>Thread model</h2>
 * <p>Runs on a single virtual thread.  All state mutations go through the
 * thread-safe {@link MemCache} and {@link AccessTracker}.
 */
public final class TierManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TierManager.class);

    /** Default scan interval — run every 5 minutes. */
    public static final long DEFAULT_INTERVAL_MS = 5 * 60 * 1_000L;

    /**
     * Keys with a score below this are dropped from the tracker entirely.
     * Prevents unbounded tracker growth for one-time-accessed keys.
     * Equivalent to "not accessed in roughly 12+ hours with 1 hit".
     */
    private static final double MIN_TRACK_SCORE = 0.01;

    private final MemCache         memCache;
    private final AccessTracker    accessTracker;
    private final TierOrchestrator orchestrator;
    private final StorageEngine    tier2;
    private final long             intervalMs;

    private volatile Thread managerThread;

    /**
     * Create a TierManager with the default 5-minute scan interval.
     *
     * @param memCache      Tier 1 cache
     * @param accessTracker key hotness tracker
     * @param orchestrator  promotion/eviction decision strategy
     * @param tier2         Tier 2 storage (LsmStorageEngine) — source for promotions
     */
    public TierManager(final MemCache memCache,
                       final AccessTracker accessTracker,
                       final TierOrchestrator orchestrator,
                       final StorageEngine tier2) {
        this(memCache, accessTracker, orchestrator, tier2, DEFAULT_INTERVAL_MS);
    }

    /**
     * Create a TierManager with a custom scan interval.
     *
     * @param memCache      Tier 1 cache
     * @param accessTracker key hotness tracker
     * @param orchestrator  promotion/eviction decision strategy
     * @param tier2         Tier 2 storage for promotions
     * @param intervalMs    how often to run the scan in milliseconds
     */
    public TierManager(final MemCache memCache,
                       final AccessTracker accessTracker,
                       final TierOrchestrator orchestrator,
                       final StorageEngine tier2,
                       final long intervalMs) {
        this.memCache      = memCache;
        this.accessTracker = accessTracker;
        this.orchestrator  = orchestrator;
        this.tier2         = tier2;
        this.intervalMs    = intervalMs;
    }

    /**
     * Start the background scan loop.
     */
    public void start() {
        managerThread = Thread.ofVirtual().name("tier-manager").start(this::runLoop);
        LOG.info("TierManager started (interval={}ms)", intervalMs);
    }

    /**
     * Stop the background loop and wait for it to finish.
     */
    @Override
    public void close() {
        Thread t = managerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Run one scan cycle immediately (for testing without waiting for the interval).
     */
    public void runCycleNow() {
        runCycle();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            runCycle();
        }
        LOG.info("TierManager stopped.");
    }

    private void runCycle() {
        Collection<AccessStats> snapshots = accessTracker.allSnapshots();
        int promoted = 0;
        int evicted  = 0;
        int purged   = 0;

        for (AccessStats stats : snapshots) {
            byte[] key = stats.key();

            // Purge barely-accessed keys from the tracker to prevent memory bloat
            if (stats.decayedScore() < MIN_TRACK_SCORE) {
                memCache.remove(key);
                accessTracker.remove(key);
                purged++;
                continue;
            }

            TierLocation current = memCache.contains(key) ? TierLocation.HOT : TierLocation.WARM;
            TierDecision decision = orchestrator.evaluate(key, stats, current);

            switch (decision) {
                case PROMOTE -> {
                    if (current == TierLocation.WARM) {
                        Optional<byte[]> value = tier2.get(key);
                        if (value.isPresent()) {
                            long expiry = expiryFor(key);
                            memCache.put(key, value.get(), expiry);
                            promoted++;
                        }
                    }
                }
                case EVICT -> {
                    if (current == TierLocation.HOT) {
                        memCache.remove(key);
                        evicted++;
                    }
                }
                case KEEP -> { /* nothing to do */ }
                default -> LOG.warn("Unknown TierDecision: {}", decision);
            }
        }

        LOG.debug("TierManager cycle: promoted={} evicted={} purged={} tracked={}",
                promoted, evicted, purged, accessTracker.size());
    }

    /**
     * Convert remaining TTL from tier2 into an absolute expiry epoch-millis.
     * Returns {@code -1} if the key has no expiry.
     */
    private long expiryFor(final byte[] key) {
        long ttl = tier2.ttlMillis(key);
        if (ttl <= 0) {
            return -1L;
        }
        return System.currentTimeMillis() + ttl;
    }
}
