package io.kiradb.core.storage.tier;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Optional;

/**
 * Two-tier {@link StorageEngine} that keeps hot data in memory and warm/cold data on SSD.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Tier 1 — {@link MemCache}           ~microseconds   hot data (35% of JVM heap)
 *   Tier 2 — LsmStorageEngine (SSD)     ~milliseconds   warm + cold data
 * </pre>
 * <p>S3 is used for periodic <em>backups only</em> and is not in the read path.
 *
 * <h2>Write path</h2>
 * <ol>
 *   <li>Write to Tier 2 first (durable).</li>
 *   <li>Also insert into MemCache (Tier 1) — the write is immediately hot.</li>
 *   <li>Record an access in {@link AccessTracker}.</li>
 * </ol>
 *
 * <h2>Read path</h2>
 * <ol>
 *   <li>Check MemCache (Tier 1) — return immediately on hit.</li>
 *   <li>Check LsmStorageEngine (Tier 2) — return to client immediately on hit.</li>
 *   <li>After returning the value, asynchronously check whether the key's score
 *       qualifies for promotion into MemCache.  The client is never blocked by this.</li>
 * </ol>
 *
 * <h2>Promotion / eviction</h2>
 * <p>{@link TierManager} runs in the background every 5 minutes and bulk-promotes
 * or bulk-evicts keys based on {@link TierOrchestrator} decisions.  On-demand
 * promotion also happens after every Tier 2 read (async).
 *
 * <h2>Extensibility</h2>
 * <p>Pass a different {@link TierOrchestrator} implementation to the constructor
 * to swap in an AI/ML-driven promotion strategy — no other code changes needed.
 *
 * <h2>Metrics (Phase 13 hardening)</h2>
 * <p>A {@link MeterRegistry} can be supplied to publish MemCache hit/miss/eviction
 * counts, AccessTracker size, and TierManager promote/evict/purge counts as
 * Micrometer gauges/counters. Callers that don't care about metrics can omit it —
 * constructors without a {@code MeterRegistry} parameter default to a private
 * {@link SimpleMeterRegistry} instance (in-memory only, never exported anywhere),
 * so existing call sites keep compiling and running unchanged. See
 * {@link #registerMetrics(MeterRegistry)} for the exact meter names.
 */
public final class TieredStorageEngine implements StorageEngine {

    private static final Logger LOG = LoggerFactory.getLogger(TieredStorageEngine.class);

    private final MemCache         memCache;
    private final StorageEngine    tier2;
    private final AccessTracker    accessTracker;
    private final TierOrchestrator orchestrator;
    private final TierManager      tierManager;
    private final MeterRegistry    meterRegistry;

    /**
     * Create a TieredStorageEngine with a {@link RuleBasedOrchestrator} and default settings.
     *
     * @param tier2         the underlying SSD storage engine (LsmStorageEngine)
     * @param maxCacheEntries maximum number of entries to keep in MemCache
     */
    public TieredStorageEngine(final StorageEngine tier2, final int maxCacheEntries) {
        this(tier2, maxCacheEntries, new RuleBasedOrchestrator(), TierManager.DEFAULT_INTERVAL_MS);
    }

    /**
     * Create a TieredStorageEngine with a custom orchestrator and scan interval.
     * Use this constructor to plug in an AI orchestrator in a future phase.
     * Metrics are published to a private, never-exported {@link SimpleMeterRegistry}
     * — use {@link #TieredStorageEngine(StorageEngine, int, TierOrchestrator, long, MeterRegistry)}
     * to publish to a real registry (e.g. one backed by a Prometheus exporter).
     *
     * @param tier2           the underlying SSD storage engine
     * @param maxCacheEntries maximum MemCache entries
     * @param orchestrator    promotion/eviction strategy
     * @param tierScanIntervalMs how often TierManager scans all keys (milliseconds)
     */
    public TieredStorageEngine(final StorageEngine tier2,
                                final int maxCacheEntries,
                                final TierOrchestrator orchestrator,
                                final long tierScanIntervalMs) {
        this(tier2, maxCacheEntries, orchestrator, tierScanIntervalMs, new SimpleMeterRegistry());
    }

