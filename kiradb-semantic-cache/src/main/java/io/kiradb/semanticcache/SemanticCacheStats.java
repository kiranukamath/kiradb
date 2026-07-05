package io.kiradb.semanticcache;

/**
 * Point-in-time counters for the semantic cache.
 *
 * <p>Counters are process-lifetime only (reset on restart); the entry count
 * reflects the live index, which IS rebuilt from storage on restart.
 *
 * @param hits                 lookups that matched at or above threshold
 * @param misses               lookups that found no acceptable match
 * @param entries              live entries currently in the index
 * @param estimatedTokensSaved sum over hits of {@code response.length() / 4}
 *                             — the standard rough chars-per-token heuristic
 */
public record SemanticCacheStats(long hits, long misses, long entries, long estimatedTokensSaved) {

    /**
     * Hit rate as a fraction of total lookups.
     *
     * @return {@code hits / (hits + misses)}, or 0.0 when no lookups have happened
     */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
