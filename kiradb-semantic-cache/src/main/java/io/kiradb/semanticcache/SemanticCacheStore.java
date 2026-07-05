package io.kiradb.semanticcache;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.semanticcache.embedding.EmbeddingProvider;
import io.kiradb.semanticcache.index.SearchResult;
import io.kiradb.semanticcache.index.VectorIndex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The semantic cache: stores (prompt → response) pairs and answers lookups by
 * <em>meaning</em>, not exact key match.
 *
 * <h2>How the pieces fit</h2>
 * <pre>
 *   set(prompt, response)                get(query)
 *        │                                   │
 *        ▼                                   ▼
 *   EmbeddingProvider.embed()          EmbeddingProvider.embed()
 *        │                                   │
 *        ├── StorageEngine.put(             VectorIndex.search(top-5)
 *        │     "sc:entry:&lt;sha256&gt;",           │  skip expired (lazy delete)
 *        │     serialized entry)             │  first score ≥ threshold → HIT
 *        └── VectorIndex.add()               └─ else → MISS
 * </pre>
 *
 * <h2>Persistence format</h2>
 * <p>Each entry is stored under key {@code sc:entry:<hex sha-256 of prompt>}
 * with a binary payload written via {@link DataOutputStream}:
 * <pre>
 *   int    promptLen,  promptLen UTF-8 bytes
 *   int    responseLen, responseLen UTF-8 bytes
 *   long   expiresAtMillis   (-1 = never)
 *   int    dimension
 *   float × dimension        (the L2-normalized embedding)
 * </pre>
 * Persisting the embedding means a restart rebuilds the index by scanning the
 * {@code sc:entry:} prefix — no re-embedding, no external service needed at boot.
 *
 * <h2>TTL</h2>
 * <p>Expiry lives inside the payload (not the storage engine's TTL) so it is
 * checked against this store's injectable clock — deterministic in tests, and
 * a single source of truth. Expired entries are removed lazily on the lookup
 * that discovers them; searches use top-k=5 so an expired best match does not
 * hide a live second-best match.
 *
 * <p>Thread-safe: counters are {@link AtomicLong}s, the index and storage are
 * themselves thread-safe, and entries are immutable once written.
 */
public final class SemanticCacheStore {

    /** Storage key prefix for all semantic cache entries. */
    static final String KEY_PREFIX = "sc:entry:";

    /** Top-k searched per lookup so expired entries can be skipped without a re-search. */
    private static final int SEARCH_K = 5;

    private final StorageEngine storage;
    private final EmbeddingProvider provider;
    private final VectorIndex index;
    private final double defaultThreshold;
    private final LongSupplier clock;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong estimatedTokensSaved = new AtomicLong();

    /**
     * Create a store using the system clock.
     *
     * @param storage          durable storage for entries (survives restarts)
     * @param provider         embedding provider (its normalization contract is relied upon)
     * @param index            in-memory similarity index (rebuilt from storage on construction)
     * @param defaultThreshold similarity threshold used by {@link #get(String)}, in {@code (0, 1]}
     */
    public SemanticCacheStore(
            final StorageEngine storage,
            final EmbeddingProvider provider,
            final VectorIndex index,
            final double defaultThreshold) {
        this(storage, provider, index, defaultThreshold, System::currentTimeMillis);
    }

    /**
     * Create a store with an injectable clock — used by tests to exercise TTL
     * expiry without sleeping.
     *
     * @param storage          durable storage for entries
     * @param provider         embedding provider
     * @param index            in-memory similarity index
     * @param defaultThreshold default similarity threshold in {@code (0, 1]}
     * @param clock            supplier of "now" in epoch millis
     */
    public SemanticCacheStore(
            final StorageEngine storage,
            final EmbeddingProvider provider,
            final VectorIndex index,
            final double defaultThreshold,
            final LongSupplier clock) {
        if (defaultThreshold <= 0.0 || defaultThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "defaultThreshold must be in (0, 1], got " + defaultThreshold);
        }
        this.storage = storage;
        this.provider = provider;
        this.index = index;
        this.defaultThreshold = defaultThreshold;
        this.clock = clock;
        rebuildIndex();
    }

    /**
     * Cache a response under a prompt.
     *
     * @param prompt     the prompt text (the semantic key)
     * @param response   the response to serve on similar future prompts
     * @param ttlSeconds seconds until expiry; {@code <= 0} means never expires
     */
    public void set(final String prompt, final String response, final long ttlSeconds) {
        float[] vector = provider.embed(prompt);
        long expiresAt = ttlSeconds <= 0 ? -1L : clock.getAsLong() + ttlSeconds * 1000L;
        String id = entryId(prompt);
        storage.put(storageKey(id), serialize(prompt, response, expiresAt, vector));
        index.add(id, vector);
    }

    /**
     * Look up using the store's default threshold.
     *
     * @param prompt the query prompt
     * @return the best non-expired match at or above the default threshold
     */
    public Optional<CacheHit> get(final String prompt) {
        return get(prompt, defaultThreshold);
    }

    /**
     * Look up the best semantically similar cached response.
     *
     * <p>Searches the top-{@value #SEARCH_K} nearest neighbors; expired
     * candidates are deleted lazily and skipped. The first live candidate at
     * or above {@code threshold} wins (results arrive best-first, so it is
     * also the best live one).
     *
     * @param prompt    the query prompt
     * @param threshold minimum cosine similarity to accept, in {@code (0, 1]}
     * @return a {@link CacheHit} on success, empty on miss
     */
    public Optional<CacheHit> get(final String prompt, final double threshold) {
        float[] query = provider.embed(prompt);
        long now = clock.getAsLong();
        for (SearchResult candidate : index.search(query, SEARCH_K)) {
            if (candidate.score() < threshold) {
                break; // results are ordered best-first — nothing below can qualify
            }
            Optional<Entry> loaded = load(candidate.id());
            if (loaded.isEmpty()) {
                index.remove(candidate.id()); // storage lost it — drop the dangling vector
                continue;
            }
            Entry entry = loaded.get();
            if (entry.isExpired(now)) {
                removeEntry(candidate.id()); // lazy expiry
                continue;
            }
            hits.incrementAndGet();
            estimatedTokensSaved.addAndGet(entry.response().length() / 4L);
            return Optional.of(new CacheHit(entry.prompt(), entry.response(), candidate.score()));
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Delete the entry for an exact prompt (byte-identical, not semantic).
     *
     * @param prompt the exact prompt used at {@link #set} time
     * @return true if an entry existed and was removed
     */
    public boolean delete(final String prompt) {
        return removeEntry(entryId(prompt));
    }

    /**
     * Snapshot the cache counters.
     *
     * @return current hits, misses, live entry count, and estimated tokens saved
     */
    public SemanticCacheStats stats() {
        return new SemanticCacheStats(
                hits.get(), misses.get(), index.size(), estimatedTokensSaved.get());
    }

    /**
     * The default similarity threshold this store was configured with.
     *
     * @return the default threshold
     */
    public double defaultThreshold() {
        return defaultThreshold;
    }

    // ── internals ─────────────────────────────────────────────────────────

    /** Scan the sc:entry: prefix and repopulate the index — crash/restart recovery. */
    private void rebuildIndex() {
        byte[] start = KEY_PREFIX.getBytes(StandardCharsets.UTF_8);
        byte[] end = start.clone();
        end[end.length - 1]++; // ':' → ';' — exclusive upper bound of the prefix range
        long now = clock.getAsLong();
        Iterator<StorageEntry> it = storage.scan(start, end);
        while (it.hasNext()) {
            StorageEntry stored = it.next();
            String id = new String(stored.key(), StandardCharsets.UTF_8)
                    .substring(KEY_PREFIX.length());
            Entry entry = deserialize(stored.value());
            if (entry.isExpired(now)) {
                storage.delete(stored.key());
                continue;
            }
            index.add(id, entry.embedding());
        }
    }

    private boolean removeEntry(final String id) {
        boolean inIndex = index.remove(id);
        boolean inStorage = storage.delete(storageKey(id));
        return inIndex || inStorage;
    }

    private Optional<Entry> load(final String id) {
        return storage.get(storageKey(id)).map(SemanticCacheStore::deserialize);
    }

    private static byte[] storageKey(final String id) {
        return (KEY_PREFIX + id).getBytes(StandardCharsets.UTF_8);
    }

    /** Hex SHA-256 of the prompt — stable, collision-safe entry id and storage key suffix. */
    private static String entryId(final String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — mandated by the JVM spec", e);
        }
    }

    private static byte[] serialize(
            final String prompt, final String response, final long expiresAt, final float[] vector) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + vector.length * 4);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] promptBytes = prompt.getBytes(StandardCharsets.UTF_8);
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            out.writeInt(promptBytes.length);
            out.write(promptBytes);
            out.writeInt(responseBytes.length);
            out.write(responseBytes);
            out.writeLong(expiresAt);
            out.writeInt(vector.length);
            for (float v : vector) {
                out.writeFloat(v);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("In-memory serialization cannot fail", e);
        }
        return bytes.toByteArray();
    }

    private static Entry deserialize(final byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] promptBytes = new byte[in.readInt()];
            in.readFully(promptBytes);
            byte[] responseBytes = new byte[in.readInt()];
            in.readFully(responseBytes);
            long expiresAt = in.readLong();
            float[] vector = new float[in.readInt()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = in.readFloat();
            }
            return new Entry(
                    new String(promptBytes, StandardCharsets.UTF_8),
                    new String(responseBytes, StandardCharsets.UTF_8),
                    expiresAt,
                    vector);
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt semantic cache entry", e);
        }
    }

    /** Deserialized on-disk entry. */
    private record Entry(String prompt, String response, long expiresAtMillis, float[] embedding) {
        boolean isExpired(final long nowMillis) {
            return expiresAtMillis > 0 && nowMillis > expiresAtMillis;
        }
    }
}
