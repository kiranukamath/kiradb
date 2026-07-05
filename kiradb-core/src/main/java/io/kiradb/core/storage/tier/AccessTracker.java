package io.kiradb.core.storage.tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-key access statistics and computes time-decayed hotness scores.
 *
 * <h2>Role in the system</h2>
 * <p>AccessTracker is a <em>sensor</em> only.  It records access events and
 * computes scores.  It never moves data between tiers — that is the job of
 * {@link TierManager} and {@link TierOrchestrator}.
 *
 * <h2>Score formula</h2>
 * <pre>
 *   score = accessCount / 2^(elapsedSeconds / DECAY_HALF_LIFE_SECONDS)
 * </pre>
 * <p>The score halves every hour of inactivity.  A key accessed 100 times
 * two hours ago scores lower than one accessed 5 times 10 seconds ago —
 * recent access matters more than historical frequency.
 *
 * <h2>Hard cap (Phase 13 hardening)</h2>
 * <p>The only backstop against unbounded growth used to be the periodic
 * {@code MIN_TRACK_SCORE} purge run by {@link TierManager} every 5 minutes.
 * A pathological access pattern — a full-keyspace scan that touches every key
 * exactly once and never returns — can balloon the tracked-key count between
 * purge cycles, since every touched key gets an entry with a nonzero score
 * and won't be purged until it decays below the threshold. {@code maxTrackedEntries}
 * puts a hard ceiling on this: once at capacity, inserting a new key evicts the
 * tracked entry with the oldest {@code lastAccessMs} (the "coldest by recency"
 * entry) to make room. Recommended default is {@code 10 * MemCache.maxEntries}
 * (see {@link #recommendedMaxTrackedEntries(int)}) — enough headroom that the
 * warm/tracked-but-not-hot population (per CLAUDE.md's Phase 5 sizing notes)
 * fits comfortably, while still bounding worst-case memory.
 *
 * <p><b>Why O(n) scan-for-oldest instead of a min-heap:</b> eviction here only
 * fires when the tracker is completely full, which — given the generous 10x
 * default headroom — should be rare relative to the purge cycle in realistic
 * workloads. A linear scan over the tracked-key map is the simplest correct
 * implementation and avoids maintaining a second data structure (a heap keyed
 * on {@code lastAccessMs}) that itself needs updates on every access. If
 * profiling ever shows this eviction path running hot enough to matter, the
 * documented upgrade is a min-heap ordered by {@code lastAccessMs} with lazy
 * deletion (O(log n) eviction, O(log n) touch) — deferred until there's a
 * concrete workload that hits this path often enough to need it.
 *
 * <h2>Thread safety</h2>
 * <p>All methods are thread-safe.  Uses {@link ConcurrentHashMap} internally.
 */
public final class AccessTracker {

    /** Sentinel meaning "no hard cap" — used by the default (no-arg) constructor. */
    private static final int UNBOUNDED = -1;

    /**
     * Multiplier applied to a MemCache's {@code maxEntries} to compute the
     * recommended {@code maxTrackedEntries}, per the Phase 13 backlog note.
     */
    private static final int RECOMMENDED_MULTIPLIER = 10;

    /**
     * Compute the recommended {@code maxTrackedEntries} for a MemCache of the
     * given entry-count capacity: {@code 10 * memCacheMaxEntries}.
     *
     * @param memCacheMaxEntries the MemCache's {@code maxEntries}
     * @return recommended hard cap for a paired AccessTracker
     */
    public static int recommendedMaxTrackedEntries(final int memCacheMaxEntries) {
        return RECOMMENDED_MULTIPLIER * memCacheMaxEntries;
    }

    /**
     * Internal per-key mutable state.
     * Stored in ConcurrentHashMap; updated via compute() for atomicity.
     */
    private static final class KeyStats {
        volatile long accessCount;
        volatile long lastAccessMs;
        final long firstTrackedMs;
        volatile int keySizeBytes;

        KeyStats(final int keySizeBytes) {
            this.accessCount   = 1;
            this.lastAccessMs  = System.currentTimeMillis();
            this.firstTrackedMs = this.lastAccessMs;
            this.keySizeBytes  = keySizeBytes;
        }
    }

    /**
     * Wrapper so {@code byte[]} can be used as a {@link ConcurrentHashMap} key.
     * Java arrays do not override {@code equals}/{@code hashCode} — this does.
     */
    private record ByteKey(byte[] bytes) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof ByteKey bk && Arrays.equals(bytes, bk.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final ConcurrentHashMap<ByteKey, KeyStats> data = new ConcurrentHashMap<>();

    /** Hard cap on tracked entries, or {@link #UNBOUNDED} for no cap. */
    private final int maxTrackedEntries;

    /**
     * Create an AccessTracker with no hard cap on tracked entries.
     *
     * <p>Prefer {@link #AccessTracker(int)} in production — an unbounded
     * tracker relies entirely on the periodic purge cycle as a backstop
     * against pathological scan workloads (see class Javadoc).
     */
    public AccessTracker() {
        this(UNBOUNDED);
    }

    /**
     * Create an AccessTracker with a hard cap on the number of tracked entries.
     *
     * @param maxTrackedEntries maximum distinct keys to track before the oldest
     *                          (by {@code lastAccessMs}) is evicted to make room;
     *                          see {@link #recommendedMaxTrackedEntries(int)} for
     *                          the suggested default sizing
     */
    public AccessTracker(final int maxTrackedEntries) {
        if (maxTrackedEntries < 1 && maxTrackedEntries != UNBOUNDED) {
            throw new IllegalArgumentException("maxTrackedEntries must be >= 1");
        }
        this.maxTrackedEntries = maxTrackedEntries;
    }

    /**
     * Record one access for a key.
     * Creates a new entry if this is the first time the key has been seen.
     * If the tracker is at its hard cap and this is a new key, the tracked
     * entry with the oldest {@code lastAccessMs} is evicted first.
     *
     * @param key          the accessed key (never null)
     * @param keySizeBytes approximate size of the key in bytes
     */
    public void recordAccess(final byte[] key, final int keySizeBytes) {
        ByteKey byteKey = new ByteKey(key);
        if (maxTrackedEntries != UNBOUNDED
                && data.size() >= maxTrackedEntries
                && !data.containsKey(byteKey)) {
            evictOldest();
        }
        data.compute(byteKey, (k, existing) -> {
            if (existing == null) {
                return new KeyStats(keySizeBytes);
            }
            existing.accessCount++;
            existing.lastAccessMs = System.currentTimeMillis();
            existing.keySizeBytes = keySizeBytes;
            return existing;
        });
    }

    /**
     * @return the configured hard cap on tracked entries, or {@code -1} if unbounded
     */
    public int maxTrackedEntries() {
        return maxTrackedEntries;
    }

    /**
     * Evict the tracked entry with the oldest {@code lastAccessMs}.
     * O(n) scan — see class Javadoc for why this is an acceptable trade-off
     * versus maintaining a min-heap.
     */
    private void evictOldest() {
        ByteKey oldestKey = null;
        long oldestAccessMs = Long.MAX_VALUE;
        for (var entry : data.entrySet()) {
            long lastAccessMs = entry.getValue().lastAccessMs;
            if (lastAccessMs < oldestAccessMs) {
                oldestAccessMs = lastAccessMs;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            data.remove(oldestKey);
        }
    }

    /**
     * Return the current time-decayed score for a key.
     * Returns {@code 0.0} if the key has never been tracked.
     *
     * @param key the key to score
     * @return hotness score — higher means hotter
     */
    public double score(final byte[] key) {
        KeyStats stats = data.get(new ByteKey(key));
        if (stats == null) {
            return 0.0;
        }
        return AccessStats.computeScore(stats.accessCount, stats.lastAccessMs);
    }

    /**
     * Build a point-in-time {@link AccessStats} snapshot for a key.
     * Returns {@code null} if the key has never been tracked.
     *
     * @param key the key to snapshot
     * @return snapshot, or null if not tracked
     */
    public AccessStats snapshot(final byte[] key) {
        KeyStats stats = data.get(new ByteKey(key));
        if (stats == null) {
            return null;
        }
        double score = AccessStats.computeScore(stats.accessCount, stats.lastAccessMs);
        return new AccessStats(key, stats.accessCount, stats.lastAccessMs,
                stats.firstTrackedMs, stats.keySizeBytes, score);
    }

    /**
     * Return snapshots for ALL currently tracked keys.
     * Used by {@link TierManager} to scan the full key space every cycle.
     *
     * @return collection of snapshots — one per tracked key
     */
    public Collection<AccessStats> allSnapshots() {
        Collection<AccessStats> result = new ArrayList<>(data.size());
        data.forEach((bk, stats) -> {
            double score = AccessStats.computeScore(stats.accessCount, stats.lastAccessMs);
            result.add(new AccessStats(bk.bytes(), stats.accessCount, stats.lastAccessMs,
                    stats.firstTrackedMs, stats.keySizeBytes, score));
        });
        return result;
    }

    /**
     * Stop tracking a key.  Called when a key is deleted from the database
     * to prevent unbounded tracker growth.
     *
     * @param key the key to forget
     */
    public void remove(final byte[] key) {
        data.remove(new ByteKey(key));
    }

    /**
     * Return the number of keys currently being tracked.
     *
     * @return tracked key count
     */
    public int size() {
        return data.size();
    }
}
