package io.kiradb.core.storage.tier;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory cache — Tier 1 of the KiraDB storage hierarchy.
 *
 * <h2>What it is</h2>
 * <p>A fast key→value map capped at {@code maxEntries}.  When full and a new entry
 * arrives, the entry with the <em>lowest hotness score</em> (from {@link AccessTracker})
 * is evicted to make room.  The evicted data is NOT lost — it always exists on
 * Tier 2 (LsmStorageEngine on SSD).  MemCache is a hot window, not storage.
 *
 * <h2>TTL handling</h2>
 * <p>Entries with an expiry are stored with their absolute expiry timestamp.
 * A {@link #get} that finds an expired entry performs lazy eviction and returns empty.
 *
 * <h2>Capacity</h2>
 * <p>Default is 1 000 000 entries (configurable).  At 35% of a 4 GB JVM heap this
 * comfortably holds 1–2 M small values.  Override via
 * {@link #MemCache(int, AccessTracker)}.
 *
 * <h2>Thread safety</h2>
 * <p>Uses {@link ConcurrentHashMap} for the entry map.  Eviction is best-effort:
 * the cache may temporarily exceed {@code maxEntries} by one under concurrent puts —
 * this is harmless since MemCache is a cache, not authoritative storage.
 */
public final class MemCache {

    /** Default maximum number of entries. */
    public static final int DEFAULT_MAX_ENTRIES = 1_000_000;

    /**
     * A stored value paired with its absolute expiry.
     * {@code expiryMillis <= 0} means no expiry.
     */
    private record CacheEntry(byte[] value, long expiryMillis) {
        boolean isExpired() {
            return expiryMillis > 0 && System.currentTimeMillis() > expiryMillis;
        }
    }

    /**
     * Wrapper so {@code byte[]} can be a {@link ConcurrentHashMap} key.
     * Java arrays do not override {@code equals}/{@code hashCode} — this does.
     */
    private record CacheKey(byte[] bytes) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof CacheKey ck && Arrays.equals(bytes, ck.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final AccessTracker accessTracker;

    /**
     * Create a MemCache with the default capacity.
     *
     * @param accessTracker used to score entries during eviction
     */
    public MemCache(final AccessTracker accessTracker) {
        this(DEFAULT_MAX_ENTRIES, accessTracker);
    }

    /**
     * Create a MemCache with a custom capacity.
     *
     * @param maxEntries    maximum number of entries before eviction triggers
     * @param accessTracker used to score entries during eviction
     */
    public MemCache(final int maxEntries, final AccessTracker accessTracker) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.maxEntries    = maxEntries;
        this.accessTracker = accessTracker;
    }

    /**
     * Look up a key.  Returns empty if absent or expired (lazy eviction on expiry).
     *
     * @param key the key to look up
     * @return the value, or empty if absent or expired
     */
    public Optional<byte[]> get(final byte[] key) {
        CacheEntry entry = cache.get(new CacheKey(key));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            cache.remove(new CacheKey(key));
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /**
     * Insert or replace an entry.  If the cache is at capacity, the entry with
     * the lowest hotness score is evicted first.
     *
     * @param key          the key
     * @param value        the value bytes
     * @param expiryMillis absolute epoch-millis expiry; {@code <= 0} means no expiry
     */
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        if (cache.size() >= maxEntries && !cache.containsKey(new CacheKey(key))) {
            evictOne();
        }
        cache.put(new CacheKey(key), new CacheEntry(value, expiryMillis));
    }

    /**
     * Remove a key from the cache.  No-op if absent.
     *
     * @param key the key to remove
     */
    public void remove(final byte[] key) {
        cache.remove(new CacheKey(key));
    }

    /**
     * Return whether a key is present and not expired.
     *
     * @param key the key to check
     * @return true if the key is hot (present and live)
     */
    public boolean contains(final byte[] key) {
        CacheEntry entry = cache.get(new CacheKey(key));
        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            cache.remove(new CacheKey(key));
            return false;
        }
        return true;
    }

    /**
     * Number of entries currently in the cache (includes not-yet-lazily-evicted expired entries).
     *
     * @return current entry count
     */
    public int size() {
        return cache.size();
    }

    /**
     * Remove all entries from the cache.
     */
    public void clear() {
        cache.clear();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Evict the entry with the lowest hotness score.
     * Scans all current entries — O(n) — acceptable because eviction is infrequent
     * (only fires when the cache first reaches capacity).
     */
    private void evictOne() {
        CacheKey lowestKey   = null;
        double   lowestScore = Double.MAX_VALUE;

        for (CacheKey ck : cache.keySet()) {
            // Lazily remove expired entries we stumble upon
            CacheEntry entry = cache.get(ck);
            if (entry != null && entry.isExpired()) {
                cache.remove(ck);
                continue;
            }
            double score = accessTracker.score(ck.bytes());
            if (score < lowestScore) {
                lowestScore = score;
                lowestKey   = ck;
            }
        }

        if (lowestKey != null) {
            cache.remove(lowestKey);
        }
    }
}
