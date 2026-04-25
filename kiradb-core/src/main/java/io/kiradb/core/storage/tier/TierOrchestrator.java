package io.kiradb.core.storage.tier;

/**
 * Strategy that decides what tier action to take for a key.
 *
 * <p>This is the extension point for AI/ML-driven tier management.
 * Two implementations exist (or will exist):
 * <ol>
 *   <li>{@link RuleBasedOrchestrator} — static score thresholds (Phase 5).</li>
 *   <li>{@code AiOrchestrator} — learns per-key access patterns and predicts
 *       future reads (future phase).</li>
 * </ol>
 *
 * <p>{@link TieredStorageEngine} and {@link TierManager} depend <em>only</em> on
 * this interface.  Swapping the AI orchestrator in requires changing one constructor
 * argument — nothing else.
 *
 * <h2>Contract</h2>
 * Implementations must be thread-safe.  {@link TierManager} calls
 * {@link #evaluate} from a single background thread, but future phases may
 * call it concurrently from multiple threads.
 */
public interface TierOrchestrator {

    /**
     * Decide what to do with a key given its current access statistics and tier.
     *
     * @param key         the key (never null)
     * @param stats       point-in-time access statistics for the key
     * @param currentTier where the key currently lives
     * @return the action to take — {@link TierDecision#PROMOTE}, {@link TierDecision#EVICT},
     *         or {@link TierDecision#KEEP}
     */
    TierDecision evaluate(byte[] key, AccessStats stats, TierLocation currentTier);
}
