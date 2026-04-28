package io.kiradb.services.ratelimit;

import io.kiradb.crdt.CrdtStore;

import java.util.Objects;

/**
 * Distributed rate limiter backed by Phase 6's GCounter CRDT.
 *
 * <h2>The mechanism</h2>
 * For each {@code (limiterName, key, time-bucket)} tuple we keep a GCounter.
 * Each request increments the local node's slot in the current bucket. The
 * "global" count is the sum of all slots — exactly the GCounter value, which
 * converges across nodes via merge.
 *
 * <p>Time bucket index is {@code floor(nowMillis / periodMillis)}. We keep
 * the current bucket plus the previous one. The estimated usage is a weighted
 * blend:
 *
 * <pre>
 *   elapsedInCurrent  = nowMillis mod periodMillis
 *   weightOfPrevious  = (periodMillis - elapsedInCurrent) / periodMillis
 *   estimatedUsage    = previousBucket * weightOfPrevious + currentBucket
 * </pre>
 *
 * This is the standard sliding-window-counter algorithm used by Cloudflare,
 * Stripe, RedisCell, and others. It gives smooth boundaries without the
 * "two windows of allowance back-to-back" bug that fixed-window has.
 *
 * <h2>The eventual-consistency trade-off</h2>
 * During gossip lag, two nodes can each independently see "below limit" and
 * both allow a request, briefly exceeding the global limit by up to one per
 * concurrent decider. For "100 per minute", typical over-allowance is ~1%.
 * For very small limits this becomes proportionally larger — for a 5-per-minute
 * limit across 10 nodes, brief over-allowance up to 50% is possible. Pick
 * larger limits or accept the trade.
 *
 * <h2>Strict mode (deferred)</h2>
 * "Exactly never over the limit" requires routing every check through a Raft
 * leader. Plumbed in a future phase; tracked in Phase 13 backlog.
 */
public final class RateLimiterStore {

    private static final String KEY_PREFIX = "rl:";

    private final CrdtStore crdtStore;

    /**
     * Construct a rate limiter backed by the given CRDT store.
     *
     * @param crdtStore CRDT store providing GCounter persistence and merge
     */
    public RateLimiterStore(final CrdtStore crdtStore) {
        this.crdtStore = Objects.requireNonNull(crdtStore, "crdtStore");
    }

    /**
     * Check and consume a quota slot in one call.
     *
     * @param limiter   limiter namespace (e.g., {@code "payments-api"})
     * @param key       the keyed subject (e.g., {@code "user:123"})
     * @param limit     max requests in {@code periodSeconds}
     * @param periodSeconds the window size, in seconds
     * @return the decision, including allow/deny + used/remaining
     */
    public RateLimitDecision allow(
            final String limiter,
            final String key,
            final long limit,
            final long periodSeconds) {
        if (limit <= 0 || periodSeconds <= 0) {
            return RateLimitDecision.denied();
        }
        long now = System.currentTimeMillis();
        long periodMillis = periodSeconds * 1000;
        long currentBucket = now / periodMillis;
        long previousBucket = currentBucket - 1;

        // Increment the local slot first, then read back to compute usage. Ordering
        // matters: a denied request still consumes a slot in the counter — same
        // behaviour as INCR-then-check in Redis. Documented; doesn't compound.
        long currentValue = crdtStore.gCounterIncrement(
                bucketKey(limiter, key, currentBucket), 1L);
        long previousValue = crdtStore.gCounterValue(
                bucketKey(limiter, key, previousBucket));

        long estimatedUsage = computeSlidingUsage(now, periodMillis, previousValue, currentValue);
        boolean allowed = estimatedUsage <= limit;
        long remaining = Math.max(0, limit - estimatedUsage);
        long resetAtMillis = (currentBucket + 1) * periodMillis;

        return new RateLimitDecision(allowed, estimatedUsage, limit, remaining, resetAtMillis);
    }

    /**
     * Inspect current usage without incrementing.
     *
     * @param limiter   limiter namespace
     * @param key       keyed subject
     * @param limit     limit to compare against
     * @param periodSeconds window size
     * @return the decision; {@code allowed} reflects the current state, no consumption
     */
    public RateLimitDecision status(
            final String limiter,
            final String key,
            final long limit,
            final long periodSeconds) {
        if (limit <= 0 || periodSeconds <= 0) {
            return RateLimitDecision.denied();
        }
        long now = System.currentTimeMillis();
        long periodMillis = periodSeconds * 1000;
        long currentBucket = now / periodMillis;
        long previousBucket = currentBucket - 1;

        long currentValue = crdtStore.gCounterValue(bucketKey(limiter, key, currentBucket));
        long previousValue = crdtStore.gCounterValue(bucketKey(limiter, key, previousBucket));

        long estimatedUsage = computeSlidingUsage(now, periodMillis, previousValue, currentValue);
        boolean allowed = estimatedUsage < limit;  // status = "would another request be allowed?"
        long remaining = Math.max(0, limit - estimatedUsage);
        long resetAtMillis = (currentBucket + 1) * periodMillis;

        return new RateLimitDecision(allowed, estimatedUsage, limit, remaining, resetAtMillis);
    }

    /**
     * Merge incoming counter state for a specific bucket. Used by the gossip
     * pipeline (Phase 13) and by tests that simulate cross-node state.
     *
     * @param limiter        limiter namespace
     * @param key            keyed subject
     * @param bucketIndex    explicit bucket index to merge into
     * @param remoteState    serialized GCounter bytes from a peer
     */
    public void mergeBucket(
            final String limiter, final String key,
            final long bucketIndex, final byte[] remoteState) {
        crdtStore.mergeGCounter(bucketKey(limiter, key, bucketIndex), remoteState);
    }

    /**
     * @param limiter limiter namespace
     * @param key     keyed subject
     * @param periodSeconds window size
     * @return the current bucket index
     */
    public long currentBucketIndex(final String limiter, final String key, final long periodSeconds) {
        return System.currentTimeMillis() / (periodSeconds * 1000);
    }

    /**
     * Read raw current-bucket counter value (for tests + gossip serializers).
     *
     * @param limiter       limiter namespace
     * @param key           keyed subject
     * @param bucketIndex   explicit bucket index
     * @return raw GCounter value for that bucket
     */
    public long rawBucketValue(final String limiter, final String key, final long bucketIndex) {
        return crdtStore.gCounterValue(bucketKey(limiter, key, bucketIndex));
    }

    private static long computeSlidingUsage(
            final long nowMillis, final long periodMillis,
            final long previousValue, final long currentValue) {
        long elapsedInCurrent = nowMillis % periodMillis;
        // weightOfPrevious is in [0.0, 1.0]: full at bucket boundary, 0 at end of bucket.
        double weightOfPrevious =
                (double) (periodMillis - elapsedInCurrent) / (double) periodMillis;
        return Math.round(previousValue * weightOfPrevious) + currentValue;
    }

    private static String bucketKey(final String limiter, final String key, final long bucket) {
        return KEY_PREFIX + limiter + ":" + key + ":" + bucket;
    }
}
