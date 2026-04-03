package io.kiradb.core.storage;

import io.kiradb.core.storage.memtable.MemTable;
import io.kiradb.core.storage.sstable.SSTableMetadata;
import io.kiradb.core.storage.sstable.SSTableReader;
import io.kiradb.core.storage.sstable.SSTableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SSTableWriter and SSTableReader: write a MemTable to disk, read back all keys,
 * verify tombstones, range checks, and bloom filter behaviour.
 */
class SSTableWriterReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReadBack() throws IOException {
        MemTable memTable = buildMemTable(100);
        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            for (int i = 0; i < 100; i++) {
                byte[] key = ("key-" + i).getBytes();
                Optional<StorageEntry> result = reader.get(key);
                assertTrue(result.isPresent(), "key-" + i + " should be present");
                assertArrayEquals(("value-" + i).getBytes(), result.get().value());
            }
        }
    }

    @Test
    void nonExistentKeyReturnsEmpty() throws IOException {
        MemTable memTable = buildMemTable(10);
        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            Optional<StorageEntry> result = reader.get("not-in-sstable".getBytes());
            assertFalse(result.isPresent(), "Key not written should return empty");
        }
    }

    @Test
    void tombstoneReturnedButNotAlive() throws IOException {
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.live("alive".getBytes(), "yes".getBytes()));
        memTable.put(StorageEntry.tombstone("dead".getBytes()));

        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            // live key
            Optional<StorageEntry> alive = reader.get("alive".getBytes());
            assertTrue(alive.isPresent());
            assertTrue(alive.get().isAlive());

            // tombstone — present in SSTable but not alive
            Optional<StorageEntry> dead = reader.get("dead".getBytes());
            assertTrue(dead.isPresent(), "Tombstone entry should exist in SSTable");
            assertFalse(dead.get().isAlive(), "Tombstone should not be alive");
        }
    }

    @Test
    void metadataMinMaxKey() throws IOException {
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.live("apple".getBytes(), "1".getBytes()));
        memTable.put(StorageEntry.live("mango".getBytes(), "2".getBytes()));
        memTable.put(StorageEntry.live("zebra".getBytes(), "3".getBytes()));

        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        assertArrayEquals("apple".getBytes(), meta.minKey());
        assertArrayEquals("zebra".getBytes(), meta.maxKey());
        assertEquals(3, meta.entryCount());
    }

    @Test
    void keyOutsideRangeReturnedEmpty() throws IOException {
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.live("m".getBytes(), "mid".getBytes()));

        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            // "a" < "m" — outside range
            assertFalse(reader.get("a".getBytes()).isPresent());
            // "z" > "m" — outside range
            assertFalse(reader.get("z".getBytes()).isPresent());
        }
    }

    @Test
    void expiryIsPreserved() throws IOException {
        long expiry = System.currentTimeMillis() + 60_000;
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.withExpiry("session".getBytes(), "tok".getBytes(), expiry));

        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            Optional<StorageEntry> result = reader.get("session".getBytes());
            assertTrue(result.isPresent());
            assertEquals(expiry, result.get().expiryMillis());
            assertTrue(result.get().isAlive());
        }
    }

    @Test
    void expiredEntryIsNotAlive() throws IOException {
        long expiry = System.currentTimeMillis() - 1000; // already expired
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.withExpiry("old".getBytes(), "data".getBytes(), expiry));

        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);

        try (SSTableReader reader = new SSTableReader(meta)) {
            Optional<StorageEntry> result = reader.get("old".getBytes());
            assertTrue(result.isPresent()); // entry is in file
            assertFalse(result.get().isAlive()); // but expired
        }
    }

    @Test
    void writeLargeMemTable() throws IOException {
        // Exercises the sparse index (every 64th entry) and bloom filter
        MemTable memTable = buildMemTable(500);
        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 1L);
        assertNotNull(meta.filePath());
        assertTrue(meta.filePath().toFile().exists());
        assertTrue(meta.filePath().toFile().length() > 0);
        assertEquals(500, meta.entryCount());

        try (SSTableReader reader = new SSTableReader(meta)) {
            // Spot-check: first, middle, last
            assertTrue(reader.get("key-0".getBytes()).isPresent());
            assertTrue(reader.get("key-250".getBytes()).isPresent());
            assertTrue(reader.get("key-499".getBytes()).isPresent());
        }
    }

    @Test
    void sequenceEmbeddedInFilename() throws IOException {
        MemTable memTable = buildMemTable(5);
        SSTableMetadata meta = SSTableWriter.write(memTable, tempDir, 42L);

        assertEquals(42L, meta.sequence());
        assertTrue(meta.filePath().getFileName().toString().contains("-42.sst"),
                "Filename should contain sequence number 42");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MemTable buildMemTable(final int count) {
        MemTable memTable = new MemTable();
        for (int i = 0; i < count; i++) {
            memTable.put(StorageEntry.live(
                    ("key-" + i).getBytes(),
                    ("value-" + i).getBytes()));
        }
        return memTable;
    }
}
