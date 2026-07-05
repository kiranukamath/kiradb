package io.kiradb.services.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-node token bucket rate limiter — the "smooth" alternative to
 * {@link RateLimiterStore}'s sliding-window algorithm.
 *
 * <h2>Why this exists alongside the sliding window</h2>
 * The sliding-window algorithm in {@link RateLimiterStore} enforces a strict
 * cap per period but has no notion of "burst tolerance" — it will deny request
 * 101 in a 100-per-minute window even if the caller has been idle for 59 of
 * those seconds. Token bucket instead accumulates unused capacity as "tokens"
 * up to a configurable burst ceiling, letting a client that's been quiet spend
 * a burst all at once, then throttling back to the steady refill rate. This is
 * the algorithm behind AWS API Gateway, most CDN edge limiters, and classic
 * network traffic shaping.
 *
 * <h2>Scoped-down for this phase: single-node-correct only</h2>
 * Per CLAUDE.md's Phase 13 backlog note, cross-node-correct token bucket is the
 * hard version: naively running one bucket per node allows up to
 * {@code numNodes * burst} of allowance briefly after a burst hits multiple
 * nodes simultaneously, and true correctness needs either (a) virtual tokens
 * refilled from a wall-clock origin all nodes can compute deterministically and
 * reconciled via CRDT merge, or (b) routing through a single coordinator.
 * Neither is implemented here. This class is intentionally single-node: each
 * KiraDB process holds its own in-memory bucket state, unreplicated. Behind a
 * single node (or for per-connection / per-process limits where global
 * coordination isn't the point) it is exactly correct. Behind a fleet of N
 * nodes with no shared state, a client hitting different nodes can receive up
 * to N times the configured capacity in the worst case — the same
 * over-allowance shape the sliding-window algorithm already documents, just
 * via a different mechanism (per-node token pools instead of gossip lag).
 * <b>True cross-node correctness remains Phase 13 backlog.</b>
 *
 * <h2>Algorithm</h2>
 * <pre>
 *   tokens(t) = min(burstCapacity, tokens(lastRefill) + (t - lastRefill) * refillRatePerMs)
 *   allow()   = tokens available &gt;= 1 ? consume 1, allow : deny
 * </pre>
 * Refill is computed lazily on each {@link #tryConsume} call from elapsed wall-clock
 * time — there is no background ticking thread. This keeps the implementation simple
 * and exact (no timer drift) at the cost of buckets that are never touched not
 * "visibly" refilling until the next check — which is fine, since nothing observes
 * an untouched bucket anyway.
 *
 * <h2>Thread safety</h2>
 * Per-bucket state updates are done via {@link AtomicReference#updateAndGet}, so
 * concurrent callers on the same {@code (limiter, key)} pair never lose an update
 * and never double-spend a token.
 */
public final class TokenBucket {

    /** Per-{@code (limiter,key)} bucket state, updated atomically. */
    private record State(double tokens, long lastRefillMillis) { }

    private final ConcurrentMap<String, AtomicReference<State>> buckets = new ConcurrentHashMap<>();

    /**
     * Attempt to consume one token from the named bucket.
     *
     * @param limiter        limiter namespace, e.g. {@code "payments-api"}
     * @param key            keyed subject, e.g. {@code "user:123"}
     * @param burstCapacity  maximum tokens the bucket can hold (the burst ceiling)
     * @param refillPerSecond tokens added per second (the steady-state rate)
     * @return the decision: allowed iff a token was available and consumed
     */
    public RateLimitDecision tryConsume(
            final String limiter,
            final String key,
            final long burstCapacity,
            final double refillPerSecond) {
        if (burstCapacity <= 0 || refillPerSecond <= 0) {
            return RateLimitDecision.denied();
        }
        String bucketKey = limiter + ":" + key;
        long now = System.currentTimeMillis();
        double refillPerMilli = refillPerSecond / 1000.0;

        AtomicReference<State> ref = buckets.computeIfAbsent(
                bucketKey, k -> new AtomicReference<>(new State(burstCapacity, now)));

        // updateAndGet's function must be side-effect-free and may be invoked multiple
        // times under contention; it must not itself decide allow/deny since a losing
        // retry must not "phantom consume". We instead capture whether a token was
        // taken via a holder cell inspected after the CAS settles.
        boolean[] consumed = new boolean[1];
        State result = ref.updateAndGet(current -> {
            double elapsedMillis = now - current.lastRefillMillis();
            double refilled = Math.min(
                    burstCapacity, current.tokens() + Math.max(0, elapsedMillis) * refillPerMilli);
            if (refilled >= 1.0) {
                consumed[0] = true;
                return new State(refilled - 1.0, now);
            }
            consumed[0] = false;
            return new State(refilled, now);
        });

        long remaining = (long) Math.floor(result.tokens());
        // Time until at least one more token is available (0 if already >= 1).
        double deficit = Math.max(0.0, 1.0 - result.tokens());
        long resetAtMillis = now + (long) Math.ceil(deficit / refillPerMilli);

        return new RateLimitDecision(consumed[0], burstCapacity - remaining, burstCapacity,
                remaining, resetAtMillis);
    }

    /**
     * Inspect the current token count without consuming one.
     *
     * @param limiter        limiter namespace
     * @param key            keyed subject
     * @param burstCapacity  maximum tokens the bucket can hold
     * @param refillPerSecond tokens added per second
     * @return the decision; {@code allowed} reflects whether a token is currently
     *         available, but none is consumed
     */
    public RateLimitDecision status(
            final String limiter,
            final String key,
            final long burstCapacity,
            final double refillPerSecond) {
        if (burstCapacity <= 0 || refillPerSecond <= 0) {
            return RateLimitDecision.denied();
        }
        String bucketKey = limiter + ":" + key;
        long now = System.currentTimeMillis();
        double refillPerMilli = refillPerSecond / 1000.0;

        AtomicReference<State> ref = buckets.get(bucketKey);
        double tokens = burstCapacity;
        if (ref != null) {
            State current = ref.get();
            double elapsedMillis = now - current.lastRefillMillis();
            tokens = Math.min(burstCapacity, current.tokens() + Math.max(0, elapsedMillis) * refillPerMilli);
        }

        long remaining = (long) Math.floor(tokens);
        double deficit = Math.max(0.0, 1.0 - tokens);
        long resetAtMillis = now + (long) Math.ceil(deficit / refillPerMilli);

        return new RateLimitDecision(tokens >= 1.0, burstCapacity - remaining, burstCapacity,
                remaining, resetAtMillis);
    }

    /**
     * Remove all bucket state (test/admin utility — not exposed over RESP3
     * as a general reset, matching {@code RL.RESET}'s documented limitation
     * for the sliding-window algorithm; unlike GCounter, this one *can* be
     * reset since it's plain in-memory local state, but there's currently no
     * wire command wired up for it).
     */
    public void clear() {
        buckets.clear();
    }
}
