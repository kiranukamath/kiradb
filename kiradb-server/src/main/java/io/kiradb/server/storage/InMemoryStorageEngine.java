package io.kiradb.server.storage;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 storage engine — a plain {@link ConcurrentHashMap}.
 *
 * <p>Data does not survive server restarts. Phase 3 replaces this with the
 * WAL-backed LSM Tree implementation. The {@link StorageEngine} interface
 * means no other code changes are needed when we swap it out.
 *
 * <p>{@link ConcurrentHashMap} gives us thread-safe reads and writes at the
 * bucket level. Each Netty EventLoop thread can call {@code get}/{@code put}
 * concurrently without external locking.
 */
public final class InMemoryStorageEngine implements StorageEngine {

    private record Entry(byte[] value, long expiryMillis) {
        boolean isExpired() {
            return expiryMillis > 0 && System.currentTimeMillis() > expiryMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void put(final byte[] key, final byte[] value) {
        store.put(toKey(key), new Entry(value, -1));
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        store.put(toKey(key), new Entry(value, expiryMillis));
    }

    @Override
    public Optional<byte[]> get(final byte[] key) {
        Entry entry = store.get(toKey(key));
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                store.remove(toKey(key)); // lazy expiry
            }
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public boolean delete(final byte[] key) {
        return store.remove(toKey(key)) != null;
    }

    @Override
    public boolean exists(final byte[] key) {
        Entry entry = store.get(toKey(key));
        if (entry == null || entry.isExpired()) {
            return false;
        }
        return true;
    }

    @Override
    public long ttlMillis(final byte[] key) {
        Entry entry = store.get(toKey(key));
        if (entry == null || entry.isExpired()) {
            return -2; // key does not exist
        }
        if (entry.expiryMillis() < 0) {
            return -1; // no expiry
        }
        return entry.expiryMillis() - System.currentTimeMillis();
    }

    @Override
    public boolean expire(final byte[] key, final long expiryMillis) {
        String k = toKey(key);
        Entry entry = store.get(k);
        if (entry == null || entry.isExpired()) {
            return false;
        }
        store.put(k, new Entry(entry.value(), expiryMillis));
        return true;
    }

    /**
     * Keys are stored as UTF-8 strings internally so ConcurrentHashMap
     * can use value equality. Raw byte arrays use reference equality
     * in Java, which would break lookups.
     */
    private String toKey(final byte[] key) {
        return new String(key, java.nio.charset.StandardCharsets.UTF_8);
    }
}
