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

    @Test
    void clearEmptiesCache() {
        cache.put("a".getBytes(), "1".getBytes(), -1L);
        cache.put("b".getBytes(), "2".getBytes(), -1L);
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.get("a".getBytes()).isEmpty());
    }
}
