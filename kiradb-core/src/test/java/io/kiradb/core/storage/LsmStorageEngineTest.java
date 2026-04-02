package io.kiradb.core.storage;

import io.kiradb.core.storage.lsm.LsmStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for LsmStorageEngine — covers crash recovery (WAL replay) and compaction.
 *
 * <p>These are the most important tests in Phase 3. They verify the two core
 * durability guarantees:
 * <ol>
 *   <li>No data loss on crash (WAL replay restores MemTable state)</li>
 *   <li>Compaction merges SSTables without losing or corrupting data</li>
 * </ol>
 */
class LsmStorageEngineTest {

    @TempDir
    Path dataDir;

    // ── Basic CRUD ────────────────────────────────────────────────────────────

    @Test
    void putAndGet() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("hello".getBytes(), "world".getBytes());
            Optional<byte[]> result = engine.get("hello".getBytes());
            assertTrue(result.isPresent());
            assertEquals("world", new String(result.get()));
        }
    }

    @Test
    void getMissingKeyReturnsEmpty() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertFalse(engine.get("no-such-key".getBytes()).isPresent());
        }
    }

    @Test
    void deleteRemovesKey() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("to-delete".getBytes(), "value".getBytes());
            assertTrue(engine.get("to-delete".getBytes()).isPresent());

            engine.delete("to-delete".getBytes());
            assertFalse(engine.get("to-delete".getBytes()).isPresent());
        }
    }

    @Test
    void existsReflectsState() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertFalse(engine.exists("x".getBytes()));
            engine.put("x".getBytes(), "v".getBytes());
            assertTrue(engine.exists("x".getBytes()));
            engine.delete("x".getBytes());
            assertFalse(engine.exists("x".getBytes()));
        }
    }

    @Test
    void ttlNoExpiry() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("k".getBytes(), "v".getBytes());
            assertEquals(-1L, engine.ttlMillis("k".getBytes()));
        }
    }

    @Test
    void ttlMissingKey() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertEquals(-2L, engine.ttlMillis("missing".getBytes()));
        }
    }

    @Test
    void putWithExpiry() throws IOException, InterruptedException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            long expiry = System.currentTimeMillis() + 200;
            engine.put("expiring".getBytes(), "soon".getBytes(), expiry);

            assertTrue(engine.get("expiring".getBytes()).isPresent());
            Thread.sleep(300);
            assertFalse(engine.get("expiring".getBytes()).isPresent());
        }
    }

    @Test
    void expire() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("key".getBytes(), "value".getBytes());
            long futureExpiry = System.currentTimeMillis() + 60_000;

            assertTrue(engine.expire("key".getBytes(), futureExpiry));
            long ttl = engine.ttlMillis("key".getBytes());
            assertTrue(ttl > 0 && ttl <= 60_000);
        }
    }

    @Test
    void expireOnMissingKeyReturnsFalse() throws IOException {
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertFalse(engine.expire("no-key".getBytes(), System.currentTimeMillis() + 1000));
        }
    }

    // ── Crash recovery (WAL replay) ───────────────────────────────────────────

    @Test
    void crashRecovery() throws IOException {
        // Session 1: write 1000 keys then close (flushes to SSTable + WAL exists)
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            for (int i = 0; i < 1000; i++) {
                engine.put(("key-" + i).getBytes(), ("value-" + i).getBytes());
            }
        }

        // Session 2: restart — SSTables loaded + WAL replayed, all keys must be accessible
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            for (int i = 0; i < 1000; i++) {
                Optional<byte[]> result = engine.get(("key-" + i).getBytes());
                assertTrue(result.isPresent(), "key-" + i + " missing after restart");
                assertEquals("value-" + i, new String(result.get()));
            }
        }
    }

    @Test
    void walReplayRespectsTombstones() throws IOException {
        // Write key, delete it, restart — key must still be gone
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("ghost".getBytes(), "boo".getBytes());
            engine.delete("ghost".getBytes());
        }

        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertFalse(engine.get("ghost".getBytes()).isPresent(),
                    "Deleted key should stay deleted after WAL replay");
        }
    }

    @Test
    void walReplayRespectsExpiry() throws IOException {
        long expiry = System.currentTimeMillis() + 60_000;
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("session".getBytes(), "token".getBytes(), expiry);
        }

        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            Optional<byte[]> result = engine.get("session".getBytes());
            assertTrue(result.isPresent(), "Not-yet-expired key should survive restart");
            assertEquals("token", new String(result.get()));
        }
    }

    @Test
    void walReplayDropsExpiredEntries() throws IOException, InterruptedException {
        long expiry = System.currentTimeMillis() + 100; // expires very soon
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("short-lived".getBytes(), "data".getBytes(), expiry);
        }

        Thread.sleep(300); // let it expire

        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            // Entry is in WAL but expired by now — get() should return empty
            assertFalse(engine.get("short-lived".getBytes()).isPresent(),
                    "Expired key should not be returned after restart");
        }
    }

    // ── Compaction ────────────────────────────────────────────────────────────

    @Test
    void compactionPreservesData() throws IOException {
        // Write 5 separate batches — each close() flushes MemTable → SSTable
        for (int batch = 0; batch < 5; batch++) {
            try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
                for (int i = 0; i < 50; i++) {
                    String key = "batch-" + batch + "-key-" + i;
                    String value = "batch-" + batch + "-value-" + i;
                    engine.put(key.getBytes(), value.getBytes());
                }
            }
        }

        // Verify all 250 keys are intact after compaction may have merged files
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            for (int batch = 0; batch < 5; batch++) {
                for (int i = 0; i < 50; i++) {
                    String key = "batch-" + batch + "-key-" + i;
                    Optional<byte[]> result = engine.get(key.getBytes());
                    assertTrue(result.isPresent(), key + " missing after compaction");
                    assertEquals("batch-" + batch + "-value-" + i, new String(result.get()));
                }
            }
        }
    }

    @Test
    void compactionDropsTombstones() throws IOException {
        // Write a key then delete it across two SSTable generations
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("to-compact".getBytes(), "value".getBytes());
        }
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.delete("to-compact".getBytes());
        }

        // After restart, key must be gone regardless of compaction state
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            assertFalse(engine.get("to-compact".getBytes()).isPresent());
        }
    }

    @Test
    void overwriteAcrossSstables() throws IOException {
        // Write key in session 1, overwrite in session 2 — must read latest value
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("versioned".getBytes(), "v1".getBytes());
        }
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            engine.put("versioned".getBytes(), "v2".getBytes());
        }
        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            Optional<byte[]> result = engine.get("versioned".getBytes());
            assertTrue(result.isPresent());
            assertEquals("v2", new String(result.get()),
                    "Newest write must win across SSTable boundaries");
        }
    }

    @Test
    void manyKeysAcrossMultipleRestarts() throws IOException {
        // Write across 3 sessions, verify all 300 keys accessible in session 4
        for (int session = 0; session < 3; session++) {
            try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
                for (int i = session * 100; i < (session + 1) * 100; i++) {
                    engine.put(("multi-" + i).getBytes(), ("val-" + i).getBytes());
                }
            }
        }

        try (StorageEngine engine = new LsmStorageEngine(dataDir)) {
            for (int i = 0; i < 300; i++) {
                Optional<byte[]> result = engine.get(("multi-" + i).getBytes());
                assertTrue(result.isPresent(), "multi-" + i + " missing");
                assertEquals("val-" + i, new String(result.get()));
            }
        }
    }
}
