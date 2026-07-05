package io.kiradb.client;

import io.kiradb.client.protocol.RespValue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent facade over the {@code SC.*} commands.
 *
 * <p>Immutable and cheap to re-derive: {@link #threshold(double)} returns a
 * new instance rather than mutating this one, so a base client can be shared
 * and specialized per call site:
 * <pre>{@code
 * SemanticCacheClient strict = db.semanticCache().threshold(0.95);
 * Optional<String> cached = strict.get(prompt);
 * }</pre>
 */
public final class SemanticCacheClient {

    /**
     * Sentinel meaning "use the server's configured default threshold" — not a
     * valid similarity score itself (valid scores are in {@code (0, 1]}).
     */
    static final double SERVER_DEFAULT_THRESHOLD = -1.0;

    private final KiraDB db;
    private final double threshold;

    SemanticCacheClient(final KiraDB db, final double threshold) {
        this.db = db;
        this.threshold = threshold;
    }

    /**
     * Derive a client that requires a minimum similarity score on {@link #get}.
     *
     * @param minSimilarity minimum cosine similarity to accept, in {@code (0, 1]}
     * @return a new client with this threshold; the receiver is unchanged
     */
    public SemanticCacheClient threshold(final double minSimilarity) {
        if (minSimilarity <= 0.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException(
                    "threshold must be in (0, 1], got " + minSimilarity);
        }
        return new SemanticCacheClient(db, minSimilarity);
    }

    /**
     * Cache a response under a prompt with no expiry.
     *
     * @param prompt   the prompt text
     * @param response the response to serve on similar future prompts
     */
    public void set(final String prompt, final String response) {
        db.call("SC.SET", prompt, response);
    }

    /**
     * Cache a response under a prompt that expires after {@code ttl}.
     *
     * @param prompt   the prompt text
     * @param response the response to serve on similar future prompts
     * @param ttl      time to live; must be positive
     */
    public void set(final String prompt, final String response, final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration, got " + ttl);
        }
        db.call("SC.SET", prompt, response, "EX", Long.toString(ttl.toSeconds()));
    }

    /**
     * Look up the best semantically similar cached response, using this
     * client's threshold (the server's default unless {@link #threshold} was
     * called).
     *
     * @param prompt the query prompt
     * @return the cached response on a hit, empty on a miss
     */
    public Optional<String> get(final String prompt) {
        RespValue reply = threshold == SERVER_DEFAULT_THRESHOLD
                ? db.call("SC.GET", prompt)
                : db.call("SC.GET", prompt, "THRESHOLD", Double.toString(threshold));
        return Optional.ofNullable(Replies.asStringOrNull(reply));
    }

    /**
     * Delete the entry for an exact prompt (byte-identical, not semantic).
     *
     * @param prompt the exact prompt used at {@link #set} time
     * @return true if an entry existed and was removed
     */
    public boolean delete(final String prompt) {
        return Replies.asLong(db.call("SC.DEL", prompt)) == 1L;
    }

    /**
     * Fetch cache-wide counters.
     *
     * @return current hits, misses, entry count, estimated tokens saved, and hit rate
     */
    public SemanticCacheStats stats() {
        Map<String, RespValue> m = Replies.asMap(db.call("SC.STATS"));
        return new SemanticCacheStats(
                Replies.mapLong(m, "hits"),
                Replies.mapLong(m, "misses"),
                Replies.mapLong(m, "entries"),
                Replies.mapLong(m, "estimated_tokens_saved"),
                Double.parseDouble(Replies.scalarToString(m.get("hit_rate"))));
    }
}
