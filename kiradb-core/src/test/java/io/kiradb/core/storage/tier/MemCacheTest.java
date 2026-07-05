package io.kiradb.core.storage.tier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link MemCache}. */
class MemCacheTest {

    private AccessTracker tracker;
    private MemCache      cache;

    @BeforeEach
    void setUp() {
        tracker = new AccessTracker();
        cache   = new MemCache(5, tracker); // tiny max for eviction testing
    }

    @Test
    void putAndGet() {
        cache.put("city".getBytes(), "Bangalore".getBytes(), -1L);
        Optional<byte[]> result = cache.get("city".getBytes());
        assertTrue(result.isPresent());
        assertArrayEquals("Bangalore".getBytes(), result.get());
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertTrue(cache.get("ghost".getBytes()).isEmpty());
    }

    @Test
    void removeDeletesEntry() {
        cache.put("k".getBytes(), "v".getBytes(), -1L);
        cache.remove("k".getBytes());
        assertTrue(cache.get("k".getBytes()).isEmpty());
        assertFalse(cache.contains("k".getBytes()));
    }

    @Test
    void containsReturnsTrueForLiveEntry() {
        cache.put("k".getBytes(), "v".getBytes(), -1L);
        assertTrue(cache.contains("k".getBytes()));
    }

    @Test
    void expiredEntryReturnedEmpty() throws InterruptedException {
        long expiry = System.currentTimeMillis() + 50; // expires in 50ms
        cache.put("ttl-key".getBytes(), "value".getBytes(), expiry);

        assertTrue(cache.get("ttl-key".getBytes()).isPresent(), "Should be present before expiry");
        Thread.sleep(60);
        assertTrue(cache.get("ttl-key".getBytes()).isEmpty(), "Should be empty after expiry");
        assertFalse(cache.contains("ttl-key".getBytes()), "Should not be contained after expiry");
    }

    @Test
    void updateExistingKey() {
        cache.put("k".getBytes(), "v1".getBytes(), -1L);
        cache.put("k".getBytes(), "v2".getBytes(), -1L);
        assertArrayEquals("v2".getBytes(), cache.get("k".getBytes()).get());
        assertEquals(1, cache.size()); // still one entry
    }

    @Test
    void evictsLowestScoreWhenFull() {
        // Fill cache to max (5 entries), giving "hot" high score
        byte[] hotKey = "hot".getBytes();
        for (int i = 0; i < 50; i++) {
            tracker.recordAccess(hotKey, 3);
        }
        cache.put(hotKey, "hot-value".getBytes(), -1L);

        // Add 4 cold keys with no access history (score=0)
        for (int i = 0; i < 4; i++) {
            byte[] cold = ("cold-" + i).getBytes();
            cache.put(cold, "v".getBytes(), -1L);
        }
        assertEquals(5, cache.size()); // at max

        // Adding one more should evict a cold key, not the hot key
        cache.put("newcomer".getBytes(), "v".getBytes(), -1L);

        assertTrue(cache.contains(hotKey), "Hot key must NOT be evicted");
        assertTrue(cache.contains("newcomer".getBytes()), "New entry must be inserted");
    }

    // ── metrics (Phase 13 hardening) ────────────────────────────────────────

