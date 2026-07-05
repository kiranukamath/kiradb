package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.services.ratelimit.RateLimitDecision;
import io.kiradb.services.ratelimit.RateLimiterStore;
import io.kiradb.services.ratelimit.TokenBucket;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single dispatcher for all {@code RL.*} RESP3 commands.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   RL.ALLOW   limiter key limit periodSec [SLIDING|TOKEN]
 *       — increment + decide; returns 1 (allowed) or 0 (denied)
 *   RL.STATUS  limiter key limit periodSec [SLIDING|TOKEN]
 *       — inspect without consuming; returns RespMap of stats
 *   RL.RESET   limiter key                  — admin reset (NOT YET IMPLEMENTED for SLIDING —
 *                                              GCounter cannot decrement)
 * </pre>
 *
 * <h2>Algorithm selector</h2>
 * The optional trailing argument picks the enforcement algorithm:
 * <ul>
 *   <li>{@code SLIDING} (default, omit to get this) — {@link RateLimiterStore}'s
 *       distributed sliding-window-counter, strict cap per period, GCounter-backed,
 *       correctly enforced across a cluster via CRDT merge.</li>
 *   <li>{@code TOKEN} — {@link TokenBucket}'s single-node token bucket, allows
 *       bursts up to a capacity then paces at a steady refill rate. For this
 *       algorithm, {@code limit} is interpreted as burst capacity and
 *       {@code periodSec} as the number of seconds over which that many tokens
 *       refill (i.e. refill rate = limit / periodSec tokens/second). Cross-node
 *       correctness is NOT implemented for TOKEN — see {@link TokenBucket}'s
 *       class Javadoc; it is single-node-correct only.</li>
 * </ul>
 *
 * <p>Note on RESET: GCounter (SLIDING) is grow-only, so we cannot truly reset a
 * counter without coordinating across nodes to write a tombstone — non-trivial
 * and out of scope for Phase 7. TOKEN buckets are plain local state and could be
 * reset in principle, but no wire command is currently wired up for it either —
 * RL.RESET returns the same explicit "not implemented" error for both algorithms
 * today.
 */
public final class RateLimitHandler implements CommandHandler {

    private final RateLimiterStore slidingWindowStore;
    private final TokenBucket tokenBucketStore;

    /**
     * Construct a rate-limit dispatcher.
     *
     * @param store the sliding-window rate limiter store (SLIDING algorithm, default)
     */
    public RateLimitHandler(final RateLimiterStore store) {
        this(store, new TokenBucket());
    }

    /**
     * Construct a rate-limit dispatcher with an explicit token bucket instance.
     *
     * @param store            the sliding-window rate limiter store (SLIDING algorithm, default)
     * @param tokenBucketStore the token bucket store (TOKEN algorithm)
     */
    public RateLimitHandler(final RateLimiterStore store, final TokenBucket tokenBucketStore) {
        this.slidingWindowStore = store;
        this.tokenBucketStore = tokenBucketStore;
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
        if (command.arity() != 4 && command.arity() != 5) {
            return Resp3Value.wrongArity("RL.ALLOW");
        }
        String limiter = command.argAsString(0);
        String key     = command.argAsString(1);
        long limit     = Long.parseLong(command.argAsString(2));
        long periodSec = Long.parseLong(command.argAsString(3));
        String algorithm = command.arity() == 5 ? command.argAsString(4) : "SLIDING";

        RateLimitDecision dec;
        try {
            dec = decide(algorithm, limiter, key, limit, periodSec, true);
        } catch (IllegalArgumentException e) {
            return Resp3Value.error("ERR " + e.getMessage());
        }
        return new Resp3Value.RespInteger(dec.allowed() ? 1L : 0L);
    }

    private Resp3Value handleStatus(final Command command) {
        if (command.arity() != 4 && command.arity() != 5) {
            return Resp3Value.wrongArity("RL.STATUS");
        }
        String limiter = command.argAsString(0);
        String key     = command.argAsString(1);
        long limit     = Long.parseLong(command.argAsString(2));
        long periodSec = Long.parseLong(command.argAsString(3));
        String algorithm = command.arity() == 5 ? command.argAsString(4) : "SLIDING";

        RateLimitDecision dec;
        try {
            dec = decide(algorithm, limiter, key, limit, periodSec, false);
        } catch (IllegalArgumentException e) {
            return Resp3Value.error("ERR " + e.getMessage());
        }

        Map<Resp3Value, Resp3Value> entries = new LinkedHashMap<>();
        entries.put(bulk("allowed"),         new Resp3Value.RespBoolean(dec.allowed()));
        entries.put(bulk("used"),            new Resp3Value.RespInteger(dec.used()));
        entries.put(bulk("limit"),           new Resp3Value.RespInteger(dec.limit()));
        entries.put(bulk("remaining"),       new Resp3Value.RespInteger(dec.remaining()));
        entries.put(bulk("reset_at_millis"), new Resp3Value.RespInteger(dec.resetAtMillis()));
        entries.put(bulk("algorithm"),       bulk(algorithm.toUpperCase(Locale.ROOT)));
        return new Resp3Value.RespMap(entries);
    }

    /**
     * Dispatch to the chosen algorithm. For TOKEN, {@code limit} is the burst
     * capacity and the refill rate is {@code limit / periodSec} tokens/second.
     */
    private RateLimitDecision decide(
            final String algorithm, final String limiter, final String key,
            final long limit, final long periodSec, final boolean consume) {
        String normalized = algorithm.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SLIDING" -> consume
                    ? slidingWindowStore.allow(limiter, key, limit, periodSec)
                    : slidingWindowStore.status(limiter, key, limit, periodSec);
            case "TOKEN" -> {
                double refillPerSecond = periodSec <= 0 ? 0 : (double) limit / (double) periodSec;
                yield consume
                        ? tokenBucketStore.tryConsume(limiter, key, limit, refillPerSecond)
                        : tokenBucketStore.status(limiter, key, limit, refillPerSecond);
            }
            default -> throw new IllegalArgumentException(
                    "unknown rate limit algorithm '" + algorithm + "' — expected SLIDING or TOKEN");
        };
    }

    private Resp3Value handleReset(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("RL.RESET");
        }
        return Resp3Value.error(
                "ERR RL.RESET not implemented — GCounter is grow-only for SLIDING, "
              + "and no reset wire command is wired up for TOKEN either. "
              + "Wait for the current period to roll over, or use a smaller window.");
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
