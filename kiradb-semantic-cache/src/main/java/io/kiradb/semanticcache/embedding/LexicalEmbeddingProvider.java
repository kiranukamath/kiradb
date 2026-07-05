package io.kiradb.semanticcache.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Default, zero-dependency embedding provider based on <em>feature hashing</em>
 * (the "hashing trick"), not a neural network.
 *
 * <p>The text is lowercased and tokenized into three feature families:
 * <ul>
 *   <li><b>word unigrams</b> — each word</li>
 *   <li><b>word bigrams</b> — each adjacent word pair (captures phrase order)</li>
 *   <li><b>character trigrams</b> — sliding 3-char windows over the normalized
 *       text (tolerates typos, plurals, small morphological changes)</li>
 * </ul>
 * Each feature is hashed with FNV-1a into one of {@value #DIMENSION} buckets,
 * its term frequency accumulated there, and the resulting vector L2-normalized.
 *
 * <h2>Honesty note</h2>
 * <p>This is a <b>lexical</b> embedder: it measures <em>word overlap</em>, not
 * meaning. Paraphrases that share words ("what is the capital of France?" vs
 * "capital of france?") score high; true synonymy with disjoint vocabulary
 * ("car" vs "automobile") scores near zero. For real semantic matching, swap in
 * {@link OllamaEmbeddingProvider} or another neural provider. This class exists
 * so the cache works out of the box with no external service and fully
 * deterministically (FNV-1a is stable across JVMs — unlike
 * {@code String.hashCode()} whose contract, while stable today, is not something
 * we want persisted vectors to depend on).
 *
 * <p>Thread-safe and stateless.
 */
public final class LexicalEmbeddingProvider implements EmbeddingProvider {

    /** Fixed output dimensionality of the hashed feature space. */
    public static final int DIMENSION = 512;

    /** Weight applied to word unigram features. */
    private static final float UNIGRAM_WEIGHT = 1.0f;

    /** Weight applied to word bigram features. */
    private static final float BIGRAM_WEIGHT = 1.0f;

    /** Weight applied to character trigram features (many per text, so damped). */
    private static final float TRIGRAM_WEIGHT = 0.5f;

    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    @Override
    public float[] embed(final String text) {
        float[] vector = new float[DIMENSION];
        String normalized = text.toLowerCase().trim();
        if (normalized.isEmpty()) {
            return vector; // zero vector — matches nothing
        }

        List<String> words = tokenize(normalized);
        for (String word : words) {
            addFeature(vector, "u:" + word, UNIGRAM_WEIGHT);
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            addFeature(vector, "b:" + words.get(i) + " " + words.get(i + 1), BIGRAM_WEIGHT);
        }
        String joined = String.join(" ", words);
        for (int i = 0; i + 3 <= joined.length(); i++) {
            addFeature(vector, "t:" + joined.substring(i, i + 3), TRIGRAM_WEIGHT);
        }

        l2Normalize(vector);
        return vector;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String id() {
        return "lexical-fnv1a-" + DIMENSION;
    }

    /** Split on any run of non-letter/non-digit characters. */
    private static List<String> tokenize(final String normalized) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (current.length() > 0) {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }

    /** Hash the feature into a bucket and accumulate its weight there. */
    private static void addFeature(final float[] vector, final String feature, final float weight) {
        int bucket = Math.floorMod(fnv1a(feature), DIMENSION);
        vector[bucket] += weight;
    }

    /**
     * 32-bit FNV-1a over the UTF-8 bytes of the string. Chosen over
     * {@code String.hashCode()} because we control the algorithm: vectors are
     * persisted to disk, so the hash must be identical across JVM versions
     * and vendors forever.
     */
    private static int fnv1a(final String s) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** Scale the vector to unit length in place. Zero vectors are left as-is. */
    private static void l2Normalize(final float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            return;
        }
        double norm = Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
