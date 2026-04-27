package io.kiradb.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Grow-only counter — a state-based CRDT.
 *
 * <p>State is a map from node id to a per-node monotonic counter. The counter's
 * value is the sum of all per-node counters. Each node only ever increments its
 * own slot; merging takes the element-wise max of the two maps.
 *
 * <h2>Why max-merge is safe</h2>
 * Only node {@code N} ever writes slot {@code N}. So when two replicas disagree
 * on slot {@code N}, the higher value strictly contains more information about
 * what happened on N — taking the max is non-destructive.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Only counts up. Use {@link PNCounter} if you need decrements.</li>
 *   <li>Per-replica state grows with the number of nodes that have ever
 *       incremented (not the number of increments). Bounded by cluster size.</li>
 * </ul>
 */
public final class GCounter {

    private static final byte FORMAT_VERSION = 1;

    private final String localNodeId;
    private final ConcurrentMap<String, Long> slots;

    /**
     * Create a fresh counter owned by the given node.
     *
     * @param localNodeId stable node identity (must match across restarts)
     */
    public GCounter(final String localNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.slots = new ConcurrentHashMap<>();
    }

    private GCounter(final String localNodeId, final Map<String, Long> initial) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.slots = new ConcurrentHashMap<>(initial);
    }

    /**
     * Increment the local node's slot by 1.
     */
    public void increment() {
        increment(1L);
    }

    /**
     * Increment the local node's slot by {@code delta} (must be positive).
     *
     * @param delta amount to add (must be {@code > 0})
     * @throws IllegalArgumentException if {@code delta <= 0}
     */
    public void increment(final long delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("GCounter delta must be positive, was " + delta);
        }
        slots.merge(localNodeId, delta, Long::sum);
    }

    /**
     * Return the counter's current value: the sum of all per-node slots.
     *
     * @return total count
     */
    public long value() {
        long sum = 0;
        for (long v : slots.values()) {
            sum += v;
        }
        return sum;
    }

    /**
     * Merge another GCounter's state into this one (in place). Element-wise max.
     *
     * <p>Commutative, associative, idempotent — the three CRDT properties.
     *
     * @param other another counter (may be from a different node)
     */
    public synchronized void merge(final GCounter other) {
        for (var entry : other.slots.entrySet()) {
            slots.merge(entry.getKey(), entry.getValue(), Math::max);
        }
    }

    /**
     * @return read-only snapshot of the per-node slot map
     */
    public Map<String, Long> slotsSnapshot() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(slots));
    }

    /**
     * Serialize this counter's state for gossip / disk persistence.
     *
     * <p>Format: {@code [version:byte] [count:int] N x ([utf:String] [val:long])}
     *
     * @return wire-format bytes
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(slots.size());
            for (var entry : slots.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct a counter from its serialized state, owned locally by {@code localNodeId}.
     *
     * @param localNodeId   node identity to bind this instance to
     * @param bytes         wire-format bytes from {@link #serialize()}
     * @return reconstructed GCounter
     */
    public static GCounter deserialize(final String localNodeId, final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown GCounter format version: " + version);
            }
            int count = in.readInt();
            Map<String, Long> initial = new java.util.HashMap<>(count);
            for (int i = 0; i < count; i++) {
                String node = in.readUTF();
                long val = in.readLong();
                initial.put(node, val);
            }
            return new GCounter(localNodeId, initial);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "GCounter{node=" + localNodeId + ", value=" + value() + ", slots=" + slots + "}";
    }
}
