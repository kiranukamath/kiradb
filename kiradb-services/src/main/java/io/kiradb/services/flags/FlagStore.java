package io.kiradb.services.flags;

import io.kiradb.crdt.CrdtStore;
import io.kiradb.crdt.LWWRegister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owner of all feature flags. Wraps a {@link CrdtStore} so flag state is replicated
 * by the same CRDT machinery as everything else in KiraDB.
 *
 * <h2>Storage layout</h2>
 * For each flag named {@code F}:
 * <ul>
 *   <li><b>State:</b> an {@link LWWRegister} at logical key {@code "flag:F"}.
 *       Value is a serialized {@link FeatureFlag} record.</li>
 *   <li><b>Metrics:</b> four {@link io.kiradb.crdt.GCounter}s — one per
 *       {(impressions, conversions) × (enabled cohort, disabled cohort)}.
 *       The bandit (Phase 14) consumes these.</li>
 *   <li><b>Index:</b> a tracking set of all known flag names so
 *       {@code FLAG.LIST} can enumerate them. Maintained in-memory; rebuilt on
 *       restart by replaying {@code listFlags} which lazy-loads existing names
 *       from disk via the CrdtStore as they're first read. (For Phase 7 a
 *       persistent index is overkill; we keep flag names in memory and rely on
 *       admin re-registration on restart in the unlikely case a server was
 *       restarted before any client touched a flag — see Phase 13 backlog
 *       entry to add a persistent index.)</li>
 * </ul>
 *
 * <h2>Why LWW for flag state, GCounter for metrics</h2>
 * Flag state is updated rarely by humans; "last admin wins" is the right merge.
 * Metrics are bumped from many concurrent requests; GCounter handles that with
 * no coordination. Two consistency models, one feature, picked per data shape.
 */
public final class FlagStore {

    private static final String FLAG_PREFIX = "flag:";
    private static final String IMPRESSIONS_ENABLED  = ":impressions:enabled";
    private static final String IMPRESSIONS_DISABLED = ":impressions:disabled";
    private static final String CONVERSIONS_ENABLED  = ":conversions:enabled";
    private static final String CONVERSIONS_DISABLED = ":conversions:disabled";

    private final CrdtStore crdtStore;

    /** In-memory index of known flag names, populated on every set/get. */
    private final ConcurrentMap<String, Boolean> knownFlags = new ConcurrentHashMap<>();

    /**
     * Create a flag store backed by the given CRDT store.
     *
     * @param crdtStore CRDT store providing LWW + GCounter + persistence
     */
    public FlagStore(final CrdtStore crdtStore) {
        this.crdtStore = Objects.requireNonNull(crdtStore, "crdtStore");
    }

    // --- admin operations ---------------------------------------------------

    /**
     * Set or replace a flag's state.
     *
     * @param flag the new flag state (replaces any existing)
     */
    public void set(final FeatureFlag flag) {
        Objects.requireNonNull(flag, "flag");
        crdtStore.lwwSet(flagKey(flag.name()), flag.serialize());
        knownFlags.put(flag.name(), Boolean.TRUE);
    }

    /**
     * Mark a flag as killed (forced OFF for everyone, regardless of rollout).
     * No-op if the flag does not exist.
     *
     * @param name flag name
     * @return true if the flag existed and was killed
     */
    public boolean kill(final String name) {
        Optional<FeatureFlag> existing = get(name);
        if (existing.isEmpty()) {
            return false;
        }
        set(existing.get().asKilled());
        return true;
    }

    /**
     * Restore a killed flag (rollout retained from before the kill).
     *
     * @param name flag name
     * @return true if the flag existed and was unkilled
     */
    public boolean unkill(final String name) {
        Optional<FeatureFlag> existing = get(name);
        if (existing.isEmpty()) {
            return false;
        }
        set(existing.get().asUnkilled());
        return true;
    }

    /**
     * @param name flag name
     * @return the flag's current state, or empty if the flag has never been set
     */
    public Optional<FeatureFlag> get(final String name) {
        byte[] bytes = crdtStore.lwwGet(flagKey(name));
        if (bytes == null) {
            return Optional.empty();
        }
        knownFlags.put(name, Boolean.TRUE);
        return Optional.of(FeatureFlag.deserialize(bytes));
    }

    /**
     * @return names of all flags this store currently knows about (in-memory index)
     */
    public List<String> listFlags() {
        List<String> names = new ArrayList<>(knownFlags.keySet());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // --- evaluation + metrics -----------------------------------------------

    /**
     * Evaluate the flag for a user, recording an impression for the appropriate cohort.
     *
     * @param name   flag name
     * @param userId user identifier (used for sticky bucketing)
     * @return true if the flag is ON for this user (false if absent or killed)
     */
    public boolean isEnabled(final String name, final String userId) {
        Optional<FeatureFlag> flagOpt = get(name);
        if (flagOpt.isEmpty()) {
            // Unknown flag: treat as disabled. Don't record metrics — there's no flag to attribute to.
            return false;
        }
        FeatureFlag flag = flagOpt.get();
        boolean enabled = !flag.killed()
                && FlagBucketing.isEnabled(name, userId, flag.rolloutPercent());

        crdtStore.gCounterIncrement(
                impressionsKey(name, enabled), 1L);
        return enabled;
    }

    /**
     * Record a conversion for the cohort the user landed in for this flag.
     * Convention: the caller calls this after observing the desired outcome
     * (purchase, click, etc.).
     *
     * @param name   flag name
     * @param userId user identifier
     */
    public void recordConversion(final String name, final String userId) {
        Optional<FeatureFlag> flagOpt = get(name);
        if (flagOpt.isEmpty()) {
            return;  // unknown flag, nothing to attribute
        }
        FeatureFlag flag = flagOpt.get();
        // Use the same bucket the user got at FLAG.GET time — sticky.
        // Note: the killed flag still attributes to a cohort based on rollout for analysis purposes.
        boolean wouldBeEnabled = FlagBucketing.isEnabled(
                name, userId, flag.rolloutPercent());
        crdtStore.gCounterIncrement(conversionsKey(name, wouldBeEnabled), 1L);
    }

    /**
     * @param name flag name
     * @return per-cohort impressions and conversions
     */
    public FlagStats stats(final String name) {
        return new FlagStats(
                crdtStore.gCounterValue(impressionsKey(name, true)),
                crdtStore.gCounterValue(impressionsKey(name, false)),
                crdtStore.gCounterValue(conversionsKey(name, true)),
                crdtStore.gCounterValue(conversionsKey(name, false)));
    }

    // --- helpers ------------------------------------------------------------

    private static String flagKey(final String name) {
        return FLAG_PREFIX + name;
    }

    private static String impressionsKey(final String name, final boolean enabled) {
        return FLAG_PREFIX + name + (enabled ? IMPRESSIONS_ENABLED : IMPRESSIONS_DISABLED);
    }

    private static String conversionsKey(final String name, final boolean enabled) {
        return FLAG_PREFIX + name + (enabled ? CONVERSIONS_ENABLED : CONVERSIONS_DISABLED);
    }

    /**
     * @return a defensive snapshot of known flag names (for tests)
     */
    Set<String> knownFlagNames() {
        return new HashSet<>(knownFlags.keySet());
    }
}