    /**
     * Create a TieredStorageEngine with a custom orchestrator, scan interval, and
     * an explicit {@link MeterRegistry} to publish metrics to.
     *
     * @param tier2              the underlying SSD storage engine
     * @param maxCacheEntries    maximum MemCache entries
     * @param orchestrator       promotion/eviction strategy
     * @param tierScanIntervalMs how often TierManager scans all keys (milliseconds)
     * @param meterRegistry      registry to publish MemCache/AccessTracker/TierManager
     *                           metrics to (see {@link #registerMetrics(MeterRegistry)})
     */
    public TieredStorageEngine(final StorageEngine tier2,
                                final int maxCacheEntries,
                                final TierOrchestrator orchestrator,
                                final long tierScanIntervalMs,
                                final MeterRegistry meterRegistry) {
        this.tier2         = tier2;
        this.orchestrator  = orchestrator;
        this.meterRegistry = meterRegistry;
        // Hard-cap the tracker at 10x MemCache capacity (Phase 13 hardening) so a
        // pathological full-keyspace scan between TierManager purge cycles cannot
        // balloon tracked-key memory unboundedly. See AccessTracker's class Javadoc.
        this.accessTracker = new AccessTracker(
                AccessTracker.recommendedMaxTrackedEntries(maxCacheEntries));
        this.memCache      = new MemCache(maxCacheEntries, accessTracker);
        this.tierManager   = new TierManager(memCache, accessTracker, orchestrator,
                tier2, tierScanIntervalMs);
        this.tierManager.start();
        registerMetrics(meterRegistry);
    }

    /**
     * Register all Phase 13 hardening metrics on the given registry.
     *
     * <h2>Meter names</h2>
     * <pre>
     *   kiradb.memcache.size                (gauge)   current MemCache entry count
     *   kiradb.memcache.hits                (counter) cumulative MemCache hits
     *   kiradb.memcache.misses              (counter) cumulative MemCache misses
     *   kiradb.memcache.evictions           (counter) cumulative MemCache evictions
     *   kiradb.accesstracker.size           (gauge)   current AccessTracker tracked-key count
     *   kiradb.tiermanager.promotions       (counter) cumulative WARM-&gt;HOT promotions
     *   kiradb.tiermanager.evictions        (counter) cumulative HOT-&gt;WARM evictions
     *   kiradb.tiermanager.purges           (counter) cumulative tracker purges
     * </pre>
     * Counters are implemented as {@link Gauge}s over the underlying cumulative
     * {@code AtomicLong}s (rather than Micrometer {@code Counter}s) because the
     * source of truth already lives in {@link MemCache}/{@link TierManager} as
     * plain counters usable without Micrometer on the classpath — a gauge over
     * "current cumulative value" is the correct Micrometer primitive for
     * externally-maintained monotonic counts.
     *
     * @param registry the registry to publish to
     */
    private void registerMetrics(final MeterRegistry registry) {
        Gauge.builder("kiradb.memcache.size", memCache, MemCache::size)
                .description("Current number of entries held in MemCache (Tier 1)")
                .register(registry);
        Gauge.builder("kiradb.memcache.hits", memCache, MemCache::hitCount)
                .description("Cumulative MemCache hit count")
                .register(registry);
        Gauge.builder("kiradb.memcache.misses", memCache, MemCache::missCount)
                .description("Cumulative MemCache miss count")
                .register(registry);
        Gauge.builder("kiradb.memcache.evictions", memCache, MemCache::evictionCount)
                .description("Cumulative MemCache eviction count (capacity-driven)")
                .register(registry);
        Gauge.builder("kiradb.accesstracker.size", accessTracker, AccessTracker::size)
                .description("Current number of keys tracked by AccessTracker")
                .register(registry);
        Gauge.builder("kiradb.tiermanager.promotions", tierManager, TierManager::cumulativePromoted)
                .description("Cumulative count of WARM -> HOT promotions")
                .register(registry);
        Gauge.builder("kiradb.tiermanager.evictions", tierManager, TierManager::cumulativeEvicted)
                .description("Cumulative count of HOT -> WARM evictions")
                .register(registry);
        Gauge.builder("kiradb.tiermanager.purges", tierManager, TierManager::cumulativePurged)
                .description("Cumulative count of AccessTracker entries purged")
                .register(registry);
    }

