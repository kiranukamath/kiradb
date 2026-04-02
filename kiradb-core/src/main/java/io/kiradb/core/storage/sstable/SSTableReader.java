package io.kiradb.core.storage.sstable;

import io.kiradb.core.storage.StorageEntry;
import io.kiradb.core.storage.bloom.BloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Reads entries from an immutable SSTable file.
 *
 * <h2>Lookup strategy</h2>
 * <ol>
 *   <li>Read the footer (last 24 bytes) to locate the bloom filter and index.</li>
 *   <li>Check the Bloom filter — if "definitely absent", return empty immediately.</li>
 *   <li>Binary search the sparse index to find the nearest data offset.</li>
 *   <li>Sequential scan from that offset to find the exact key.</li>
 * </ol>
 *
 * <h2>Why RandomAccessFile?</h2>
 * <p>{@link RandomAccessFile} lets us seek to arbitrary byte offsets. We use it to:
 * <ul>
 *   <li>Jump to the footer (seek to file length - 24)</li>
 *   <li>Jump to the index section (seek to indexOffset)</li>
 *   <li>Jump to a data entry (seek to the offset from the sparse index)</li>
 * </ul>
 * Without random access, we'd have to read the entire file linearly every time.
 */
public final class SSTableReader implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SSTableReader.class);

    private static final int FOOTER_SIZE = 3 * Long.BYTES; // indexOffset + bloomOffset + magic

    private final SSTableMetadata metadata;
    private final RandomAccessFile raf;
    private final BloomFilter bloomFilter;
    private final List<IndexEntry> sparseIndex;
    private final long indexSectionStart;

    /**
     * Open an SSTable file for reading. Loads the footer, bloom filter, and
     * sparse index into memory. The data section is read on demand.
     *
     * @param metadata metadata for this SSTable (path, sequence, etc.)
     * @throws IOException if the file cannot be read or is corrupt
     */
    public SSTableReader(final SSTableMetadata metadata) throws IOException {
        this.metadata = metadata;
        this.raf = new RandomAccessFile(metadata.filePath().toFile(), "r");

        long fileLength = raf.length();
        if (fileLength < FOOTER_SIZE) {
            throw new IOException("SSTable file too small to contain a valid footer: "
                    + metadata.filePath());
        }

        // ── Read footer ───────────────────────────────────────────────────
        raf.seek(fileLength - FOOTER_SIZE);
        long indexOffset = raf.readLong();
        long bloomOffset = raf.readLong();
        long magic = raf.readLong();

        if (magic != SSTableWriter.MAGIC) {
            throw new IOException("Invalid SSTable magic number in: " + metadata.filePath());
        }

        // ── Load bloom filter ─────────────────────────────────────────────
        raf.seek(bloomOffset);
        int bloomLen = raf.readInt();
        byte[] bloomBytes = new byte[bloomLen];
        raf.readFully(bloomBytes);
        this.bloomFilter = new BloomFilter(bloomBytes);

        // ── Load sparse index ─────────────────────────────────────────────
        this.indexSectionStart = indexOffset;
        raf.seek(indexOffset);
        int indexCount = raf.readInt();
        this.sparseIndex = new ArrayList<>(indexCount);
        for (int i = 0; i < indexCount; i++) {
            int keyLen = raf.readInt();
            byte[] key = new byte[keyLen];
            raf.readFully(key);
            long offset = raf.readLong();
            sparseIndex.add(new IndexEntry(key, offset));
        }

        LOG.debug("Opened SSTable: {} ({} index entries)", metadata, sparseIndex.size());
    }

    /**
     * Look up a key. Returns the entry (which may be a tombstone) or empty.
     *
     * <p>Callers should check {@link StorageEntry#isAlive()} — a tombstone means
     * the key was deleted and overrides any older value in an older SSTable.
     *
     * @param key the key to look up
     * @return the entry if found, or empty if this SSTable does not contain the key
     * @throws IOException if a disk read fails
     */
    public Optional<StorageEntry> get(final byte[] key) throws IOException {
        // Fast path 1: key range check
        if (!metadata.mightContainKey(key)) {
            return Optional.empty();
        }

        // Fast path 2: bloom filter
        if (!bloomFilter.mightContain(key)) {
            return Optional.empty();
        }

        // Find the nearest index entry via binary search
        long startOffset = findStartOffset(key);

        // Sequential scan from that offset
        raf.seek(startOffset);
        while (raf.getFilePointer() < indexSectionStart) {
            StorageEntry entry = readDataEntry();
            int cmp = Arrays.compare(key, entry.key());
            if (cmp == 0) {
                return Optional.of(entry);
            }
            if (cmp < 0) {
                // Keys are sorted — we've passed the target key without finding it
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Scan all live entries with keys in {@code [startKey, endKey)}.
     *
     * @param startKey inclusive lower bound; null = from first key
     * @param endKey   exclusive upper bound; null = to last key
     * @return iterator over matching live entries in key order
     * @throws IOException if a disk read fails
     */
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey)
            throws IOException {

        // Quick range check
        if (startKey != null && Arrays.compare(startKey, metadata.maxKey()) > 0) {
            return Collections.emptyIterator();
        }
        if (endKey != null && Arrays.compare(endKey, metadata.minKey()) <= 0) {
            return Collections.emptyIterator();
        }

        long startOffset = startKey == null ? 0L : findStartOffset(startKey);
        raf.seek(startOffset);

        List<StorageEntry> results = new ArrayList<>();
        while (raf.getFilePointer() < indexSectionStart) {
            StorageEntry entry = readDataEntry();
            if (startKey != null && Arrays.compare(entry.key(), startKey) < 0) {
                continue;
            }
            if (endKey != null && Arrays.compare(entry.key(), endKey) >= 0) {
                break;
            }
            if (entry.isAlive()) {
                results.add(entry);
            }
        }
        return results.iterator();
    }

    /**
     * SSTable metadata (file path, key range, sequence number).
     *
     * @return this SSTable's metadata
     */
    public SSTableMetadata metadata() {
        return metadata;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Binary search the sparse index for the largest key ≤ the target.
     * Returns the file offset to start scanning from.
     */
    private long findStartOffset(final byte[] targetKey) {
        if (sparseIndex.isEmpty()) {
            return 0L;
        }

        int lo = 0;
        int hi = sparseIndex.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            int cmp = Arrays.compare(sparseIndex.get(mid).key(), targetKey);
            if (cmp <= 0) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        // lo is the last index entry whose key is ≤ targetKey
        int cmp = Arrays.compare(sparseIndex.get(lo).key(), targetKey);
        if (cmp > 0) {
            return 0L; // target is before first index entry — scan from start
        }
        return sparseIndex.get(lo).offset();
    }

    /**
     * Read one data entry from the current RandomAccessFile position.
     */
    private StorageEntry readDataEntry() throws IOException {
        int keyLen = raf.readInt();
        byte[] key = new byte[keyLen];
        raf.readFully(key);

        int valueLen = raf.readInt();
        byte[] value = null;
        boolean deleted = false;

        if (valueLen == SSTableWriter.TOMBSTONE_VALUE_LEN) {
            deleted = true;
        } else if (valueLen > 0) {
            value = new byte[valueLen];
            raf.readFully(value);
        }

        long expiryMillis = raf.readLong();

        return new StorageEntry(key, value, expiryMillis, deleted);
    }

    /** Sparse index entry: key + byte offset in the data section. */
    private record IndexEntry(byte[] key, long offset) { }
}
