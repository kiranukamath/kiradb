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
 * <h2>Thread safety</h2>
 * <p>All methods are thread-safe.  Uses {@link ConcurrentHashMap} internally.
 */
public final class AccessTracker {

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

    /**
     * Record one access for a key.
     * Creates a new entry if this is the first time the key has been seen.
     *
     * @param key          the accessed key (never null)
     * @param keySizeBytes approximate size of the key in bytes
     */
    public void recordAccess(final byte[] key, final int keySizeBytes) {
        data.compute(new ByteKey(key), (k, existing) -> {
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
