package io.kiradb.semanticcache.embedding;

/**
 * Turns text into a fixed-length vector of floats — an <em>embedding</em>.
 *
 * <p>Semantically similar texts must map to vectors that point in similar
 * directions, so that cosine similarity (the angle between vectors) measures
 * meaning-closeness. This is the pluggable seam of the semantic cache: swap
 * a lexical hasher for a neural model (Ollama, OpenAI) without touching the
 * cache or index code.
 *
 * <h2>Normalization contract</h2>
 * <p>Every vector returned by {@link #embed(String)} MUST be L2-normalized
 * (unit length). The index relies on this: for unit vectors, cosine
 * similarity reduces to a plain dot product, which is both cheaper and
 * numerically simpler. Violating this contract silently corrupts every
 * similarity score in the cache.
 */
public interface EmbeddingProvider {

    /**
     * Embed the given text into a fixed-dimension, L2-normalized float vector.
     *
     * @param text the text to embed (never null)
     * @return a unit-length vector of {@link #dimension()} floats
     */
    float[] embed(String text);

    /**
     * The dimensionality of vectors produced by this provider.
     *
     * @return vector length, or {@code -1} if not yet known (e.g. a remote
     *         model whose dimension is discovered on first call)
     */
    int dimension();

    /**
     * Stable identifier for this provider + model combination.
     *
     * <p>Vectors from different providers live in different spaces and must
     * never be compared. Callers can use this id to detect provider changes
     * (e.g. invalidate a persisted index built with a different embedder).
     *
     * @return a stable id such as {@code "lexical-fnv1a-512"} or {@code "ollama:nomic-embed-text"}
     */
    String id();
}
