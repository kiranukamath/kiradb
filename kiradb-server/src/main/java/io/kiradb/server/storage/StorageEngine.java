package io.kiradb.server.storage;

import java.util.Optional;

/**
 * Core storage contract for KiraDB.
 *
 * <p>Phase 1 implementation is {@link InMemoryStorageEngine} backed by a ConcurrentHashMap.
 * Phase 3 replaces this with the LSM Tree / RocksDB implementation — the interface
 * stays the same, so all command handlers keep working with zero changes.
 *
 * <p>This is the Dependency Inversion Principle in practice: command handlers depend
 * on this abstraction, not on a concrete storage class.
 */
public interface StorageEngine {

    /**
     * Store a value. Overwrites any existing value for the key.
     *
     * @param key the key (never null)
     * @param value the value bytes (never null)
     */
    void put(byte[] key, byte[] value);

    /**
     * Store a value with an expiry.
     *
     * @param key the key
     * @param value the value bytes
     * @param expiryMillis absolute epoch-millis at which the entry expires
     */
    void put(byte[] key, byte[] value, long expiryMillis);

    /**
     * Retrieve a value. Returns empty if the key does not exist or has expired.
     *
     * @param key the key to look up
     * @return the value, or empty if absent / expired
     */
    Optional<byte[]> get(byte[] key);

    /**
     * Delete a key. No-op if the key does not exist.
     *
     * @param key the key to delete
     * @return true if the key existed and was deleted
     */
    boolean delete(byte[] key);

    /**
     * Check whether a key exists and has not expired.
     *
     * @param key the key to check
     * @return true if the key exists
     */
    boolean exists(byte[] key);

    /**
     * Return the remaining TTL of a key in milliseconds.
     *
     * @param key the key
     * @return remaining millis, -1 if no expiry, -2 if key does not exist
     */
    long ttlMillis(byte[] key);

    /**
     * Set an expiry on an existing key.
     *
     * @param key the key
     * @param expiryMillis absolute epoch-millis
     * @return true if the key existed and the expiry was set
     */
    boolean expire(byte[] key, long expiryMillis);
}