    /**
     * @return the {@link MeterRegistry} metrics are published to (a private
     *         {@link SimpleMeterRegistry} unless one was explicitly supplied)
     */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    @Override
    public void put(final byte[] key, final byte[] value) {
        tier2.put(key, value);
        memCache.put(key, value, -1L);
        accessTracker.recordAccess(key, value.length);
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        tier2.put(key, value, expiryMillis);
        memCache.put(key, value, expiryMillis);
        accessTracker.recordAccess(key, value.length);
    }

    @Override
    public boolean delete(final byte[] key) {
        boolean existed = tier2.delete(key);
        memCache.remove(key);
        accessTracker.remove(key);
        return existed;
    }

    @Override
    public boolean expire(final byte[] key, final long expiryMillis) {
        boolean applied = tier2.expire(key, expiryMillis);
        if (applied && memCache.contains(key)) {
            // Refresh the cache entry with the new expiry
            Optional<byte[]> value = tier2.get(key);
            value.ifPresent(v -> memCache.put(key, v, expiryMillis));
        }
        return applied;
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Override
    public Optional<byte[]> get(final byte[] key) {
        // Tier 1: MemCache hit — fastest path
        Optional<byte[]> cached = memCache.get(key);
        if (cached.isPresent()) {
            accessTracker.recordAccess(key, cached.get().length);
            return cached;
        }

        // Tier 2: SSD hit — return immediately, then async-promote if score qualifies
        Optional<byte[]> fromDisk = tier2.get(key);
        fromDisk.ifPresent(value -> {
            accessTracker.recordAccess(key, value.length);
            // Fire-and-forget: check if this key deserves promotion into MemCache.
            // Client is NOT blocked — this runs on a separate virtual thread.
            Thread.ofVirtual().name("tier-promote").start(() -> maybePromote(key, value));
        });
        return fromDisk;
    }

    @Override
    public boolean exists(final byte[] key) {
        if (memCache.contains(key)) {
            return true;
        }
        return tier2.exists(key);
    }

    @Override
    public long ttlMillis(final byte[] key) {
        // TTL is authoritative on Tier 2
        return tier2.ttlMillis(key);
    }

    @Override
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
        // Scan is a range operation — always goes to Tier 2 (LSM is sorted, MemCache is not)
        return tier2.scan(startKey, endKey);
    }

    @Override
    public void close() {
        tierManager.close();
        try {
            tier2.close();
        } catch (Exception e) {
            LOG.warn("Error closing tier2: {}", e.getMessage());
        }
    }

    // ── Observability (read-only, Phase 9 dashboard) ─────────────────────────

    /**
     * Number of entries currently held in MemCache (Tier 1) — the "hot" key count.
     *
     * @return current MemCache entry count
     */
    public int memCacheSize() {
        return memCache.size();
    }

    /**
     * Configured MemCache capacity in entries.
     *
     * @return maximum MemCache entry count before eviction triggers
     */
    public int memCacheMaxEntries() {
        return memCache.maxEntries();
    }

    /**
     * Number of keys currently tracked by the {@link AccessTracker} — the working
     * set (hot + recently-touched warm keys), not the full dataset.
     *
     * @return tracked key count
     */
    public int trackedKeys() {
        return accessTracker.size();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Check whether this key's score justifies promotion into MemCache.
     * Called asynchronously from a virtual thread after a Tier 2 hit.
     *
     * @param key   the key that was just read from disk
     * @param value the value that was returned
     */
    private void maybePromote(final byte[] key, final byte[] value) {
        AccessStats stats = accessTracker.snapshot(key);
        if (stats == null) {
            return;
        }
        TierDecision decision = orchestrator.evaluate(key, stats, TierLocation.WARM);
        if (decision == TierDecision.PROMOTE) {
            long ttl    = tier2.ttlMillis(key);
            long expiry = ttl > 0 ? System.currentTimeMillis() + ttl : -1L;
            memCache.put(key, value, expiry);
            LOG.debug("Promoted key to HOT (score={})", stats.decayedScore());
        }
    }
}
