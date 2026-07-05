package io.kiradb.semanticcache.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the JSON-parsing half of {@link OllamaEmbeddingProvider} — no network,
 * no running Ollama daemon required.
 */
class OllamaEmbeddingProviderTest {

    @Test
    void parsesAndNormalizesEmbeddingArray() {
        float[] v = OllamaEmbeddingProvider.parseEmbedding("{\"embedding\":[3.0,4.0]}");
        assertEquals(2, v.length);
        // Input (3,4) has norm 5 — normalized to (0.6, 0.8).
        assertEquals(0.6f, v[0], 1e-6f);
        assertEquals(0.8f, v[1], 1e-6f);
    }

    @Test
    void rejectsMissingEmbeddingField() {
        assertThrows(EmbeddingException.class,
                () -> OllamaEmbeddingProvider.parseEmbedding("{\"error\":\"model not found\"}"));
    }

    @Test
    void rejectsEmptyEmbeddingArray() {
        assertThrows(EmbeddingException.class,
                () -> OllamaEmbeddingProvider.parseEmbedding("{\"embedding\":[]}"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(EmbeddingException.class,
                () -> OllamaEmbeddingProvider.parseEmbedding("not json at all"));
    }

    @Test
    void idIncludesModelName() {
        OllamaEmbeddingProvider provider =
                new OllamaEmbeddingProvider("http://localhost:11434/", "nomic-embed-text");
        assertEquals("ollama:nomic-embed-text", provider.id());
        assertEquals(-1, provider.dimension(), "dimension unknown before first embed");
    }
}
