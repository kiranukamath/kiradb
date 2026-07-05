package io.kiradb.semanticcache;

/**
 * A successful semantic cache lookup.
 *
 * @param matchedPrompt the originally cached prompt that matched the query
 * @param response      the cached response for that prompt
 * @param similarity    cosine similarity between query and matched prompt, in {@code [0, 1]}
 */
public record CacheHit(String matchedPrompt, String response, double similarity) {
}
