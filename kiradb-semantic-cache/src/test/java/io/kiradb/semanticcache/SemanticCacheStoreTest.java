package io.kiradb.semanticcache;

import io.kiradb.semanticcache.embedding.LexicalEmbeddingProvider;
import io.kiradb.semanticcache.index.FlatCosineIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SemanticCacheStore}: threshold semantics, TTL expiry
 * via an injectable clock (no sleeps), delete, stats, and index rebuild from
 * storage after a "restart".
 */
class SemanticCacheStoreTest {

    private TestStorageEngine storage;
    private AtomicLong clock;
    private SemanticCacheStore store;

    @BeforeEach
    void setUp() {
        storage = new TestStorageEngine();
        clock = new AtomicLong(1_000_000L);
        store = newStore();
    }

    private SemanticCacheStore newStore() {
        return new SemanticCacheStore(
                storage, new LexicalEmbeddingProvider(), new FlatCosineIndex(), 0.85, clock::get);
    }

    @Test
    void exactPromptIsAPerfectHit() {
        store.set("what is the capital of France?", "Paris is the capital.", 0);
        Optional<CacheHit> hit = store.get("what is the capital of France?");
        assertTrue(hit.isPresent());
        assertEquals("Paris is the capital.", hit.get().response());
        assertEquals("what is the capital of France?", hit.get().matchedPrompt());
        assertEquals(1.0, hit.get().similarity(), 1e-4);
    }

    @Test
    void paraphraseHitsAboveLoweredThreshold() {
        store.set("what is the capital of France?", "Paris is the capital.", 0);
        Optional<CacheHit> hit = store.get("capital of france?", 0.5);
        assertTrue(hit.isPresent(), "word-overlap paraphrase should match at 0.5");
        assertEquals("Paris is the capital.", hit.get().response());
        assertTrue(hit.get().similarity() < 1.0);
    }

    @Test
    void unrelatedPromptMissesEvenAtLowThreshold() {
        store.set("what is the capital of France?", "Paris is the capital.", 0);
        assertTrue(store.get("how do I bake sourdough bread", 0.5).isEmpty());
    }

    @Test
    void similarPromptMissesBelowStrictThreshold() {
        store.set("what is the capital of France?", "Paris is the capital.", 0);
        // The paraphrase is similar but not identical — a 0.999 threshold rejects it.
        assertTrue(store.get("capital of france?", 0.999).isEmpty());
    }

    @Test
    void ttlExpiryIsAMissAndLazilyDeletes() {
        store.set("temporary prompt about weather", "It is sunny.", 60);
        assertTrue(store.get("temporary prompt about weather").isPresent());

        clock.addAndGet(61_000L); // advance past expiry — no sleeping
        assertTrue(store.get("temporary prompt about weather").isEmpty());
        assertEquals(0, store.stats().entries(), "expired entry should be lazily removed");
    }

    @Test
    void expiredBestMatchDoesNotHideLiveSecondBest() {
        store.set("what is the capital of France?", "stale answer", 60);
        store.set("what is the capital of France please", "fresh answer", 0);

        clock.addAndGet(61_000L); // first entry expires, second never does
        Optional<CacheHit> hit = store.get("what is the capital of France?", 0.5);
        assertTrue(hit.isPresent(), "live second-best candidate should be found");
        assertEquals("fresh answer", hit.get().response());
    }

    @Test
    void deleteRemovesExactPrompt() {
        store.set("prompt to delete later", "answer", 0);
        assertTrue(store.delete("prompt to delete later"));
        assertFalse(store.delete("prompt to delete later"));
        assertTrue(store.get("prompt to delete later").isEmpty());
        assertEquals(0, store.stats().entries());
    }

    @Test
    void statsCountHitsMissesAndTokens() {
        store.set("a prompt about java virtual threads", "x".repeat(400), 0);

        store.get("a prompt about java virtual threads"); // hit
        store.get("completely different sourdough topic"); // miss
        store.get("another unrelated quantum topic");      // miss

        SemanticCacheStats stats = store.stats();
        assertEquals(1, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(1, stats.entries());
        assertEquals(100, stats.estimatedTokensSaved(), "400 chars / 4 per token");
        assertEquals(1.0 / 3.0, stats.hitRate(), 1e-9);
    }

    @Test
    void indexIsRebuiltFromStorageOnRestart() {
        store.set("what is the capital of France?", "Paris is the capital.", 0);
        store.set("how do virtual threads work in java", "They are cheap threads.", 0);

        // Simulate a restart: a brand-new store over the same storage engine.
        SemanticCacheStore reopened = newStore();
        assertEquals(2, reopened.stats().entries(), "index rebuilt from sc:entry: scan");

        Optional<CacheHit> hit = reopened.get("what is the capital of France?");
        assertTrue(hit.isPresent());
        assertEquals("Paris is the capital.", hit.get().response());
    }

    @Test
    void rebuildSkipsAndPurgesExpiredEntries() {
        store.set("short lived prompt", "gone soon", 60);
        store.set("long lived prompt", "still here", 0);
        clock.addAndGet(61_000L);

        SemanticCacheStore reopened = newStore();
        assertEquals(1, reopened.stats().entries(), "expired entry excluded from rebuild");
        assertTrue(reopened.get("long lived prompt").isPresent());
    }

    @Test
    void setOverwritesSamePrompt() {
        store.set("same prompt twice", "first answer", 0);
        store.set("same prompt twice", "second answer", 0);
        assertEquals(1, store.stats().entries());
        assertEquals("second answer", store.get("same prompt twice").get().response());
    }
}
