package io.kiradb.core.storage.lsm;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.core.storage.memtable.MemTable;
import io.kiradb.core.storage.sstable.SSTableMetadata;
import io.kiradb.core.storage.sstable.SSTableReader;
import io.kiradb.core.storage.sstable.SSTableWriter;
import io.kiradb.core.storage.wal.Wal;
import io.kiradb.core.storage.wal.WalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * LSM Tree storage engine — the Phase 3 persistent implementation of {@link StorageEngine}.
 *
 * <h2>Write path</h2>
 * <ol>
 *   <li>Acquire write lock</li>
 *   <li>Append to WAL (durable before ack)</li>
 *   <li>Insert into active MemTable</li>
 *   <li>If MemTable is full → swap in a new one, flush old one to SSTable on a virtual thread</li>
 *   <li>Release write lock</li>
 * </ol>
 *
 * <h2>Read path</h2>
 * <ol>
 *   <li>Acquire read lock (shared — concurrent reads allowed)</li>
 *   <li>Check active MemTable</li>
 *   <li>Check flushing MemTable (if a flush is in progress)</li>
 *   <li>Check SSTables newest-first: bloom filter → sparse index → scan</li>
 *   <li>Return first non-tombstone result; tombstone = deleted</li>
 * </ol>
 *
 * <h2>Crash recovery</h2>
 * <p>On startup, the WAL is replayed to reconstruct the MemTable for any writes
 * that were not yet flushed to an SSTable.
 *
 * <h2>Thread safety</h2>
 * <p>A {@link ReentrantReadWriteLock} guards all state. Netty EventLoop threads
 * take a read lock (concurrent reads). Writes are exclusive. The MemTable flush
 * runs on a virtual thread but holds the write lock only for the brief swap of
 * the active MemTable — the actual disk write happens outside the lock.
 */
