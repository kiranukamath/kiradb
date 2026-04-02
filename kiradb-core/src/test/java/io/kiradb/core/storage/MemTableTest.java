package io.kiradb.core.storage;

import io.kiradb.core.storage.memtable.MemTable;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MemTable put/get/delete and flush trigger.
 */
class MemTableTest {

    @Test
    void putAndGet() {
        MemTable memTable = new MemTable();
        byte[] key = "hello".getBytes();
        byte[] value = "world".getBytes();

        memTable.put(StorageEntry.live(key, value));

        Optional<StorageEntry> result = memTable.get(key);
        assertTrue(result.isPresent());
        assertFalse(result.get().deleted());
        assertEquals("world", new String(result.get().value()));
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        MemTable memTable = new MemTable();
        assertTrue(memTable.get("no-such-key".getBytes()).isEmpty());
    }

    @Test
    void overwriteKey() {
        MemTable memTable = new MemTable();
        byte[] key = "k".getBytes();

        memTable.put(StorageEntry.live(key, "first".getBytes()));
        memTable.put(StorageEntry.live(key, "second".getBytes()));

        Optional<StorageEntry> result = memTable.get(key);
        assertTrue(result.isPresent());
        assertEquals("second", new String(result.get().value()));
        assertEquals(1, memTable.size()); // still only one entry
    }

    @Test
    void tombstoneMarksDeleted() {
        MemTable memTable = new MemTable();
        byte[] key = "deleted".getBytes();

        memTable.put(StorageEntry.live(key, "value".getBytes()));
        memTable.put(StorageEntry.tombstone(key));

        Optional<StorageEntry> result = memTable.get(key);
        assertTrue(result.isPresent()); // entry exists in memtable
        assertTrue(result.get().deleted()); // but it is a tombstone
        assertFalse(result.get().isAlive()); // not alive — should not be returned to client
    }

    @Test
    void entriesAreSortedByKey() {
        MemTable memTable = new MemTable();
        // Insert out of order
        memTable.put(StorageEntry.live("c".getBytes(), "3".getBytes()));
        memTable.put(StorageEntry.live("a".getBytes(), "1".getBytes()));
        memTable.put(StorageEntry.live("b".getBytes(), "2".getBytes()));

        Iterator<StorageEntry> it = memTable.iterator();
        assertEquals("a", new String(it.next().key()));
        assertEquals("b", new String(it.next().key()));
        assertEquals("c", new String(it.next().key()));
        assertFalse(it.hasNext());
    }

    @Test
    void isFullTriggersAtThreshold() {
        // Set a tiny threshold: 10 bytes
        MemTable memTable = new MemTable(10);
        assertFalse(memTable.isFull());

        // A key+value pair that exceeds 10 bytes
        memTable.put(StorageEntry.live("key123".getBytes(), "value123".getBytes()));
        assertTrue(memTable.isFull());
    }

    @Test
    void sizeGrowsWithPuts() {
        MemTable memTable = new MemTable();
        assertEquals(0, memTable.sizeBytes());

        memTable.put(StorageEntry.live("k".getBytes(), "v".getBytes()));
        assertTrue(memTable.sizeBytes() > 0);

        long sizeAfterOne = memTable.sizeBytes();
        memTable.put(StorageEntry.live("k2".getBytes(), "v2".getBytes()));
        assertTrue(memTable.sizeBytes() > sizeAfterOne);
    }

    @Test
    void overwriteShrinksSizeWhenValueSmaller() {
        MemTable memTable = new MemTable();
        byte[] key = "key".getBytes();

        memTable.put(StorageEntry.live(key, "a-very-long-value-string-here".getBytes()));
        long sizeBefore = memTable.sizeBytes();

        memTable.put(StorageEntry.live(key, "x".getBytes())); // much smaller
        assertTrue(memTable.sizeBytes() < sizeBefore);
    }

    @Test
    void scanRangeReturnsLiveEntriesOnly() {
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.live("aa".getBytes(), "1".getBytes()));
        memTable.put(StorageEntry.live("ab".getBytes(), "2".getBytes()));
        memTable.put(StorageEntry.tombstone("ac".getBytes())); // deleted
        memTable.put(StorageEntry.live("ad".getBytes(), "4".getBytes()));

        // Scan all keys from "aa" to "ad" (exclusive end)
        Iterator<StorageEntry> it = memTable.scan("aa".getBytes(), "ad".getBytes());
        int count = 0;
        while (it.hasNext()) {
            StorageEntry e = it.next();
            assertTrue(e.isAlive(), "tombstone should not appear in scan results");
            count++;
        }
        assertEquals(2, count); // "aa" and "ab" (ac is tombstone, ad is excluded)
    }

    @Test
    void scanNullBoundsReturnsAllLive() {
        MemTable memTable = new MemTable();
        memTable.put(StorageEntry.live("a".getBytes(), "1".getBytes()));
        memTable.put(StorageEntry.live("b".getBytes(), "2".getBytes()));
        memTable.put(StorageEntry.tombstone("c".getBytes()));

        Iterator<StorageEntry> it = memTable.scan(null, null);
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(2, count); // "a" and "b"; "c" tombstone excluded
    }

    @Test
    void isEmptyAfterCreation() {
        MemTable memTable = new MemTable();
        assertTrue(memTable.isEmpty());
        assertEquals(0, memTable.size());

        memTable.put(StorageEntry.live("k".getBytes(), "v".getBytes()));
        assertFalse(memTable.isEmpty());
        assertEquals(1, memTable.size());
    }
}
