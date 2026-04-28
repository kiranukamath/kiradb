package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.services.ratelimit.RateLimitDecision;
import io.kiradb.services.ratelimit.RateLimiterStore;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single dispatcher for all {@code RL.*} RESP3 commands.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   RL.ALLOW   limiter key limit periodSec  — increment + decide; returns 1 (allowed) or 0 (denied)
 *   RL.STATUS  limiter key limit periodSec  — inspect without consuming; returns RespMap of stats
 *   RL.RESET   limiter key                  — admin reset (NOT YET IMPLEMENTED — GCounter cannot decrement)
 * </pre>
 *
 * <p>Note on RESET: GCounter is grow-only, so we cannot truly reset a counter
 * without coordinating across nodes to write a tombstone — non-trivial and out
 * of scope for Phase 7. Accepted RESET returns an explicit error pointing the
 * operator at restarting the affected bucket window or waiting for natural roll-over.
 */
public final class RateLimitHandler implements CommandHandler {

    private final RateLimiterStore store;

    /**
     * Construct a rate-limit dispatcher.
     *
     * @param store the rate limiter store
     */
    public RateLimitHandler(final RateLimiterStore store) {
        this.store = store;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        return switch (command.name()) {
            case "RL.ALLOW" -> handleAllow(command);
            case "RL.STATUS" -> handleStatus(command);
            case "RL.RESET" -> handleReset(command);
            default -> Resp3Value.error("ERR unknown RL subcommand '" + command.name() + "'");
        };
    }

    private Resp3Value handleAllow(final Command command) {
        if (command.arity() != 4) {
            return Resp3Value.wrongArity("RL.ALLOW");
        }
        String limiter = command.argAsString(0);
        String key     = command.argAsString(1);
        long limit     = Long.parseLong(command.argAsString(2));
        long periodSec = Long.parseLong(command.argAsString(3));
        RateLimitDecision dec = store.allow(limiter, key, limit, periodSec);
        return new Resp3Value.RespInteger(dec.allowed() ? 1L : 0L);
    }

    private Resp3Value handleStatus(final Command command) {
        if (command.arity() != 4) {
            return Resp3Value.wrongArity("RL.STATUS");
        }
        String limiter = command.argAsString(0);
        String key     = command.argAsString(1);
        long limit     = Long.parseLong(command.argAsString(2));
        long periodSec = Long.parseLong(command.argAsString(3));
        RateLimitDecision dec = store.status(limiter, key, limit, periodSec);

        Map<Resp3Value, Resp3Value> entries = new LinkedHashMap<>();
        entries.put(bulk("allowed"),         new Resp3Value.RespBoolean(dec.allowed()));
        entries.put(bulk("used"),            new Resp3Value.RespInteger(dec.used()));
        entries.put(bulk("limit"),           new Resp3Value.RespInteger(dec.limit()));
        entries.put(bulk("remaining"),       new Resp3Value.RespInteger(dec.remaining()));
        entries.put(bulk("reset_at_millis"), new Resp3Value.RespInteger(dec.resetAtMillis()));
        return new Resp3Value.RespMap(entries);
    }

    private Resp3Value handleReset(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("RL.RESET");
        }
        return Resp3Value.error(
                "ERR RL.RESET not implemented — GCounter is grow-only. "
              + "Wait for the current period to roll over, or use a smaller window.");
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
