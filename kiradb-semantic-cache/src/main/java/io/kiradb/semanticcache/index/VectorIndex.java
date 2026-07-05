package io.kiradb.semanticcache.index;

import java.util.List;

/**
 * In-memory similarity index over L2-normalized vectors.
 *
 * <p>This is the second pluggable seam of the semantic cache (the first is
 * {@code EmbeddingProvider}): the cache depends only on this interface, so a
 * brute-force scan ({@link FlatCosineIndex}) can later be swapped for HNSW or
 * an external vector database (Weaviate) with zero changes to cache logic.
 *
 * <p>All vectors passed in MUST be unit-length; implementations may compute
 * cosine similarity as a plain dot product on that assumption.
 */
public interface VectorIndex {

    /**
     * Add (or replace) a vector under the given id.
     *
     * @param id     stable identifier for the vector
     * @param vector L2-normalized embedding
     */
    void add(String id, float[] vector);

    /**
     * Remove the vector stored under the given id.
     *
     * @param id the identifier to remove
     * @return true if a vector was present and removed
     */
    boolean remove(String id);

    /**
     * Return the {@code k} most similar vectors to the query, best first.
     *
     * @param query L2-normalized query vector
     * @param k     maximum number of results
     * @return up to {@code k} results ordered by descending similarity
     */
    List<SearchResult> search(float[] query, int k);

    /**
     * Number of vectors currently in the index.
     *
     * @return the entry count
     */
    int size();

    /** Remove all vectors. */
    void clear();
}
