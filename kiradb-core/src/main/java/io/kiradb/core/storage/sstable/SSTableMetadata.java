package io.kiradb.core.storage.sstable;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Immutable metadata about one SSTable file.
 *
 * <p>Loaded at startup by scanning the data directory and opening each {@code .sst} file.
 * Kept in memory as long as the SSTable is active. Used by {@link io.kiradb.core.storage.lsm.LsmStorageEngine}
 * to quickly skip SSTables that cannot possibly contain a given key (min/max key range check
 * before even loading the Bloom filter).
 *
 * @param filePath   absolute path to the SSTable file on disk
 * @param minKey     smallest key in this SSTable (inclusive)
 * @param maxKey     largest key in this SSTable (inclusive)
 * @param entryCount total number of entries (including tombstones)
 * @param sequence   monotonically increasing sequence number — higher = newer
 */
public record SSTableMetadata(
        Path filePath,
        byte[] minKey,
        byte[] maxKey,
        int entryCount,
        long sequence) {

    /**
     * True if this SSTable's key range could contain the given key.
     * Returns false if the key is outside [minKey, maxKey] — allows caller to skip this file.
     *
     * @param key the key to test
     * @return true if the key is within range
     */
    public boolean mightContainKey(final byte[] key) {
        return Arrays.compare(key, minKey) >= 0 && Arrays.compare(key, maxKey) <= 0;
    }

    @Override
    public String toString() {
        return "SSTableMetadata{file=" + filePath.getFileName()
                + ", entries=" + entryCount
                + ", seq=" + sequence + '}';
    }
}
