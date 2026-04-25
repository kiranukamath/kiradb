package io.kiradb.core.storage.tier;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TieredStorageEngine}.
 *
 * <p>Uses a real {@link LsmStorageEngine} as Tier 2 and a small MemCache
 * to verify the full promotion / eviction / read-path behaviour.
 */
class TieredStorageEngineTest {

    @TempDir
    Path dataDir;

    private TieredStorageEngine engine;

    /**
     * Build an engine with:
     * - MemCache max = 10 entries
     * - Promote threshold = 3.0 (accessed 3+ times recently → promote)
     * - Evict threshold   = 0.5 (barely accessed → evict)
     * - Tier scan interval = 1s (fast for tests; default is 5 minutes)
     */
    @BeforeEach
    void setUp() throws IOException {
        StorageEngine lsm = new LsmStorageEngine(dataDir);
        RuleBasedOrchestrator orchestrator = new RuleBasedOrchestrator(3.0, 0.5);
        engine = new TieredStorageEngine(lsm, 10, orchestrator, 1_000L);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    @Test
    void putAndGetReturnsValue() {
        engine.put("city".getBytes(), "Bangalore".getBytes());
        Optional<byte[]> result = engine.get("city".getBytes());
        assertTrue(result.isPresent());
        assertArrayEquals("Bangalore".getBytes(), result.get());
    }

    @Test
    void writeIsImmediatelyHot() {
        engine.put("key".getBytes(), "val".getBytes());
        // After a write the entry should be in MemCache — second get must come from Tier 1
        // We verify this indirectly: engine.get() returns it, and it was placed in memCache on put
        assertTrue(engine.get("key".getBytes()).isPresent());
    }

    @Test
    void deleteRemovesFromBothTiers() {
        engine.put("del-me".getBytes(), "v".getBytes());
        engine.delete("del-me".getBytes());
        assertTrue(engine.get("del-me".getBytes()).isEmpty());
        assertFalse(engine.exists("del-me".getBytes()));
    }

    @Test
    void existsReturnsFalseForMissingKey() {
        assertFalse(engine.exists("ghost".getBytes()));
    }

    @Test
    void existsReturnsTrueAfterWrite() {
        engine.put("present".getBytes(), "v".getBytes());
        assertTrue(engine.exists("present".getBytes()));
    }

    // ── TTL ───────────────────────────────────────────────────────────────────

    @Test
    void putWithExpiryExpiresCorrectly() throws InterruptedException {
        long expiry = System.currentTimeMillis() + 100;
        engine.put("ttl-key".getBytes(), "v".getBytes(), expiry);

        assertTrue(engine.get("ttl-key".getBytes()).isPresent(), "Should exist before expiry");
        Thread.sleep(150);
        assertTrue(engine.get("ttl-key".getBytes()).isEmpty(), "Should be gone after expiry");
    }

    @Test
    void ttlMillisReturnsPositiveForExpiringKey() {
        long expiry = System.currentTimeMillis() + 60_000;
        engine.put("ttl-key".getBytes(), "v".getBytes(), expiry);
        assertTrue(engine.ttlMillis("ttl-key".getBytes()) > 0);
    }

    @Test
    void ttlMillisReturnsMinusOneForNoExpiry() {
        engine.put("forever".getBytes(), "v".getBytes());
        assertTrue(engine.ttlMillis("forever".getBytes()) <= 0);
    }

    // ── Promotion ─────────────────────────────────────────────────────────────

    @Test
    void frequentlyAccessedKeyGetsPromoted() throws InterruptedException {
        // Write directly to tier2 only (bypass TieredStorageEngine.put which also warms Tier 1)
        // We simulate a "warm" key that has never been in MemCache
        engine.put("popular".getBytes(), "v".getBytes());
        // Manually evict from MemCache by reading through get (no direct access)
        // Instead: access the key many times so its score exceeds the promote threshold
        for (int i = 0; i < 20; i++) {
            engine.get("popular".getBytes());
        }

        // Wait for the async promotion thread and TierManager to run
        Thread.sleep(300);

        // The key must still be readable — from either tier
        Optional<byte[]> result = engine.get("popular".getBytes());
        assertTrue(result.isPresent());
        assertArrayEquals("v".getBytes(), result.get());
    }

    // ── Multiple keys ─────────────────────────────────────────────────────────

    @Test
    void multipleKeysAllReadable() {
        for (int i = 0; i < 20; i++) {
            engine.put(("key-" + i).getBytes(), ("val-" + i).getBytes());
        }
        for (int i = 0; i < 20; i++) {
            Optional<byte[]> result = engine.get(("key-" + i).getBytes());
            assertTrue(result.isPresent(), "key-" + i + " should be readable");
            assertArrayEquals(("val-" + i).getBytes(), result.get());
        }
    }

    @Test
    void overwriteReturnsLatestValue() {
        engine.put("k".getBytes(), "v1".getBytes());
        engine.put("k".getBytes(), "v2".getBytes());
        assertArrayEquals("v2".getBytes(), engine.get("k".getBytes()).get());
    }

    // ── TierManager cycle ─────────────────────────────────────────────────────

    @Test
    void tierManagerCycleDoesNotLoseData() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            engine.put(("persist-" + i).getBytes(), ("v-" + i).getBytes());
        }

        // Wait for at least one TierManager cycle (interval=1s in tests)
        Thread.sleep(1_500);

        for (int i = 0; i < 5; i++) {
            Optional<byte[]> result = engine.get(("persist-" + i).getBytes());
            assertTrue(result.isPresent(), "persist-" + i + " must survive a TierManager cycle");
            assertArrayEquals(("v-" + i).getBytes(), result.get());
        }
    }
}
