package io.kiradb.core.storage;

import java.util.Iterator;
import java.util.Optional;

/**
 * Core storage contract for KiraDB.
 *
 * <p>Phase 2 used {@code InMemoryStorageEngine} (ConcurrentHashMap).
 * Phase 3 provides {@code LsmStorageEngine} — WAL + MemTable + SSTables.
 * All command handlers depend on this interface, not on any concrete class.
 *
 * <p>This is the Dependency Inversion Principle: the network layer (kiradb-server)
 * depends on this abstraction defined in kiradb-core. Swapping storage implementations
 * requires zero changes to command handlers.
 */
public interface StorageEngine extends AutoCloseable {

    /**
     * Store a value with no expiry.
     *
     * @param key   the key (never null)
     * @param value the value bytes (never null)
     */
    void put(byte[] key, byte[] value);

    /**
     * Store a value with an absolute expiry timestamp.
     *
     * @param key          the key
     * @param value        the value bytes
     * @param expiryMillis absolute epoch-millis at which the entry expires
     */
    void put(byte[] key, byte[] value, long expiryMillis);

    /**
     * Retrieve a value. Returns empty if the key does not exist or has expired.
     *
     * @param key the key to look up
     * @return the value, or empty if absent or expired
     */
    Optional<byte[]> get(byte[] key);

    /**
     * Delete a key. Writes a tombstone — the entry is physically removed during compaction.
     *
     * @param key the key to delete
     * @return true if the key existed and was deleted
     */
    boolean delete(byte[] key);

    /**
     * Check whether a key exists and has not expired.
     *
     * @param key the key to check
     * @return true if the key exists and is not expired
     */
    boolean exists(byte[] key);

    /**
     * Return the remaining TTL of a key in milliseconds.
     *
     * @param key the key
     * @return remaining millis; {@code -1} if no expiry; {@code -2} if key does not exist
     */
    long ttlMillis(byte[] key);

    /**
     * Set or update the expiry on an existing key.
     *
     * @param key          the key
     * @param expiryMillis absolute epoch-millis
     * @return true if the key existed and the expiry was set
     */
    boolean expire(byte[] key, long expiryMillis);

    /**
     * Scan all live (non-expired, non-deleted) entries whose keys fall within
     * {@code [startKey, endKey)} in lexicographic (byte) order.
     *
     * <p>Used for range queries and during SSTable compaction.
     *
     * @param startKey inclusive lower bound (null = start from beginning)
     * @param endKey   exclusive upper bound (null = scan to end)
     * @return iterator over matching entries, ordered by key
     */
    Iterator<StorageEntry> scan(byte[] startKey, byte[] endKey);

    /**
     * Flush any in-memory state to disk and release resources.
     * Must be called on clean shutdown.
     */
    void close();
}
