package io.kiradb.core.storage;

import io.kiradb.core.storage.wal.Wal;
import io.kiradb.core.storage.wal.WalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for WAL write and crash-recovery replay.
 */
class WalTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReplay() throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        // Write 100 entries
        try (Wal wal = new Wal(walPath)) {
            for (int i = 0; i < 100; i++) {
                byte[] key = ("key-" + i).getBytes();
                byte[] value = ("value-" + i).getBytes();
                wal.append(new WalEntry(WalEntry.Op.PUT,
                        System.currentTimeMillis(), -1L, key, value, 0));
            }
        }

        // Replay — simulates crash recovery
        List<WalEntry> entries = Wal.replay(walPath);
        assertEquals(100, entries.size());
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(("key-" + i).getBytes(), entries.get(i).key());
            assertArrayEquals(("value-" + i).getBytes(), entries.get(i).value());
            assertEquals(WalEntry.Op.PUT, entries.get(i).op());
        }
    }

    @Test
    void replayWithDeletes() throws IOException {
        Path walPath = tempDir.resolve("deletes.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append(new WalEntry(WalEntry.Op.PUT,
                    System.currentTimeMillis(), -1L,
                    "hello".getBytes(), "world".getBytes(), 0));
            wal.append(new WalEntry(WalEntry.Op.DELETE,
                    System.currentTimeMillis(), -1L,
                    "hello".getBytes(), null, 0));
        }

        List<WalEntry> entries = Wal.replay(walPath);
        assertEquals(2, entries.size());
        assertEquals(WalEntry.Op.PUT, entries.get(0).op());
        assertEquals(WalEntry.Op.DELETE, entries.get(1).op());
    }

    @Test
    void replayEmptyFile() throws IOException {
        Path walPath = tempDir.resolve("empty.wal");
        // File does not exist yet
        List<WalEntry> entries = Wal.replay(walPath);
        assertEquals(0, entries.size());
    }

    @Test
    void appendAfterReplay() throws IOException {
        Path walPath = tempDir.resolve("append.wal");

        // First session — write 5 entries
        try (Wal wal = new Wal(walPath)) {
            for (int i = 0; i < 5; i++) {
                wal.append(new WalEntry(WalEntry.Op.PUT,
                        System.currentTimeMillis(), -1L,
                        ("k" + i).getBytes(), ("v" + i).getBytes(), 0));
            }
        }

        // Second session — append 3 more (simulates restart + continue)
        try (Wal wal = new Wal(walPath)) {
            for (int i = 5; i < 8; i++) {
                wal.append(new WalEntry(WalEntry.Op.PUT,
                        System.currentTimeMillis(), -1L,
                        ("k" + i).getBytes(), ("v" + i).getBytes(), 0));
            }
        }

        List<WalEntry> entries = Wal.replay(walPath);
        assertEquals(8, entries.size());
    }

    @Test
    void replayWithExpiry() throws IOException {
        Path walPath = tempDir.resolve("expiry.wal");
        long expiry = System.currentTimeMillis() + 60_000;

        try (Wal wal = new Wal(walPath)) {
            wal.append(new WalEntry(WalEntry.Op.PUT,
                    System.currentTimeMillis(), expiry,
                    "session".getBytes(), "abc".getBytes(), 0));
        }

        List<WalEntry> entries = Wal.replay(walPath);
        assertEquals(1, entries.size());
        assertEquals(expiry, entries.get(0).expiryMillis());
    }
}