public final class LsmStorageEngine implements StorageEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LsmStorageEngine.class);

    private static final String WAL_FILENAME = "kiradb.wal";

    private final Path dataDir;
    private final Wal wal;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final Compactor compactor;
    private final Thread compactorThread;

    /** The MemTable currently accepting writes. */
    private MemTable activeMemTable;

    /**
     * The MemTable currently being flushed to an SSTable on a virtual thread.
     * Non-null only during a flush. Reads must check this too.
     */
    private volatile MemTable flushingMemTable;

    /**
     * All on-disk SSTables, newest-first.
     * Guarded by the write lock for mutation; read lock for iteration.
     */
    private final List<SSTableReader> sstables = new ArrayList<>();

    /**
     * Open (or create) the LSM storage engine at the given data directory.
     * Replays the WAL and loads existing SSTables on startup.
     *
     * @param dataDir directory for WAL and SSTable files
     * @throws IOException if the directory cannot be created or files cannot be read
     */
    public LsmStorageEngine(final Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);

        // Load existing SSTables (sorted newest-first by sequence number embedded in filename)
        loadExistingSstables();

        // Replay WAL → reconstruct MemTable for writes not yet in an SSTable
        this.activeMemTable = replayWal();

        // Open WAL in append mode (existing entries stay, new ones are appended)
        this.wal = new Wal(dataDir.resolve(WAL_FILENAME));

        // Start background compactor on a virtual thread
        this.compactor = new Compactor(this, dataDir, sequenceCounter);
        this.compactorThread = Thread.ofVirtual()
                .name("kiradb-compactor")
                .start(compactor);

        LOG.info("LsmStorageEngine ready — dataDir={}, sstables={}, memtable entries={}",
                dataDir, sstables.size(), activeMemTable.size());
    }

    // ── StorageEngine implementation ──────────────────────────────────────────

    @Override
    public void put(final byte[] key, final byte[] value) {
        put(key, value, -1L);
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        StorageEntry entry = expiryMillis > 0
                ? StorageEntry.withExpiry(key, value, expiryMillis)
                : StorageEntry.live(key, value);

        lock.writeLock().lock();
        try {
            wal.append(new WalEntry(WalEntry.Op.PUT,
                    System.currentTimeMillis(), expiryMillis, key, value, 0));
            activeMemTable.put(entry);
            maybeScheduleFlush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<byte[]> get(final byte[] key) {
        lock.readLock().lock();
        try {
            // 1. Active MemTable
            Optional<StorageEntry> found = activeMemTable.get(key);
            if (found.isPresent()) {
                return toValue(found.get());
            }

            // 2. Flushing MemTable (if a flush is in progress)
            MemTable flushing = flushingMemTable;
            if (flushing != null) {
                found = flushing.get(key);
                if (found.isPresent()) {
                    return toValue(found.get());
                }
            }

            // 3. SSTables newest-first
            for (SSTableReader sstable : sstables) {
                try {
                    Optional<StorageEntry> result = sstable.get(key);
                    if (result.isPresent()) {
                        return toValue(result.get());
                    }
                } catch (IOException e) {
                    LOG.error("SSTable read error for key, skipping: {}", sstable.metadata(), e);
                }
            }

            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(final byte[] key) {
        boolean existed = exists(key);

        lock.writeLock().lock();
        try {
            wal.append(new WalEntry(WalEntry.Op.DELETE,
                    System.currentTimeMillis(), -1L, key, null, 0));
            activeMemTable.put(StorageEntry.tombstone(key));
        } finally {
            lock.writeLock().unlock();
        }

        return existed;
    }

    @Override
    public boolean exists(final byte[] key) {
        return get(key).isPresent();
    }

    @Override
    public long ttlMillis(final byte[] key) {
        lock.readLock().lock();
        try {
            StorageEntry entry = findEntry(key);
            if (entry == null || !entry.isAlive()) {
                return -2L;
            }
            if (entry.expiryMillis() < 0) {
                return -1L;
            }
            return entry.expiryMillis() - System.currentTimeMillis();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean expire(final byte[] key, final long expiryMillis) {
        lock.writeLock().lock();
        try {
            // Check the key exists
            StorageEntry existing = findEntry(key);
            if (existing == null || !existing.isAlive()) {
                return false;
            }
            // Re-write with new expiry
            StorageEntry updated = StorageEntry.withExpiry(key, existing.value(), expiryMillis);
            wal.append(new WalEntry(WalEntry.Op.PUT,
                    System.currentTimeMillis(), expiryMillis, key, existing.value(), 0));
            activeMemTable.put(updated);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
        lock.readLock().lock();
        try {
            // Merge results from MemTable and all SSTables, deduplicating by key
            // For simplicity in Phase 3: collect from MemTable only
            // (full merge-scan across SSTables is added in Phase 4)
            return activeMemTable.scan(startKey, endKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        LOG.info("LsmStorageEngine closing...");
        compactor.stop();
        compactorThread.interrupt();

        lock.writeLock().lock();
        try {
            // Flush active MemTable to disk before shutdown
            if (!activeMemTable.isEmpty()) {
                flushMemTableSync(activeMemTable);
            }
            try {
                wal.close();
            } catch (IOException e) {
                LOG.error("WAL close error", e);
            }
            for (SSTableReader reader : sstables) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOG.warn("SSTableReader close error", e);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        LOG.info("LsmStorageEngine closed.");
    }

    // ── package-private hooks for Compactor ───────────────────────────────────

    /**
     * Return the oldest SSTables to compact if count exceeds the threshold.
     * Returns an empty list if no compaction is needed.
     */
    List<SSTableReader> getSstablesForCompaction(final int threshold) {
        lock.readLock().lock();
        try {
            if (sstables.size() <= threshold) {
                return Collections.emptyList();
            }
            // Return all but the newest SSTable (oldest-first for compaction)
            List<SSTableReader> sorted = new ArrayList<>(sstables);
            sorted.sort(Comparator.comparingLong(r -> r.metadata().sequence()));
            return sorted.subList(0, sorted.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Replace compacted SSTables with the new merged SSTable.
     * Called by the Compactor after writing the new file.
     */
    void replaceCompactedSstables(
            final List<SSTableReader> oldReaders, final SSTableReader newReader) {
        lock.writeLock().lock();
        try {
            sstables.removeAll(oldReaders);
            // Insert at the correct position (newest-first)
            sstables.add(0, newReader);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Return a value from a StorageEntry, or empty if it is a tombstone or expired.
     */
    private static Optional<byte[]> toValue(final StorageEntry entry) {
        if (entry.isAlive()) {
            return Optional.of(entry.value());
        }
        return Optional.empty();
    }

    /**
     * Find the most recent StorageEntry for a key across MemTable and SSTables.
     * Returns null if not found anywhere.
     * Caller must hold at least the read lock.
     */
    private StorageEntry findEntry(final byte[] key) {
        Optional<StorageEntry> found = activeMemTable.get(key);
        if (found.isPresent()) {
            return found.get();
        }
        MemTable flushing = flushingMemTable;
        if (flushing != null) {
            found = flushing.get(key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        for (SSTableReader sstable : sstables) {
            try {
                Optional<StorageEntry> result = sstable.get(key);
                if (result.isPresent()) {
                    return result.get();
                }
            } catch (IOException e) {
                LOG.error("SSTable read error", e);
            }
        }
        return null;
    }

    /**
     * If the active MemTable is full, swap in a new empty one and schedule
     * an async flush of the old one on a virtual thread.
     * Caller must hold the write lock.
     */
    private void maybeScheduleFlush() {
        if (!activeMemTable.isFull()) {
            return;
        }
        MemTable toFlush = activeMemTable;
        activeMemTable = new MemTable();
        flushingMemTable = toFlush;

        Thread.ofVirtual().name("kiradb-flush").start(() -> {
            try {
                flushMemTableSync(toFlush);
            } finally {
                flushingMemTable = null;
            }
        });
    }

    /**
     * Flush a MemTable to an SSTable synchronously.
     * Safe to call from any thread — does not hold any lock during disk I/O.
     */
    private void flushMemTableSync(final MemTable memTable) {
        try {
            long seq = sequenceCounter.incrementAndGet();
            SSTableMetadata meta = SSTableWriter.write(memTable, dataDir, seq);
            SSTableReader reader = new SSTableReader(meta);

            lock.writeLock().lock();
            try {
                sstables.add(0, reader); // newest-first
            } finally {
                lock.writeLock().unlock();
            }

            LOG.info("MemTable flushed to SSTable: {}", meta);
        } catch (IOException e) {
            LOG.error("MemTable flush failed", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * On startup, replay the WAL to reconstruct the MemTable.
     * Any writes that were acknowledged but not yet flushed to an SSTable
     * will be recovered here.
     */
    private MemTable replayWal() throws IOException {
        Path walPath = dataDir.resolve(WAL_FILENAME);
        List<WalEntry> entries = Wal.replay(walPath);
        MemTable memTable = new MemTable();

        for (WalEntry entry : entries) {
            switch (entry.op()) {
                case PUT -> {
                    StorageEntry se = entry.expiryMillis() > 0
                            ? StorageEntry.withExpiry(entry.key(), entry.value(), entry.expiryMillis())
                            : StorageEntry.live(entry.key(), entry.value());
                    memTable.put(se);
                }
                case DELETE -> memTable.put(StorageEntry.tombstone(entry.key()));
                default -> { /* unknown op — skip */ }
            }
        }

        return memTable;
    }

    /**
     * Scan the data directory for existing {@code .sst} files and open readers.
     * Files are sorted newest-first by sequence number (embedded in filename).
     */
    private void loadExistingSstables() throws IOException {
        if (!dataDir.toFile().exists()) {
            return;
        }

        List<Path> sstFiles;
        try (Stream<Path> files = Files.list(dataDir)) {
            sstFiles = files
                    .filter(p -> p.toString().endsWith(".sst"))
                    .sorted(Comparator.comparing(
                            (Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }

        for (Path sstFile : sstFiles) {
            try {
                // Parse sequence from filename: sstable-{timestamp}-{seq}.sst
                String name = sstFile.getFileName().toString();
                long seq = parseSequence(name);

                // We need SSTableMetadata to open a reader, but metadata is stored
                // in the file itself. Open a temporary reader to get metadata, then
                // reconstruct with the parsed sequence.
                SSTableMetadata tempMeta = new SSTableMetadata(sstFile, null, null, 0, seq);
                SSTableReader reader = new SSTableReader(tempMeta);
                sstables.add(reader);

                if (seq > sequenceCounter.get()) {
                    sequenceCounter.set(seq);
                }
                LOG.info("Loaded SSTable: {}", name);
            } catch (Exception e) {
                LOG.warn("Failed to load SSTable {}: {}", sstFile.getFileName(), e.getMessage());
            }
        }

        // Sort newest-first
        sstables.sort(Comparator.comparingLong(
                r -> -r.metadata().sequence()));
    }

    private static long parseSequence(final String filename) {
        // filename: sstable-{timestamp}-{seq}.sst
        String withoutExt = filename.replace(".sst", "");
        String[] parts = withoutExt.split("-");
        return Long.parseLong(parts[parts.length - 1]);
    }
}
