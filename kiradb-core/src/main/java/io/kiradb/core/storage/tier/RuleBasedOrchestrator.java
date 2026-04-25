package io.kiradb.core.storage.tier;

/**
 * Rule-based {@link TierOrchestrator} that uses static score thresholds.
 *
 * <h2>Rules</h2>
 * <pre>
 *   WARM key, score &gt;= promoteThreshold  → PROMOTE (move into MemCache)
 *   HOT  key, score &lt;  evictThreshold    → EVICT   (remove from MemCache; stays on SSD)
 *   otherwise                             → KEEP
 * </pre>
 *
 * <h2>Default thresholds</h2>
 * <ul>
 *   <li>{@code promoteThreshold = 10.0} — key accessed ~10+ times recently</li>
 *   <li>{@code evictThreshold   =  2.0} — key barely accessed (1–2 times, long ago)</li>
 * </ul>
 *
 * <p>These values are configurable at construction time.  The AI orchestrator
 * (future phase) will replace the static thresholds with learned predictions.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable after construction — thread-safe with no synchronization needed.
 */
public final class RuleBasedOrchestrator implements TierOrchestrator {

    /** Default score at which a WARM key gets promoted to HOT. */
    public static final double DEFAULT_PROMOTE_THRESHOLD = 10.0;

    /** Default score below which a HOT key gets evicted from MemCache. */
    public static final double DEFAULT_EVICT_THRESHOLD = 2.0;

    private final double promoteThreshold;
    private final double evictThreshold;

    /**
     * Create an orchestrator with default thresholds.
     */
    public RuleBasedOrchestrator() {
        this(DEFAULT_PROMOTE_THRESHOLD, DEFAULT_EVICT_THRESHOLD);
    }

    /**
     * Create an orchestrator with custom thresholds.
     *
     * @param promoteThreshold score at or above which a WARM key is promoted
     * @param evictThreshold   score below which a HOT key is evicted
     */
    public RuleBasedOrchestrator(final double promoteThreshold, final double evictThreshold) {
        if (promoteThreshold <= evictThreshold) {
            throw new IllegalArgumentException(
                    "promoteThreshold must be > evictThreshold to avoid oscillation");
        }
        this.promoteThreshold = promoteThreshold;
        this.evictThreshold   = evictThreshold;
    }

    @Override
    public TierDecision evaluate(final byte[] key,
                                 final AccessStats stats,
                                 final TierLocation currentTier) {
        double score = stats.decayedScore();
        return switch (currentTier) {
            case WARM -> score >= promoteThreshold ? TierDecision.PROMOTE : TierDecision.KEEP;
            case HOT  -> score < evictThreshold    ? TierDecision.EVICT   : TierDecision.KEEP;
        };
    }

    /**
     * Return the promotion threshold.
     *
     * @return promote threshold
     */
    public double promoteThreshold() {
        return promoteThreshold;
    }

    /**
     * Return the eviction threshold.
     *
     * @return evict threshold
     */
    public double evictThreshold() {
        return evictThreshold;
    }
}