    @Test
    void hitAndMissCountsAreTracked() {
        cache.put("k".getBytes(), "v".getBytes(), -1L);
        cache.get("k".getBytes());       // hit
        cache.get("k".getBytes());       // hit
        cache.get("missing".getBytes()); // miss

        assertEquals(2, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    void expiredGetCountsAsMiss() throws InterruptedException {
        long expiry = System.currentTimeMillis() + 20;
        cache.put("ttl-key".getBytes(), "v".getBytes(), expiry);
        cache.get("ttl-key".getBytes()); // hit, still live
        Thread.sleep(30);
        cache.get("ttl-key".getBytes()); // miss, expired

        assertEquals(1, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    void evictionCountIncrementsOnCapacityEviction() {
        // Cache max is 5 (from setUp). Fill to capacity, then push one more.
        for (int i = 0; i < 5; i++) {
            cache.put(("k" + i).getBytes(), "v".getBytes(), -1L);
        }
        assertEquals(0, cache.evictionCount());

        cache.put("overflow".getBytes(), "v".getBytes(), -1L);
        assertEquals(1, cache.evictionCount());
    }

    @Test
    void clearEmptiesCache() {
        cache.put("a".getBytes(), "1".getBytes(), -1L);
        cache.put("b".getBytes(), "2".getBytes(), -1L);
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.get("a".getBytes()).isEmpty());
    }

    // ── byte-budget mode ─────────────────────────────────────────────────────

    @Test
    void entryCountModeIsDefault() {
        assertFalse(cache.isByteBudgetMode());
        assertEquals(5, cache.maxEntries());
        assertEquals(-1L, cache.maxBytes());
    }

    @Test
    void byteBudgetModeTracksRunningSize() {
        AccessTracker t = new AccessTracker();
        // Budget large enough for exactly ~2 small entries.
        long perEntry = "k0".getBytes().length + "v".repeat(10).getBytes().length
                + MemCache.PER_ENTRY_OVERHEAD_BYTES;
        MemCache byteCache = MemCache.ofMaxBytes(perEntry * 2, t);

        assertTrue(byteCache.isByteBudgetMode());
        assertEquals(0L, byteCache.currentBytes());

        byteCache.put("k0".getBytes(), "v".repeat(10).getBytes(), -1L);
        assertEquals(perEntry, byteCache.currentBytes());

        byteCache.put("k1".getBytes(), "v".repeat(10).getBytes(), -1L);
        assertEquals(perEntry * 2, byteCache.currentBytes());
        assertEquals(2, byteCache.size());
    }

    @Test
    void byteBudgetModeEvictsLowestScoreWhenOverBudget() {
        AccessTracker t = new AccessTracker();
        byte[] hotKey = "hot".getBytes();
        long hotEntry = hotKey.length + "v".repeat(10).getBytes().length
                + MemCache.PER_ENTRY_OVERHEAD_BYTES;
        long coldEntry = "cold0".getBytes().length + "v".repeat(10).getBytes().length
                + MemCache.PER_ENTRY_OVERHEAD_BYTES;
        // Budget for exactly hot + one cold entry; a third entry forces an eviction.
        MemCache byteCache = MemCache.ofMaxBytes(hotEntry + coldEntry, t);

        for (int i = 0; i < 50; i++) {
            t.recordAccess(hotKey, 3);
        }
        byteCache.put(hotKey, "v".repeat(10).getBytes(), -1L);
        byteCache.put("cold0".getBytes(), "v".repeat(10).getBytes(), -1L);
        assertTrue(byteCache.contains(hotKey), "Hot key should still be present before overflow");

        // Adding a third entry should push us over budget and evict the coldest.
        byteCache.put("cold1".getBytes(), "v".repeat(10).getBytes(), -1L);

        assertTrue(byteCache.contains(hotKey), "Hot key must NOT be evicted");
        assertTrue(byteCache.currentBytes() <= byteCache.maxBytes(),
                "current byte usage should stay within budget after eviction");
    }

    @Test
    void byteBudgetModeEvictsMultipleEntriesForOneLargeIncomingValue() {
        AccessTracker t = new AccessTracker();
        long smallEntry = "k".getBytes().length + "v".getBytes().length
                + MemCache.PER_ENTRY_OVERHEAD_BYTES;
        byte[] bigValue = "v".repeat(200).getBytes();
        long bigEntry = "big".getBytes().length + bigValue.length + MemCache.PER_ENTRY_OVERHEAD_BYTES;
        // Budget large enough for the big entry plus only ONE small entry — not
        // enough to hold the big entry alongside all 3 small ones, forcing
        // multiple evictions to admit it.
        MemCache byteCache = MemCache.ofMaxBytes(smallEntry + bigEntry, t);

        byteCache.put("a".getBytes(), "v".getBytes(), -1L);
        byteCache.put("b".getBytes(), "v".getBytes(), -1L);
        byteCache.put("c".getBytes(), "v".getBytes(), -1L);
        assertEquals(3, byteCache.size());

        // A single large value that alone would need to evict multiple small entries.
        byteCache.put("big".getBytes(), bigValue, -1L);

        assertTrue(byteCache.contains("big".getBytes()));
        assertTrue(byteCache.currentBytes() <= byteCache.maxBytes(),
                "current byte usage should stay within budget after evicting multiple entries");
        assertTrue(byteCache.size() < 4, "at least one small entry must have been evicted");
    }

    @Test
    void byteBudgetModeAllowsTemporaryOverBudgetWhenSingleEntryExceedsCapacity() {
        // An incoming entry larger than the entire budget cannot be made to fit
        // by evicting everything else — MemCache still admits it (documented
        // best-effort behavior: it is a cache, not authoritative storage) rather
        // than silently rejecting the write.
        AccessTracker t = new AccessTracker();
        MemCache byteCache = MemCache.ofMaxBytes(50L, t);
        byte[] hugeValue = "v".repeat(500).getBytes();
        byteCache.put("huge".getBytes(), hugeValue, -1L);
        assertTrue(byteCache.contains("huge".getBytes()));
        assertEquals(1, byteCache.size());
    }

    @Test
    void byteBudgetModeRemoveDecrementsRunningSize() {
        AccessTracker t = new AccessTracker();
        MemCache byteCache = MemCache.ofMaxBytes(10_000L, t);
        byteCache.put("k".getBytes(), "value".getBytes(), -1L);
        long afterPut = byteCache.currentBytes();
        assertTrue(afterPut > 0);

        byteCache.remove("k".getBytes());
        assertEquals(0L, byteCache.currentBytes());
    }

    @Test
    void byteBudgetModeReplaceExistingKeyAdjustsSizeCorrectly() {
        AccessTracker t = new AccessTracker();
        MemCache byteCache = MemCache.ofMaxBytes(10_000L, t);
        byteCache.put("k".getBytes(), "short".getBytes(), -1L);
        long afterShort = byteCache.currentBytes();

        byteCache.put("k".getBytes(), "a-much-longer-value-string".getBytes(), -1L);
        long afterLong = byteCache.currentBytes();

        assertTrue(afterLong > afterShort);
        assertEquals(1, byteCache.size());
    }
}
