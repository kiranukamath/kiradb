package io.kiradb.semanticcache.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LexicalEmbeddingProvider}: the normalization contract,
 * cross-call determinism, and the core property that word-overlap paraphrases
 * score higher than unrelated text.
 */
class LexicalEmbeddingProviderTest {

    private final LexicalEmbeddingProvider provider = new LexicalEmbeddingProvider();

    private static double norm(final float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(final float[] a, final float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    @Test
    void vectorsAreUnitLength() {
        assertEquals(1.0, norm(provider.embed("what is the capital of France?")), 1e-4);
        assertEquals(1.0, norm(provider.embed("x")), 1e-4);
    }

    @Test
    void emptyTextGivesZeroVector() {
        assertEquals(0.0, norm(provider.embed("   ")), 1e-9);
    }

    @Test
    void embeddingIsDeterministic() {
        assertArrayEquals(
                provider.embed("hello semantic world"),
                provider.embed("hello semantic world"));
        // A fresh instance must agree too — no per-instance state.
        assertArrayEquals(
                provider.embed("hello semantic world"),
                new LexicalEmbeddingProvider().embed("hello semantic world"));
    }

    @Test
    void dimensionAndIdAreStable() {
        assertEquals(512, provider.dimension());
        assertEquals(512, provider.embed("anything").length);
        assertEquals("lexical-fnv1a-512", provider.id());
    }

    @Test
    void paraphraseScoresHigherThanUnrelated() {
        float[] original = provider.embed("what is the capital of France?");
        float[] paraphrase = provider.embed("capital of france?");
        float[] unrelated = provider.embed("how do I bake sourdough bread");

        double paraphraseSim = cosine(original, paraphrase);
        double unrelatedSim = cosine(original, unrelated);

        assertTrue(paraphraseSim > unrelatedSim,
                "paraphrase " + paraphraseSim + " should beat unrelated " + unrelatedSim);
        assertTrue(paraphraseSim > 0.5, "word-overlap paraphrase should be clearly similar");
        assertTrue(unrelatedSim < 0.3, "unrelated text should be clearly dissimilar");
    }

    @Test
    void caseAndPunctuationAreIgnored() {
        assertEquals(1.0,
                cosine(provider.embed("Hello, World!"), provider.embed("hello world")), 1e-4);
    }
}
