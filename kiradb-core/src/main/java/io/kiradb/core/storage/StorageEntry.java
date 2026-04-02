package io.kiradb.core.storage;

import java.util.Arrays;

/**
 * Immutable representation of one entry in the storage engine.
 *
 * <p>A record in Java 25 — the compiler generates constructor, accessors,
 * {@code equals}, {@code hashCode}, and {@code toString} automatically.
 *
 * <p>The {@code deleted} flag marks a tombstone. Tombstones are written
 * to the WAL and MemTable when a key is deleted. They suppress older
 * values in SSTables and are physically removed during compaction.
 *
 * @param key          the key bytes
 * @param value        the value bytes; {@code null} for tombstones
 * @param expiryMillis absolute epoch-millis expiry; {@code -1} means no expiry
 * @param deleted      true if this entry is a tombstone (DELETE marker)
 */
public record StorageEntry(byte[] key, byte[] value, long expiryMillis, boolean deleted) {

    /**
     * True if this entry has passed its expiry time.
     *
     * @return true if expired
     */
    public boolean isExpired() {
        return expiryMillis > 0 && System.currentTimeMillis() > expiryMillis;
    }

    /**
     * True if this entry represents a live, readable value.
     * An entry is alive when it is not a tombstone and has not expired.
     *
     * @return true if the entry can be returned to a client
     */
    public boolean isAlive() {
        return !deleted && !isExpired();
    }

    /**
     * Convenience factory — a live entry with no expiry.
     *
     * @param key   the key
     * @param value the value
     * @return a live StorageEntry
     */
    public static StorageEntry live(final byte[] key, final byte[] value) {
        return new StorageEntry(key, value, -1L, false);
    }

    /**
     * Convenience factory — a live entry with an absolute expiry timestamp.
     *
     * @param key          the key
     * @param value        the value
     * @param expiryMillis absolute epoch-millis
     * @return a live StorageEntry with TTL
     */
    public static StorageEntry withExpiry(
            final byte[] key, final byte[] value, final long expiryMillis) {
        return new StorageEntry(key, value, expiryMillis, false);
    }

    /**
     * Convenience factory — a tombstone (DELETE marker).
     *
     * @param key the key being deleted
     * @return a tombstone StorageEntry
     */
    public static StorageEntry tombstone(final byte[] key) {
        return new StorageEntry(key, null, -1L, true);
    }

    /**
     * Records use structural equality for arrays by default via the generated equals —
     * but byte[] uses reference equality. Override to compare key bytes by value.
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StorageEntry other)) {
            return false;
        }
        return Arrays.equals(key, other.key)
                && Arrays.equals(value, other.value)
                && expiryMillis == other.expiryMillis
                && deleted == other.deleted;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + Long.hashCode(expiryMillis);
        result = 31 * result + Boolean.hashCode(deleted);
        return result;
    }

    @Override
    public String toString() {
        return "StorageEntry{"
                + "key=" + new String(key, java.nio.charset.StandardCharsets.UTF_8)
                + ", deleted=" + deleted
                + ", expiryMillis=" + expiryMillis
                + '}';
    }
}
