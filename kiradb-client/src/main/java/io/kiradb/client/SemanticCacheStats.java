package io.kiradb.client;

/**
 * Semantic cache counters ({@code SC.STATS} reply).
 *
 * @param hits                 lookups that returned a cached response
 * @param misses               lookups that found nothing above the threshold
 * @param entries              number of cached prompt/response pairs
 * @param estimatedTokensSaved rough count of LLM tokens not spent thanks to cache hits
 * @param hitRate              hits / (hits + misses); 0.0 when there is no traffic yet
 */
public record SemanticCacheStats(
        long hits,
        long misses,
        long entries,
        long estimatedTokensSaved,
        double hitRate) {
}
