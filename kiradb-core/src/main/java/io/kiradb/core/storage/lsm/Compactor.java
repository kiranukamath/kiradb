package io.kiradb.core.storage.lsm;

import io.kiradb.core.storage.StorageEntry;
import io.kiradb.core.storage.memtable.MemTable;
import io.kiradb.core.storage.sstable.SSTableMetadata;
import io.kiradb.core.storage.sstable.SSTableReader;
import io.kiradb.core.storage.sstable.SSTableWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background compactor — merges old SSTables into one, removing stale entries.
 *
 * <h2>Why compaction is necessary</h2>
 * <p>Every MemTable flush creates a new SSTable. Without compaction, reads
 * must check every SSTable in order — slow. Deleted keys (tombstones) also
 * accumulate forever and waste disk space.
 *
 * <p>Compaction solves both: it merges N old SSTables into one, keeping only
 * the most recent value per key and discarding tombstones for keys that no
 * longer exist in newer SSTables.
 *
 * <h2>N-way merge</h2>
 * <p>Compaction is an N-way merge sort. All SSTables are already sorted by key.
 * We use a {@link PriorityQueue} (min-heap) seeded with the first entry from each
 * SSTable. We repeatedly pull the smallest key, skip older duplicates, and write
 * it to the output SSTable.
 *
 * <h2>Virtual thread</h2>
 * <p>Runs on a virtual thread — it does blocking disk I/O, which would be
 * catastrophic on a Netty EventLoop thread but is fine on a virtual thread
 * (the JVM parks it while waiting for the OS).
 */
final class Compactor implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Compactor.class);

    /** Trigger compaction when there are more than this many SSTables. */
    static final int COMPACTION_THRESHOLD = 4;

    /** How long to sleep between compaction checks (ms). */
    private static final long SLEEP_MS = 30_000;

    private final LsmStorageEngine engine;
    private final Path dataDir;
    private final AtomicLong sequenceCounter;
    private volatile boolean running = true;

    Compactor(final LsmStorageEngine engine, final Path dataDir,
              final AtomicLong sequenceCounter) {
        this.engine = engine;
        this.dataDir = dataDir;
        this.sequenceCounter = sequenceCounter;
    }

    /** Signal this compactor to stop after the current sleep/compaction cycle. */
    void stop() {
        running = false;
    }

    @Override
    public void run() {
        LOG.info("Compactor started");
        while (running) {
            try {
                Thread.sleep(SLEEP_MS);
                if (running) {
                    maybeCompact();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Compaction error — will retry next cycle", e);
            }
        }
        LOG.info("Compactor stopped");
    }

    /**
     * Compact if the number of SSTables exceeds the threshold.
     * Called on the compactor's own virtual thread — safe to do blocking I/O.
     */
    void maybeCompact() {
        List<SSTableReader> toCompact = engine.getSstablesForCompaction(COMPACTION_THRESHOLD);
        if (toCompact.isEmpty()) {
            return;
        }

        LOG.info("Starting compaction of {} SSTables", toCompact.size());
        try {
            SSTableMetadata result = compact(toCompact);
            SSTableReader newReader = new SSTableReader(result);
            engine.replaceCompactedSstables(toCompact, newReader);
            deleteFiles(toCompact);
            LOG.info("Compaction complete — produced {}", result);
        } catch (IOException e) {
            LOG.error("Compaction failed", e);
        }
    }

    /**
     * Merge the given SSTables into one new SSTable using an N-way merge.
     *
     * <p>The SSTables are ordered newest-first. For duplicate keys, we keep
     * the value from the newest SSTable and discard older ones.
     */
    private SSTableMetadata compact(final List<SSTableReader> readers) throws IOException {
        // Min-heap ordered by key, then by sequence (newer wins on ties)
        PriorityQueue<HeapEntry> heap = new PriorityQueue<>(
                Comparator.<HeapEntry, byte[]>comparing(
                        e -> e.entry().key(), Arrays::compare)
                .thenComparingLong(e -> -e.sequence())); // higher seq = newer

        // Seed the heap with the first entry from each reader
        List<Iterator<StorageEntry>> iterators = new ArrayList<>(readers.size());
        for (SSTableReader reader : readers) {
            Iterator<StorageEntry> it = reader.scan(null, null);
            iterators.add(it);
            if (it.hasNext()) {
                heap.offer(new HeapEntry(it.next(), readers.indexOf(reader),
                        reader.metadata().sequence(), it));
            }
        }

        // Write merged output to a temporary MemTable, then flush as a new SSTable
        // We use MemTable here to reuse SSTableWriter's flush logic.
        // For huge compactions this could be streamed directly — acceptable for Phase 3.
        MemTable merged = new MemTable(Long.MAX_VALUE); // no size limit during compaction

        byte[] lastKey = null;
        while (!heap.isEmpty()) {
            HeapEntry current = heap.poll();
            StorageEntry entry = current.entry();

            // Skip duplicate keys — we already wrote the newest version
            if (lastKey != null && Arrays.equals(lastKey, entry.key())) {
                advance(heap, current);
                continue;
            }

            // Skip expired entries and tombstones that no longer need to be preserved
            // (tombstones in the oldest layer can be dropped — there's nothing older to suppress)
            if (!entry.isExpired() && !(entry.deleted() && isOldestLayer(current, readers))) {
                merged.put(entry);
            }

            lastKey = entry.key();
            advance(heap, current);
        }

        if (merged.isEmpty()) {
            LOG.warn("Compaction produced an empty SSTable — skipping write");
            // Return a dummy metadata; caller should handle empty case
            throw new IOException("Compaction produced empty result");
        }

        return SSTableWriter.write(merged, dataDir, sequenceCounter.incrementAndGet());
    }

    /** Advance the iterator for a just-consumed heap entry and re-insert the next. */
    private static void advance(
            final PriorityQueue<HeapEntry> heap, final HeapEntry consumed) {
        if (consumed.iterator().hasNext()) {
            heap.offer(new HeapEntry(
                    consumed.iterator().next(),
                    consumed.readerIndex(),
                    consumed.sequence(),
                    consumed.iterator()));
        }
    }

    /**
     * A tombstone can be safely dropped only if this is the oldest SSTable in
     * the compaction set — i.e., there's no older SSTable that could have a
     * live value for this key.
     */
    private static boolean isOldestLayer(
            final HeapEntry entry, final List<SSTableReader> readers) {
        long minSeq = readers.stream()
                .mapToLong(r -> r.metadata().sequence())
                .min().orElse(Long.MAX_VALUE);
        return entry.sequence() == minSeq;
    }

    private static void deleteFiles(final List<SSTableReader> readers) {
        for (SSTableReader reader : readers) {
            try {
                reader.close();
                Files.deleteIfExists(reader.metadata().filePath());
                LOG.debug("Deleted compacted SSTable: {}", reader.metadata().filePath().getFileName());
            } catch (IOException e) {
                LOG.warn("Failed to delete compacted SSTable: {}", reader.metadata().filePath(), e);
            }
        }
    }

    /** Entry in the merge heap: one entry from one SSTable's iterator. */
    private record HeapEntry(
            StorageEntry entry,
            int readerIndex,
            long sequence,
            Iterator<StorageEntry> iterator) { }
}
