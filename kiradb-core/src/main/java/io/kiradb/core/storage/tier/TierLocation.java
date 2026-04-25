package io.kiradb.core.storage.tier;

/**
 * Where a key currently lives in the storage hierarchy.
 *
 * <ul>
 *   <li>{@link #HOT}  — key is in MemCache (Tier 1). Reads take microseconds.</li>
 *   <li>{@link #WARM} — key is only on SSD/LSM (Tier 2). Reads take milliseconds.</li>
 * </ul>
 */
public enum TierLocation {

    /** Key is in the in-memory MemCache (Tier 1). */
    HOT,

    /** Key is only on SSD/disk (Tier 2 — LsmStorageEngine). */
    WARM
}
