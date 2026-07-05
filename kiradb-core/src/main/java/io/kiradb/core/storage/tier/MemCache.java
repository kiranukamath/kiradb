package io.kiradb.core.storage.tier;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory cache — Tier 1 of the KiraDB storage hierarchy.
 *
 * <h2>What it is</h2>
 * <p>A fast key→value map capped either by entry count or by an estimated byte
 * budget.  When full and a new entry arrives, the entry with the <em>lowest
 * hotness score</em> (from {@link AccessTracker}) is evicted to make room — in
 * byte-budget mode, entries keep being evicted lowest-score-first until the
 * cache is back under budget, since one incoming entry can be much larger than
 * the one it displaces.  The evicted data is NOT lost — it always exists on
 * Tier 2 (LsmStorageEngine on SSD).  MemCache is a hot window, not storage.
 * If a single incoming entry is larger than the entire configured byte budget,
 * it is still admitted (evicting everything else first) rather than rejected —
 * consistent with MemCache being a best-effort cache, not authoritative storage.
 *
 * <h2>TTL handling</h2>
 * <p>Entries with an expiry are stored with their absolute expiry timestamp.
 * A {@link #get} that finds an expired entry performs lazy eviction and returns empty.
 *
 * <h2>Capacity: entry-count mode vs. byte-budget mode</h2>
 * <p>The original (Phase 5) design capped capacity by entry count
 * ({@link #MemCache(int, AccessTracker)}), which is simple but only tracks the
 * heap budget accurately when values are roughly uniform in size. Real workloads
 * rarely are — a cache holding 1M 10-byte counters and a cache holding 1M 10KB
 * JSON blobs have wildly different memory footprints for the same
 * {@code maxEntries}.
 *
 * <p>{@link #ofMaxBytes(long, AccessTracker)} switches to tracking a running
 * estimated byte size instead, so capacity tracks the actual heap budget
 * (e.g. 35% of {@code -Xmx}) directly. Per-entry cost is estimated as:
 * <pre>
 *   key.length + value.length + PER_ENTRY_OVERHEAD_BYTES
 * </pre>
 * {@code PER_ENTRY_OVERHEAD_BYTES} (see constant below) approximates the fixed
 * JVM object overhead per cached entry — the {@code CacheKey} wrapper, the
 * {@code CacheEntry} record, and the {@code ConcurrentHashMap} node plus its
 * slot share — per the per-entry RAM accounting in CLAUDE.md's Phase 5 section
 * (~78 B fixed overhead for a HOT entry, exclusive of the {@code ceil8} rounding
 * on key/value byte arrays, which we don't attempt to replicate exactly here —
 * this is a budget estimate, not an exact accounting).
 *
 * <h2>Thread safety</h2>
 * <p>Uses {@link ConcurrentHashMap} for the entry map.  Eviction is best-effort:
 * the cache may temporarily exceed its budget under concurrent puts —
 * this is harmless since MemCache is a cache, not authoritative storage. The
 * byte-budget running total ({@code currentBytes}) is maintained with an
 * {@link AtomicLong} and is therefore exact modulo the same best-effort race.
 */
public final class MemCache {

    /** Default maximum number of entries (entry-count mode). */
    public static final int DEFAULT_MAX_ENTRIES = 1_000_000;

    /**
     * Estimated fixed per-entry overhead in bytes, used only in byte-budget mode
     * to convert {@code key.length + value.length} into an approximate total
     * heap footprint. See the class Javadoc for the derivation.
     */
    public static final int PER_ENTRY_OVERHEAD_BYTES = 78;

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

    /** Entry-count cap; {@code -1} when this instance is in byte-budget mode. */
    private final int maxEntries;

    /** Byte budget cap; {@code -1} when this instance is in entry-count mode. */
    private final long maxBytes;

    /** Running estimated byte size of all live entries; only maintained in byte-budget mode. */
    private final AtomicLong currentBytes = new AtomicLong(0L);

    private final AccessTracker accessTracker;

    // ── metrics (Phase 13 hardening) — plain counters, Micrometer-agnostic ────
    // These are maintained unconditionally (cheap AtomicLong increments) so that
    // callers who don't wire a MeterRegistry still get the numbers via the plain
    // accessor methods below; TieredStorageEngine's Micrometer gauges/counters
    // (when a registry is supplied) just read these.
    private final AtomicLong hitCount = new AtomicLong(0L);
    private final AtomicLong missCount = new AtomicLong(0L);
    private final AtomicLong evictionCount = new AtomicLong(0L);

    /**
     * Create a MemCache with the default entry-count capacity.
     *
     * @param accessTracker used to score entries during eviction
     */
    public MemCache(final AccessTracker accessTracker) {
        this(DEFAULT_MAX_ENTRIES, accessTracker);
    }

    /**
     * Create a MemCache in entry-count mode with a custom capacity.
     *
     * @param maxEntries    maximum number of entries before eviction triggers
     * @param accessTracker used to score entries during eviction
     */
    public MemCache(final int maxEntries, final AccessTracker accessTracker) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.maxEntries    = maxEntries;
        this.maxBytes      = -1L;
        this.accessTracker = accessTracker;
    }

    /**
     * Create a MemCache in byte-budget mode: capacity is tracked as an estimated
     * total byte size (see class Javadoc for the per-entry estimate) rather than
     * an entry count. Use this when values vary widely in size and you want
     * capacity to track the actual heap budget directly.
     *
     * @param maxBytes      maximum estimated total bytes before eviction triggers
     * @param accessTracker used to score entries during eviction
     * @return a MemCache in byte-budget mode
     */
    public static MemCache ofMaxBytes(final long maxBytes, final AccessTracker accessTracker) {
        return new MemCache(maxBytes, accessTracker, true);
    }

    /** Private byte-budget-mode constructor; the boolean disambiguates from the int overload. */
    private MemCache(final long maxBytes, final AccessTracker accessTracker, final boolean byteMode) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be >= 1");
        }
        this.maxEntries    = -1;
        this.maxBytes      = maxBytes;
        this.accessTracker = accessTracker;
    }

    /**
     * @return true if this instance caps capacity by estimated byte size rather than entry count
     */
    public boolean isByteBudgetMode() {
        return maxBytes >= 0;
    }

    private static long estimatedEntryBytes(final byte[] key, final byte[] value) {
        return (long) key.length + value.length + PER_ENTRY_OVERHEAD_BYTES;
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
            missCount.incrementAndGet();
            return Optional.empty();
        }
        if (entry.isExpired()) {
            cache.remove(new CacheKey(key));
            missCount.incrementAndGet();
            return Optional.empty();
        }
        hitCount.incrementAndGet();
        return Optional.of(entry.value());
    }

    /**
     * Insert or replace an entry.  If the cache is at capacity, the entry (or
     * entries, in byte-budget mode) with the lowest hotness score are evicted first.
     *
     * @param key          the key
     * @param value        the value bytes
     * @param expiryMillis absolute epoch-millis expiry; {@code <= 0} means no expiry
     */
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        CacheKey cacheKey = new CacheKey(key);
        if (isByteBudgetMode()) {
            long incomingBytes = estimatedEntryBytes(key, value);
            // Evict lowest-score entries — which may include this same key's existing
            // entry, if it happens to be the coldest — until admitting the incoming
            // entry keeps us under budget. Loop (not a single evictOne()) because one
            // incoming entry can be far larger than any single entry it displaces.
            while (currentBytes.get() - currentSizeOf(cacheKey) + incomingBytes > maxBytes
                    && evictOne()) {
                // evictOne() already adjusted currentBytes for whatever it removed;
                // re-check on the next loop condition via currentSizeOf(cacheKey).
                continue;
            }
            currentBytes.addAndGet(incomingBytes - currentSizeOf(cacheKey));
        } else {
            if (cache.size() >= maxEntries && !cache.containsKey(cacheKey)) {
                evictOne();
            }
        }
        cache.put(cacheKey, new CacheEntry(value, expiryMillis));
    }

    /**
     * @param cacheKey the key to check
     * @return the estimated byte size currently accounted for this key, or 0 if absent
     */
    private long currentSizeOf(final CacheKey cacheKey) {
        CacheEntry entry = cache.get(cacheKey);
        return entry == null ? 0L : estimatedEntryBytes(cacheKey.bytes(), entry.value());
    }

    /**
     * Remove a key from the cache.  No-op if absent.
     *
     * @param key the key to remove
     */
    public void remove(final byte[] key) {
        CacheEntry removed = cache.remove(new CacheKey(key));
        if (isByteBudgetMode() && removed != null) {
            currentBytes.addAndGet(-estimatedEntryBytes(key, removed.value()));
        }
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
     * The configured capacity — the entry count at which eviction triggers.
     * Only meaningful in entry-count mode; returns {@code -1} in byte-budget mode.
     *
     * @return maximum entry count, or -1 if this instance is in byte-budget mode
     */
    public int maxEntries() {
        return maxEntries;
    }

    /**
     * The configured byte budget. Only meaningful in byte-budget mode; returns
     * {@code -1} in entry-count mode.
     *
     * @return maximum estimated total bytes, or -1 if this instance is in entry-count mode
     */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Current estimated total byte size of all live entries. Only meaningful in
     * byte-budget mode; returns {@code 0} in entry-count mode (not tracked there).
     *
     * @return current estimated byte size
     */
    public long currentBytes() {
        return currentBytes.get();
    }

    /**
     * Remove all entries from the cache.
     */
    public void clear() {
        cache.clear();
        currentBytes.set(0L);
    }

    /**
     * Total number of {@link #get} calls that found a live entry, since construction.
     *
     * @return cumulative hit count
     */
    public long hitCount() {
        return hitCount.get();
    }

    /**
     * Total number of {@link #get} calls that found no live entry (absent or expired),
     * since construction.
     *
     * @return cumulative miss count
     */
    public long missCount() {
        return missCount.get();
    }

    /**
     * Total number of entries evicted to make room for a new entry (capacity pressure),
     * since construction. Does not include lazy removal of individually-expired entries
     * encountered incidentally during an eviction scan — those are a TTL expiry, not a
     * capacity-driven eviction.
     *
     * @return cumulative eviction count
     */
    public long evictionCount() {
        return evictionCount.get();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Evict the entry with the lowest hotness score.
     * Scans all current entries — O(n) — acceptable because eviction is infrequent
     * (only fires when the cache first reaches capacity; in byte-budget mode it may
     * loop, but each loop iteration is still one O(n) scan removing one entry).
     *
     * @return true if an entry was evicted (an empty/all-expired cache evicts nothing)
     */
    private boolean evictOne() {
        CacheKey lowestKey   = null;
        double   lowestScore = Double.MAX_VALUE;

        for (CacheKey ck : cache.keySet()) {
            // Lazily remove expired entries we stumble upon
            CacheEntry entry = cache.get(ck);
            if (entry != null && entry.isExpired()) {
                if (cache.remove(ck, entry) && isByteBudgetMode()) {
                    currentBytes.addAndGet(-estimatedEntryBytes(ck.bytes(), entry.value()));
                }
                continue;
            }
            double score = accessTracker.score(ck.bytes());
            if (score < lowestScore) {
                lowestScore = score;
                lowestKey   = ck;
            }
        }

        if (lowestKey != null) {
            CacheEntry removed = cache.remove(lowestKey);
            if (removed != null) {
                evictionCount.incrementAndGet();
                if (isByteBudgetMode()) {
                    currentBytes.addAndGet(-estimatedEntryBytes(lowestKey.bytes(), removed.value()));
                }
            }
            return true;
        }
        return false;
    }
}
