package io.kiradb.semanticcache;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Minimal in-memory {@link StorageEngine} for unit tests — a sorted map so
 * {@code scan} works, with no persistence and no TTL enforcement beyond what
 * the entries carry. Test-scope only; production uses the LSM engine.
 */
final class TestStorageEngine implements StorageEngine {

    private final ConcurrentNavigableMap<byte[], StorageEntry> map =
            new ConcurrentSkipListMap<>(Arrays::compareUnsigned);

    @Override
    public void put(final byte[] key, final byte[] value) {
        map.put(key.clone(), StorageEntry.live(key.clone(), value.clone()));
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        map.put(key.clone(), StorageEntry.withExpiry(key.clone(), value.clone(), expiryMillis));
    }

    @Override
    public Optional<byte[]> get(final byte[] key) {
        StorageEntry entry = map.get(key);
        return entry != null && entry.isAlive() ? Optional.of(entry.value()) : Optional.empty();
    }

    @Override
    public boolean delete(final byte[] key) {
        return map.remove(key) != null;
    }

    @Override
    public boolean exists(final byte[] key) {
        return get(key).isPresent();
    }

    @Override
    public long ttlMillis(final byte[] key) {
        StorageEntry entry = map.get(key);
        if (entry == null || !entry.isAlive()) {
            return -2;
        }
        return entry.expiryMillis() < 0 ? -1
                : entry.expiryMillis() - System.currentTimeMillis();
    }

    @Override
    public boolean expire(final byte[] key, final long expiryMillis) {
        StorageEntry entry = map.get(key);
        if (entry == null || !entry.isAlive()) {
            return false;
        }
        map.put(key.clone(), StorageEntry.withExpiry(entry.key(), entry.value(), expiryMillis));
        return true;
    }

    @Override
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
        ConcurrentNavigableMap<byte[], StorageEntry> range = map;
        if (startKey != null) {
            range = range.tailMap(startKey, true);
        }
        if (endKey != null) {
            range = range.headMap(endKey, false);
        }
        return range.values().stream().filter(StorageEntry::isAlive).iterator();
    }

    @Override
    public void close() {
        map.clear();
    }
}
