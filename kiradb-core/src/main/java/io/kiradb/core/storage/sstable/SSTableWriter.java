package io.kiradb.core.storage.sstable;

import io.kiradb.core.storage.StorageEntry;
import io.kiradb.core.storage.bloom.BloomFilter;
import io.kiradb.core.storage.memtable.MemTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Flushes a {@link MemTable} to an immutable SSTable file on disk.
 *
 * <h2>File format</h2>
 * <pre>
 * DATA SECTION (sorted by key — inherits MemTable's TreeMap order):
 *   per entry:
 *     [4 bytes] keyLen
 *     [keyLen ] key bytes
 *     [4 bytes] valueLen  (-1 = tombstone)
 *     [valueLen] value bytes  (omitted if tombstone)
 *     [8 bytes] expiryMillis
 *
 * INDEX SECTION (sparse — every INDEX_STRIDE-th entry):
 *   [4 bytes] count
 *   per index entry:
 *     [4 bytes] keyLen
 *     [keyLen ] key bytes
 *     [8 bytes] file offset of the data entry
 *
 * BLOOM FILTER SECTION:
 *   [4 bytes] serialized bloom filter length in bytes
 *   [N bytes] serialized bloom filter
 *
 * FOOTER (last 24 bytes — always at a known position from end of file):
 *   [8 bytes] offset of index section
 *   [8 bytes] offset of bloom filter section
 *   [8 bytes] magic = 0x4B495241444220L  ("KIRADB " in ASCII)
 * </pre>
 *
 * <h2>Why a sparse index?</h2>
 * <p>Storing an index entry for every key would double the file size.
 * A sparse index (every 64th key) is tiny in memory, and the worst case
 * is scanning 63 consecutive data entries — still very fast on SSD.
 */
public final class SSTableWriter {

    private static final Logger LOG = LoggerFactory.getLogger(SSTableWriter.class);

    /** Write an index entry every this many data entries. */
    static final int INDEX_STRIDE = 64;

    /** Magic number identifying a valid KiraDB SSTable footer. */
    static final long MAGIC = 0x4B495241444220L;

    /** Tombstone marker in the valueLen field. */
    static final int TOMBSTONE_VALUE_LEN = -1;

    private SSTableWriter() {
        // static utility class
    }

    /**
     * Write a MemTable to a new SSTable file.
     *
     * @param memTable  the MemTable to flush (must not be modified during this call)
     * @param directory directory to write the SSTable file into
     * @param sequence  monotonically increasing sequence number for ordering
     * @return metadata describing the written SSTable
     * @throws IOException if writing fails
     */
    public static SSTableMetadata write(
            final MemTable memTable,
            final Path directory,
            final long sequence) throws IOException {

        if (memTable.isEmpty()) {
            throw new IllegalArgumentException("Cannot write an empty MemTable to an SSTable");
        }

        String fileName = String.format("sstable-%d-%d.sst",
                System.currentTimeMillis(), sequence);
        Path filePath = directory.resolve(fileName);

        LOG.info("Flushing MemTable ({} entries) to {}", memTable.size(), filePath.getFileName());

        BloomFilter bloom = new BloomFilter(memTable.size(), 0.01);
        List<IndexEntry> indexEntries = new ArrayList<>();
        byte[] minKey = null;
        byte[] maxKey = null;

        try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
             DataOutputStream out = new DataOutputStream(fos)) {

            long currentOffset = 0;
            int entryCount = 0;
            Iterator<StorageEntry> it = memTable.iterator();

            // ── DATA SECTION ──────────────────────────────────────────────
            while (it.hasNext()) {
                StorageEntry entry = it.next();

                // Track key range
                if (minKey == null) {
                    minKey = entry.key();
                }
                maxKey = entry.key();

                // Add to bloom filter (tombstones too — they are valid lookup targets)
                bloom.add(entry.key());

                // Sparse index: record file offset every INDEX_STRIDE entries
                if (entryCount % INDEX_STRIDE == 0) {
                    indexEntries.add(new IndexEntry(entry.key(), currentOffset));
                }

                // Write entry
                currentOffset += writeDataEntry(out, entry);
                entryCount++;
            }

            long indexOffset = currentOffset;

            // ── INDEX SECTION ─────────────────────────────────────────────
            out.writeInt(indexEntries.size());
            for (IndexEntry idx : indexEntries) {
                out.writeInt(idx.key().length);
                out.write(idx.key());
                out.writeLong(idx.offset());
            }

            // ── BLOOM FILTER SECTION ──────────────────────────────────────
            long bloomOffset = indexOffset
                    + Integer.BYTES
                    + indexEntries.stream()
                        .mapToLong(e -> Integer.BYTES + e.key().length + Long.BYTES)
                        .sum();

            byte[] bloomBytes = bloom.serialize();
            out.writeInt(bloomBytes.length);
            out.write(bloomBytes);

            // ── FOOTER ────────────────────────────────────────────────────
            out.writeLong(indexOffset);
            out.writeLong(bloomOffset);
            out.writeLong(MAGIC);
            out.flush();
            fos.getFD().sync();

            LOG.info("SSTable written: {} entries, {} bytes", entryCount, filePath.toFile().length());
            return new SSTableMetadata(filePath, minKey, maxKey, entryCount, sequence);
        }
    }

    /**
     * Write one data entry to the stream.
     *
     * @return the number of bytes written
     */
    private static int writeDataEntry(
            final DataOutputStream out, final StorageEntry entry) throws IOException {
        int written = 0;

        out.writeInt(entry.key().length);
        out.write(entry.key());
        written += Integer.BYTES + entry.key().length;

        if (entry.deleted() || entry.value() == null) {
            out.writeInt(TOMBSTONE_VALUE_LEN);
            written += Integer.BYTES;
        } else {
            out.writeInt(entry.value().length);
            out.write(entry.value());
            written += Integer.BYTES + entry.value().length;
        }

        out.writeLong(entry.expiryMillis());
        written += Long.BYTES;

        return written;
    }

    /** Sparse index entry: key + its file offset in the data section. */
    private record IndexEntry(byte[] key, long offset) { }
}
