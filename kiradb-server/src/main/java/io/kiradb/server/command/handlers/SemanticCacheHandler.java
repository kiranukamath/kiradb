package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.semanticcache.CacheHit;
import io.kiradb.semanticcache.SemanticCacheStats;
import io.kiradb.semanticcache.SemanticCacheStore;
import io.kiradb.semanticcache.embedding.EmbeddingException;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Single dispatcher for {@code SC.*} RESP3 commands — the semantic cache.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   SC.SET   prompt response [EX seconds]   — cache a response; +OK
 *   SC.GET   prompt [THRESHOLD t]           — bulk string on hit, nil on miss
 *   SC.DEL   prompt                         — :1 if removed, :0 otherwise
 *   SC.STATS                                — map of hits/misses/entries/tokens/hit_rate
 * </pre>
 *
 * <p>{@code SC.GET} returns just the response body so it is a drop-in
 * replacement for a plain {@code GET} in LLM client code; the matched prompt
 * and similarity live in {@link CacheHit} for future exposure.
 */
public final class SemanticCacheHandler implements CommandHandler {

    private final SemanticCacheStore store;

    /**
     * Construct the dispatcher.
     *
     * @param store the semantic cache store backing all SC.* commands
     */
    public SemanticCacheHandler(final SemanticCacheStore store) {
        this.store = store;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        try {
            return switch (command.name()) {
                case "SC.SET" -> handleSet(command);
                case "SC.GET" -> handleGet(command);
                case "SC.DEL" -> handleDel(command);
                case "SC.STATS" -> handleStats(command);
                default -> Resp3Value.error("ERR unknown SC subcommand '" + command.name() + "'");
            };
        } catch (EmbeddingException e) {
            return Resp3Value.error("ERR embedding failed: " + e.getMessage());
        }
    }

    private Resp3Value handleSet(final Command command) {
        if (command.arity() != 2 && command.arity() != 4) {
            return Resp3Value.wrongArity("SC.SET");
        }
        long ttlSeconds = 0;
        if (command.arity() == 4) {
            if (!"EX".equalsIgnoreCase(command.argAsString(2))) {
                return Resp3Value.error("ERR syntax error — expected EX, got '"
                        + command.argAsString(2) + "'");
            }
            try {
                ttlSeconds = Long.parseLong(command.argAsString(3));
            } catch (NumberFormatException e) {
                return Resp3Value.error("ERR value is not an integer or out of range");
            }
            if (ttlSeconds <= 0) {
                return Resp3Value.error("ERR invalid expire time in 'SC.SET' command");
            }
        }
        store.set(command.argAsString(0), command.argAsString(1), ttlSeconds);
        return Resp3Value.ok();
    }

    private Resp3Value handleGet(final Command command) {
        if (command.arity() != 1 && command.arity() != 3) {
            return Resp3Value.wrongArity("SC.GET");
        }
        Optional<CacheHit> hit;
        if (command.arity() == 3) {
            if (!"THRESHOLD".equalsIgnoreCase(command.argAsString(1))) {
                return Resp3Value.error("ERR syntax error — expected THRESHOLD, got '"
                        + command.argAsString(1) + "'");
            }
            double threshold;
            try {
                threshold = Double.parseDouble(command.argAsString(2));
            } catch (NumberFormatException e) {
                return Resp3Value.error("ERR threshold is not a valid float");
            }
            if (threshold <= 0.0 || threshold > 1.0) {
                return Resp3Value.error("ERR threshold must be in (0, 1]");
            }
            hit = store.get(command.argAsString(0), threshold);
        } else {
            hit = store.get(command.argAsString(0));
        }
        return hit.<Resp3Value>map(h ->
                        new Resp3Value.BulkString(h.response().getBytes(StandardCharsets.UTF_8)))
                .orElseGet(Resp3Value::nil);
    }

    private Resp3Value handleDel(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("SC.DEL");
        }
        return new Resp3Value.RespInteger(store.delete(command.argAsString(0)) ? 1L : 0L);
    }

    private Resp3Value handleStats(final Command command) {
        if (command.arity() != 0) {
            return Resp3Value.wrongArity("SC.STATS");
        }
        SemanticCacheStats stats = store.stats();
        Map<Resp3Value, Resp3Value> entries = new LinkedHashMap<>();
        entries.put(bulk("hits"), new Resp3Value.RespInteger(stats.hits()));
        entries.put(bulk("misses"), new Resp3Value.RespInteger(stats.misses()));
        entries.put(bulk("entries"), new Resp3Value.RespInteger(stats.entries()));
        entries.put(bulk("estimated_tokens_saved"),
                new Resp3Value.RespInteger(stats.estimatedTokensSaved()));
        entries.put(bulk("hit_rate"), new Resp3Value.SimpleString(
                String.format(Locale.ROOT, "%.2f", stats.hitRate())));
        return new Resp3Value.RespMap(entries);
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
