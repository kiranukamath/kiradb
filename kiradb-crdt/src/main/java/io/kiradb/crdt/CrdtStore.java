package io.kiradb.crdt;

import io.kiradb.core.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Owner of named CRDT instances, with read-through and write-through to a {@link StorageEngine}.
 *
 * <p>Each CRDT is identified by a name (the user-facing key) and a type. Names live
 * in disjoint namespaces per type — a GCounter named "votes" is unrelated to an
 * ORSet named "votes". On disk, CRDT bytes are stored under reserved key prefixes
 * so they coexist with ordinary RESP3 keys without collision.
 *
 * <h2>Concurrency</h2>
 * CRDT instances are individually thread-safe (their {@code merge}/{@code set}
 * methods synchronize internally). The {@code computeIfAbsent} on the in-memory
 * cache makes load-or-create atomic. Persistence happens after each mutation
 * via {@link #persist(String, byte[])}.
 *
 * <h2>NodeId stability</h2>
 * The {@code localNodeId} passed to the constructor must be stable across restarts.
 * A drifting node id splits one logical node into two, doubling counters and
 * resurrecting elements. Reuse the Raft node id from {@code kiradb-raft}.
 */
public final class CrdtStore {

    private static final String GCOUNTER_PREFIX = "crdt:g:";
    private static final String PNCOUNTER_PREFIX = "crdt:pn:";
    private static final String LWW_PREFIX = "crdt:lww:";
    private static final String MV_PREFIX = "crdt:mv:";
    private static final String ORSET_PREFIX = "crdt:or:";

    private final StorageEngine storage;
    private final String localNodeId;

    private final ConcurrentMap<String, GCounter> gCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PNCounter> pnCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LWWRegister> lwwRegisters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MVRegister> mvRegisters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ORSet> orSets = new ConcurrentHashMap<>();

    /**
     * Create a CRDT store backed by the given storage engine.
     *
     * @param storage     storage engine for persistence
     * @param localNodeId stable node identity (must match across restarts)
     */
    public CrdtStore(final StorageEngine storage, final String localNodeId) {
        this.storage = storage;
        this.localNodeId = localNodeId;
    }

    // --- GCounter -----------------------------------------------------------

    /**
     * Get or lazily load a GCounter with the given name.
     *
     * @param name CRDT name
     * @return the existing or newly created counter
     */
    public GCounter gCounter(final String name) {
        return gCounters.computeIfAbsent(name, n -> {
            byte[] key = (GCOUNTER_PREFIX + n).getBytes(StandardCharsets.UTF_8);
            Optional<byte[]> bytes = storage.get(key);
            return bytes.map(b -> GCounter.deserialize(localNodeId, b))
                    .orElseGet(() -> new GCounter(localNodeId));
        });
    }

    /**
     * Increment a GCounter by {@code delta}, persist, and return the new value.
     *
     * @param name  CRDT name
     * @param delta amount to add (must be positive)
     * @return value after increment
     */
    public long gCounterIncrement(final String name, final long delta) {
        GCounter c = gCounter(name);
        synchronized (c) {
            c.increment(delta);
            persistGCounter(name, c);
            return c.value();
        }
    }

    /**
     * @param name CRDT name
     * @return current GCounter value (0 if absent)
     */
    public long gCounterValue(final String name) {
        return gCounter(name).value();
    }

    private void persistGCounter(final String name, final GCounter c) {
        byte[] key = (GCOUNTER_PREFIX + name).getBytes(StandardCharsets.UTF_8);
        storage.put(key, c.serialize());
    }

    // --- PNCounter ----------------------------------------------------------

    /**
     * Get or lazily load a PNCounter.
     *
     * @param name CRDT name
     * @return PNCounter instance
     */
    public PNCounter pnCounter(final String name) {
        return pnCounters.computeIfAbsent(name, n -> {
            byte[] key = (PNCOUNTER_PREFIX + n).getBytes(StandardCharsets.UTF_8);
            Optional<byte[]> bytes = storage.get(key);
            return bytes.map(b -> PNCounter.deserialize(localNodeId, b))
                    .orElseGet(() -> new PNCounter(localNodeId));
        });
    }

    /**
     * Apply a signed delta and persist.
     *
     * @param name  CRDT name
     * @param delta signed delta
     * @return value after applying delta
     */
    public long pnCounterAdd(final String name, final long delta) {
        PNCounter c = pnCounter(name);
        synchronized (c) {
            c.add(delta);
            byte[] key = (PNCOUNTER_PREFIX + name).getBytes(StandardCharsets.UTF_8);
            storage.put(key, c.serialize());
            return c.value();
        }
    }

    /**
     * @param name CRDT name
     * @return signed value (0 if absent)
     */
    public long pnCounterValue(final String name) {
        return pnCounter(name).value();
    }

    // --- LWWRegister --------------------------------------------------------

    /**
     * Get or lazily load an LWWRegister.
     *
     * @param name CRDT name
     * @return LWWRegister instance
     */
    public LWWRegister lwwRegister(final String name) {
        return lwwRegisters.computeIfAbsent(name, n -> {
            byte[] key = (LWW_PREFIX + n).getBytes(StandardCharsets.UTF_8);
            Optional<byte[]> bytes = storage.get(key);
            return bytes.map(b -> LWWRegister.deserialize(localNodeId, b))
                    .orElseGet(() -> new LWWRegister(localNodeId));
        });
    }

    /**
     * Set the value of an LWWRegister and persist.
     *
     * @param name  CRDT name
     * @param value new value (may be null to clear)
     */
    public void lwwSet(final String name, final byte[] value) {
        LWWRegister r = lwwRegister(name);
        synchronized (r) {
            r.set(value);
            byte[] key = (LWW_PREFIX + name).getBytes(StandardCharsets.UTF_8);
            storage.put(key, r.serialize());
        }
    }

    /**
     * @param name CRDT name
     * @return current value (or null)
     */
    public byte[] lwwGet(final String name) {
        return lwwRegister(name).get();
    }

    // --- MVRegister ---------------------------------------------------------

    /**
     * Get or lazily load an MVRegister.
     *
     * @param name CRDT name
     * @return MVRegister instance
     */
    public MVRegister mvRegister(final String name) {
        return mvRegisters.computeIfAbsent(name, n -> {
            byte[] key = (MV_PREFIX + n).getBytes(StandardCharsets.UTF_8);
            Optional<byte[]> bytes = storage.get(key);
            return bytes.map(b -> MVRegister.deserialize(localNodeId, b))
                    .orElseGet(() -> new MVRegister(localNodeId));
        });
    }

    // --- ORSet --------------------------------------------------------------

    /**
     * Get or lazily load an ORSet.
     *
     * @param name CRDT name
     * @return ORSet instance
     */
    public ORSet orSet(final String name) {
        return orSets.computeIfAbsent(name, n -> {
            byte[] key = (ORSET_PREFIX + n).getBytes(StandardCharsets.UTF_8);
            Optional<byte[]> bytes = storage.get(key);
            return bytes.map(ORSet::deserialize).orElseGet(ORSet::new);
        });
    }

    /**
     * Add an element to a named ORSet and persist.
     *
     * @param name    CRDT name
     * @param element element to add
     */
    public void orSetAdd(final String name, final String element) {
        ORSet s = orSet(name);
        synchronized (s) {
            s.add(element);
            byte[] key = (ORSET_PREFIX + name).getBytes(StandardCharsets.UTF_8);
            storage.put(key, s.serialize());
        }
    }

    /**
     * Remove an element from a named ORSet and persist.
     *
     * @param name    CRDT name
     * @param element element to remove
     * @return true if at least one tag was removed
     */
    public boolean orSetRemove(final String name, final String element) {
        ORSet s = orSet(name);
        synchronized (s) {
            boolean removed = s.remove(element);
            byte[] key = (ORSET_PREFIX + name).getBytes(StandardCharsets.UTF_8);
            storage.put(key, s.serialize());
            return removed;
        }
    }

    // --- Generic merge helper for replication-incoming bytes -----------------

    /**
     * Merge incoming gossip bytes for a GCounter and persist.
     *
     * @param name  CRDT name
     * @param bytes serialized GCounter from a peer
     */
    public void mergeGCounter(final String name, final byte[] bytes) {
        GCounter incoming = GCounter.deserialize(localNodeId, bytes);
        GCounter local = gCounter(name);
        synchronized (local) {
            local.merge(incoming);
            persistGCounter(name, local);
        }
    }

    /**
     * @return the local node id this store stamps onto writes
     */
    public String localNodeId() {
        return localNodeId;
    }
}
