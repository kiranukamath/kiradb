package io.kiradb.core.storage.memtable;

import io.kiradb.core.storage.StorageEntry;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory write buffer — the first landing zone for all writes.
 *
 * <h2>Why TreeMap?</h2>
 * <p>A {@link TreeMap} keeps entries sorted by key. When we flush to an SSTable,
 * we need entries in sorted order so the file supports binary search. Using a
 * sorted structure means the flush is a simple sequential scan — O(n) with no
 * extra sorting step.
 *
 * <h2>Size tracking</h2>
 * <p>We track {@code currentSizeBytes} as entries are added. When the threshold
 * is exceeded, {@link LsmStorageEngine} swaps in a new empty MemTable and flushes
 * this one to disk on a virtual thread.
 *
 * <h2>Thread safety</h2>
 * <p>MemTable is NOT thread-safe internally. The caller (LsmStorageEngine) holds
 * a {@code ReadWriteLock}: writers take an exclusive lock, readers a shared lock.
 * This means multiple Netty EventLoop threads can read concurrently, but writes
 * are serialized.
 */
public final class MemTable {

    /** Default flush threshold: 4 MB (small for development, bump to 64 MB in production). */
    public static final long DEFAULT_THRESHOLD_BYTES = 4L * 1024 * 1024;

    /**
     * Byte comparator for keys. Arrays.compare() gives lexicographic ordering —
     * the same ordering used in SSTables, so merging is consistent.
     */
    private static final java.util.Comparator<byte[]> KEY_ORDER = Arrays::compare;

    private final TreeMap<byte[], StorageEntry> data = new TreeMap<>(KEY_ORDER);
    private final long thresholdBytes;
    private long currentSizeBytes;

    /**
     * Create a MemTable with the default flush threshold.
     */
    public MemTable() {
        this(DEFAULT_THRESHOLD_BYTES);
    }

    /**
     * Create a MemTable with a custom flush threshold.
     *
     * @param thresholdBytes flush when size exceeds this many bytes
     */
    public MemTable(final long thresholdBytes) {
        this.thresholdBytes = thresholdBytes;
    }

    /**
     * Insert or overwrite an entry.
     *
     * @param entry the entry to store (may be a tombstone)
     */
    public void put(final StorageEntry entry) {
        StorageEntry existing = data.put(entry.key(), entry);
        // Track size delta: subtract old entry size, add new entry size
        if (existing != null) {
            currentSizeBytes -= entrySize(existing);
        }
        currentSizeBytes += entrySize(entry);
    }

    /**
     * Look up a key. Returns the entry (which may be a tombstone) or empty if absent.
     *
     * <p>Callers must check {@link StorageEntry#isAlive()} — a tombstone means
     * the key was deleted and should not be returned to the client.
     *
     * @param key the key to look up
     * @return the entry, or empty if not in this MemTable
     */
    public Optional<StorageEntry> get(final byte[] key) {
        return Optional.ofNullable(data.get(key));
    }

    /**
     * True when the MemTable has reached its flush threshold.
     *
     * @return true if this MemTable should be flushed to disk
     */
    public boolean isFull() {
        return currentSizeBytes >= thresholdBytes;
    }

    /**
     * Current size in bytes (approximate — key + value lengths only).
     *
     * @return current size in bytes
     */
    public long sizeBytes() {
        return currentSizeBytes;
    }

    /**
     * Number of entries (including tombstones).
     *
     * @return entry count
     */
    public int size() {
        return data.size();
    }

    /**
     * True if this MemTable has no entries.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Iterate all entries in sorted key order.
     * Used by SSTableWriter when flushing to disk.
     *
     * @return iterator over all entries, sorted by key
     */
    public Iterator<StorageEntry> iterator() {
        return data.values().iterator();
    }

    /**
     * Scan live entries in {@code [startKey, endKey)} range (both nullable).
     *
     * @param startKey inclusive lower bound; null = from beginning
     * @param endKey   exclusive upper bound; null = to end
     * @return iterator over matching live entries, sorted by key
     */
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
        Map<byte[], StorageEntry> range;
        if (startKey == null && endKey == null) {
            range = data;
        } else if (startKey == null) {
            range = data.headMap(endKey);
        } else if (endKey == null) {
            range = data.tailMap(startKey);
        } else {
            range = data.subMap(startKey, endKey);
        }
        return range.values().stream()
                .filter(StorageEntry::isAlive)
                .iterator();
    }

    /**
     * Approximate byte size of one entry: key bytes + value bytes + metadata overhead.
     */
    private static long entrySize(final StorageEntry entry) {
        long size = entry.key().length;
        if (entry.value() != null) {
            size += entry.value().length;
        }
        size += Long.BYTES * 2; // expiryMillis + overhead estimate
        return size;
    }
}
