package io.kiradb.semanticcache.index;

/**
 * One nearest-neighbor match from a {@link VectorIndex} search.
 *
 * @param id    the identifier the vector was stored under
 * @param score cosine similarity to the query in {@code [-1, 1]}
 *              (in practice {@code [0, 1]} for TF-weighted lexical vectors)
 */
public record SearchResult(String id, float score) {
}
