package io.kiradb.semanticcache.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact (non-approximate) nearest-neighbor index: a brute-force scan over
 * every stored vector.
 *
 * <p>Because all vectors are L2-normalized (see {@link VectorIndex}), cosine
 * similarity is just a dot product — one multiply-add per dimension. Each
 * query is <b>O(n·d)</b> for n entries of dimension d. At 512 dims that is
 * roughly 50M float ops for 100k entries — sub-10ms on modern hardware, so
 * a flat scan is the right choice up to ~50k entries: zero build cost, zero
 * recall loss, trivially correct. Beyond that, swap in an HNSW graph index or
 * a Weaviate sidecar behind the same {@link VectorIndex} interface — those
 * trade a small recall loss for O(log n)-ish query time.
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap}; a search iterates a
 * weakly-consistent snapshot, which is acceptable for cache semantics
 * (a racing add may or may not be visible — both outcomes are correct).
 */
public final class FlatCosineIndex implements VectorIndex {

    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    @Override
    public void add(final String id, final float[] vector) {
        vectors.put(id, vector.clone());
    }

    @Override
    public boolean remove(final String id) {
        return vectors.remove(id) != null;
    }

    @Override
    public List<SearchResult> search(final float[] query, final int k) {
        if (k <= 0 || vectors.isEmpty()) {
            return List.of();
        }
        // Min-heap of size k: the root is the worst of the current best-k,
        // so a candidate only enters if it beats the root. O(n log k).
        PriorityQueue<SearchResult> heap =
                new PriorityQueue<>(k, (a, b) -> Float.compare(a.score(), b.score()));
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            float score = dot(query, entry.getValue());
            if (heap.size() < k) {
                heap.offer(new SearchResult(entry.getKey(), score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new SearchResult(entry.getKey(), score));
            }
        }
        List<SearchResult> results = new ArrayList<>(heap);
        results.sort((a, b) -> Float.compare(b.score(), a.score()));
        return Collections.unmodifiableList(results);
    }

    @Override
    public int size() {
        return vectors.size();
    }

    @Override
    public void clear() {
        vectors.clear();
    }

    private static float dot(final float[] a, final float[] b) {
        int len = Math.min(a.length, b.length);
        float sum = 0f;
        for (int i = 0; i < len; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
