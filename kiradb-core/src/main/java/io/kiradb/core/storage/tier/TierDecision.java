package io.kiradb.core.storage.tier;

/**
 * Decision returned by a {@link TierOrchestrator} for a single key.
 *
 * <ul>
 *   <li>{@link #PROMOTE} — move the key up to a faster tier (WARM → HOT).</li>
 *   <li>{@link #EVICT}   — remove the key from the current tier (HOT → WARM).
 *                          Data is NOT lost — it still lives on Tier 2 (SSD).</li>
 *   <li>{@link #KEEP}    — leave the key exactly where it is.</li>
 * </ul>
 */
public enum TierDecision {

    /** Promote the key to a faster tier. */
    PROMOTE,

    /**
     * Evict the key from the current tier.
     * For a HOT key this means removing it from MemCache (it stays on SSD — no data loss).
     */
    EVICT,

    /** No action required — the key is already in the right tier. */
    KEEP
}
