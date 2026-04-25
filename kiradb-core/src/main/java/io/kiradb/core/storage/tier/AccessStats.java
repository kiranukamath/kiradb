package io.kiradb.core.storage.tier;

/**
 * A point-in-time snapshot of access statistics for a single key.
 *
 * <p>Passed to {@link TierOrchestrator#evaluate} so the orchestrator can decide
 * whether to promote, evict, or keep the key.  Contains everything a rule-based
 * <em>or</em> AI-driven orchestrator might need:
 *
 * <ul>
 *   <li>Raw access count and timestamps for rule-based threshold checks.</li>
 *   <li>Key size for capacity-aware decisions (large cold keys should be evicted first).</li>
 *   <li>A pre-computed time-decayed score for convenience.</li>
 * </ul>
 *
 * <h2>Score formula</h2>
 * <pre>
 *   score = accessCount / 2^(elapsedSeconds / DECAY_HALF_LIFE_SECONDS)
 * </pre>
 * <p>The score halves every {@code DECAY_HALF_LIFE_SECONDS} seconds of inactivity.
 * A key accessed 100 times 2 hours ago scores lower than one accessed 5 times 10 seconds ago.
 * Recent access beats historical frequency — which is what we want.
 *
 * @param key            the key these stats belong to
 * @param accessCount    total number of accesses recorded since first tracking
 * @param lastAccessMs   epoch-millis of the most recent access
 * @param firstTrackedMs epoch-millis when this key was first seen by {@link AccessTracker}
 * @param keySizeBytes   approximate size of the key in bytes (used for capacity decisions)
 * @param decayedScore   time-decayed score computed at snapshot time
 */
public record AccessStats(
        byte[] key,
        long accessCount,
        long lastAccessMs,
        long firstTrackedMs,
        int keySizeBytes,
        double decayedScore) {

    /** Score halves every hour of inactivity. */
    static final double DECAY_HALF_LIFE_SECONDS = 3600.0;

    /**
     * Compute a time-decayed score from raw counts.
     * Called by {@link AccessTracker} when building a snapshot.
     *
     * @param accessCount  total accesses
     * @param lastAccessMs epoch-millis of last access
     * @return score — higher means hotter
     */
    static double computeScore(final long accessCount, final long lastAccessMs) {
        long elapsedMs = System.currentTimeMillis() - lastAccessMs;
        double elapsedSeconds = Math.max(0, elapsedMs) / 1000.0;
        return accessCount / Math.pow(2.0, elapsedSeconds / DECAY_HALF_LIFE_SECONDS);
    }
}
