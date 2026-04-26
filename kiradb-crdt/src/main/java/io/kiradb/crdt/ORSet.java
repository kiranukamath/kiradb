package io.kiradb.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Observed-Remove Set — adds and removes can both happen concurrently on
 * different replicas without losing data, and concurrent re-adds always survive
 * concurrent removes.
 *
 * <h2>The trick: tag every add with a unique id</h2>
 * Each {@code add(x)} produces a fresh {@link UUID}. The element is "in the set"
 * iff at least one of its add tags has not yet been removed.
 *
 * <p>{@code remove(x)} only kills the tags it has <i>currently observed</i>.
 * If another replica concurrently performs {@code add(x)} with a brand-new tag
 * the remover never saw, the element survives — which matches user intent in
 * shopping carts and presence sets ("better resurrect than lose").
 *
 * <h2>Known limitation</h2>
 * Tombstones (removed tags) accumulate over time. Production systems use
 * causal-stability-based GC; for Phase 6 we accept unbounded tombstone growth
 * as a documented limitation.
 */
public final class ORSet {

    private static final byte FORMAT_VERSION = 1;

    /** element -> set of live add-tags. Empty value-set means the element is currently absent. */
    private final ConcurrentMap<String, Set<UUID>> liveAdds;

    /** All add-tags that have been removed. Required to suppress already-deleted tags arriving via merge. */
    private final Set<UUID> tombstones;

    /**
     * Create an empty ORSet.
     */
    public ORSet() {
        this.liveAdds = new ConcurrentHashMap<>();
        this.tombstones = ConcurrentHashMap.newKeySet();
    }

    private ORSet(final Map<String, Set<UUID>> initialAdds, final Set<UUID> initialTombs) {
        this.liveAdds = new ConcurrentHashMap<>();
        for (var e : initialAdds.entrySet()) {
            this.liveAdds.put(e.getKey(), ConcurrentHashMap.newKeySet());
            this.liveAdds.get(e.getKey()).addAll(e.getValue());
        }
        this.tombstones = ConcurrentHashMap.newKeySet();
        this.tombstones.addAll(initialTombs);
    }

    /**
     * Add an element to the set (always succeeds; produces a fresh add-tag).
     *
     * @param element element to add
     */
    public void add(final String element) {
        Objects.requireNonNull(element, "element");
        UUID tag = UUID.randomUUID();
        liveAdds.computeIfAbsent(element, k -> ConcurrentHashMap.newKeySet()).add(tag);
    }

    /**
     * Remove an element from the set. Kills only the add-tags currently observed
     * locally; concurrent adds elsewhere will survive.
     *
     * @param element element to remove
     * @return true if any tag was observed and removed
     */
    public synchronized boolean remove(final String element) {
        Set<UUID> tags = liveAdds.remove(element);
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        tombstones.addAll(tags);
        return true;
    }

    /**
     * @param element element to test
     * @return true if the element has at least one live (non-tombstoned) add-tag
     */
    public boolean contains(final String element) {
        Set<UUID> tags = liveAdds.get(element);
        return tags != null && !tags.isEmpty();
    }

    /**
     * @return snapshot of all elements currently in the set
     */
    public Set<String> elements() {
        Set<String> out = new HashSet<>();
        for (var entry : liveAdds.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * @return number of distinct elements currently in the set
     */
    public int size() {
        return elements().size();
    }

    /**
     * Merge another ORSet into this one (in place).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Union of tombstones — once a tag is removed anywhere, it stays removed.</li>
     *   <li>Union of add-tags per element, then strip out any tag in the merged tombstone set.</li>
     * </ol>
     * Crucially this means an add-tag the local node never saw, but which the remote
     * has tombstoned, becomes correctly absent — and an add-tag generated <i>after</i>
     * a concurrent remove (different tag, never tombstoned) survives.
     *
     * @param other another ORSet
     */
    public synchronized void merge(final ORSet other) {
        // Union tombstones first — these are authoritative deletions.
        tombstones.addAll(other.tombstones);

        // Union live adds, suppressing anything in the merged tombstone set.
        for (var entry : other.liveAdds.entrySet()) {
            String element = entry.getKey();
            Set<UUID> incomingTags = entry.getValue();
            Set<UUID> localTags = liveAdds.computeIfAbsent(
                    element, k -> ConcurrentHashMap.newKeySet());
            for (UUID t : incomingTags) {
                if (!tombstones.contains(t)) {
                    localTags.add(t);
                }
            }
        }

        // Sweep: strip tombstoned tags out of local liveAdds, drop empty entries.
        for (var entry : liveAdds.entrySet()) {
            entry.getValue().removeAll(tombstones);
        }
        liveAdds.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Serialize state.
     *
     * <p>Format: {@code [version:byte] [elementCount:int]
     *           N x ([utf:String] [tagCount:int] N x [uuidMsb:long] [uuidLsb:long])
     *           [tombstoneCount:int] N x ([uuidMsb:long] [uuidLsb:long])}
     *
     * @return wire-format bytes
     */
    public synchronized byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(liveAdds.size());
            for (var entry : liveAdds.entrySet()) {
                out.writeUTF(entry.getKey());
                Set<UUID> tags = entry.getValue();
                out.writeInt(tags.size());
                for (UUID t : tags) {
                    out.writeLong(t.getMostSignificantBits());
                    out.writeLong(t.getLeastSignificantBits());
                }
            }
            out.writeInt(tombstones.size());
            for (UUID t : tombstones) {
                out.writeLong(t.getMostSignificantBits());
                out.writeLong(t.getLeastSignificantBits());
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct from serialized form.
     *
     * @param bytes output of {@link #serialize()}
     * @return reconstructed ORSet
     */
    public static ORSet deserialize(final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown ORSet format version: " + version);
            }
            int elementCount = in.readInt();
            Map<String, Set<UUID>> adds = new HashMap<>(elementCount);
            for (int i = 0; i < elementCount; i++) {
                String element = in.readUTF();
                int tagCount = in.readInt();
                Set<UUID> tags = new HashSet<>(tagCount);
                for (int j = 0; j < tagCount; j++) {
                    long msb = in.readLong();
                    long lsb = in.readLong();
                    tags.add(new UUID(msb, lsb));
                }
                adds.put(element, tags);
            }
            int tombCount = in.readInt();
            Set<UUID> tombs = new HashSet<>(tombCount);
            for (int i = 0; i < tombCount; i++) {
                long msb = in.readLong();
                long lsb = in.readLong();
                tombs.add(new UUID(msb, lsb));
            }
            return new ORSet(adds, tombs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
