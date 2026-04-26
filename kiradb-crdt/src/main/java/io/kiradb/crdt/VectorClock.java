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

/**
 * Vector clock — captures causality between events without a wall clock.
 *
 * <p>State: a map from node id to a per-node monotonic counter.
 * Three update rules:
 * <ol>
 *   <li>Local event on node N → {@code VC[N]++}</li>
 *   <li>Send message from N → attach a copy of VC</li>
 *   <li>Receive message with VC' on N → element-wise max with VC, then {@code VC[N]++}</li>
 * </ol>
 *
 * <h2>Comparing two vector clocks</h2>
 * <ul>
 *   <li><b>BEFORE</b>: A ≤ B on every key, A &lt; B on at least one — A causally precedes B.</li>
 *   <li><b>AFTER</b>: B ≤ A on every key, B &lt; A on at least one — B causally precedes A.</li>
 *   <li><b>EQUAL</b>: same on every key.</li>
 *   <li><b>CONCURRENT</b>: neither dominates — events with no causal relationship.</li>
 * </ul>
 *
 * <p>This is the underpinning of {@link MVRegister} and other causality-aware CRDTs.
 *
 * <h2>Invariant: only the owning node may increment its own slot</h2>
 * Violating this (e.g., letting clients bump arbitrary slots) caused the famous
 * Riak "vector clock explosion" bug — clocks ballooned to millions of entries.
 * Increment authority must be enforced server-side.
 */
public final class VectorClock {

    private static final byte FORMAT_VERSION = 1;

    /**
     * Pairwise causal ordering between two vector clocks.
     */
    public enum Ordering {
        /** This clock causally precedes the other. */
        BEFORE,
        /** This clock causally follows the other. */
        AFTER,
        /** Both clocks are identical. */
        EQUAL,
        /** Neither dominates — events are concurrent. */
        CONCURRENT
    }

    private final Map<String, Long> clock;

    /**
     * Create an empty vector clock.
     */
    public VectorClock() {
        this.clock = new HashMap<>();
    }

    private VectorClock(final Map<String, Long> initial) {
        this.clock = new HashMap<>(initial);
    }

    /**
     * Create a vector clock from a snapshot map (deep copy).
     *
     * @param snapshot raw {@code nodeId -> counter} map
     * @return new vector clock holding a copy of the given state
     */
    public static VectorClock of(final Map<String, Long> snapshot) {
        return new VectorClock(snapshot);
    }

    /**
     * Increment the given node's slot by 1. Used when {@code nodeId} performs a local event.
     *
     * @param nodeId node performing the event
     */
    public synchronized void increment(final String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        clock.merge(nodeId, 1L, Long::sum);
    }

    /**
     * @param nodeId node identity to inspect
     * @return current value for the given node, or 0 if absent
     */
    public long get(final String nodeId) {
        return clock.getOrDefault(nodeId, 0L);
    }

    /**
     * Element-wise max merge in place. Equivalent to receiving a remote VC and
     * taking the max with the local VC. Does NOT increment afterwards — callers
     * doing receive-then-local-event must call {@link #increment(String)} themselves.
     *
     * @param other vector clock to merge in
     */
    public synchronized void mergeMax(final VectorClock other) {
        for (var entry : other.clock.entrySet()) {
            clock.merge(entry.getKey(), entry.getValue(), Math::max);
        }
    }

    /**
     * @return defensive deep copy
     */
    public synchronized VectorClock copy() {
        return new VectorClock(clock);
    }

    /**
     * @return read-only snapshot of the underlying map
     */
    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(clock));
    }

    /**
     * Compare two vector clocks for causal ordering.
     *
     * @param other other clock
     * @return ordering classification
     */
    public Ordering compare(final VectorClock other) {
        boolean thisLessOrEqual = true;  // every slot of this <= other
        boolean otherLessOrEqual = true; // every slot of other <= this

        Set<String> nodes = new HashSet<>();
        nodes.addAll(this.clock.keySet());
        nodes.addAll(other.clock.keySet());

        for (String node : nodes) {
            long a = this.get(node);
            long b = other.get(node);
            if (a > b) {
                thisLessOrEqual = false;
            }
            if (a < b) {
                otherLessOrEqual = false;
            }
        }

        if (thisLessOrEqual && otherLessOrEqual) {
            return Ordering.EQUAL;
        }
        if (thisLessOrEqual) {
            return Ordering.BEFORE;
        }
        if (otherLessOrEqual) {
            return Ordering.AFTER;
        }
        return Ordering.CONCURRENT;
    }

    /**
     * Serialize this clock. Format: {@code [version:byte] [count:int] N x ([utf:String] [val:long])}.
     *
     * @return wire-format bytes
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(clock.size());
            for (var entry : clock.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
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
     * @return reconstructed vector clock
     */
    public static VectorClock deserialize(final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown VectorClock format version: " + version);
            }
            int count = in.readInt();
            Map<String, Long> initial = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                String node = in.readUTF();
                long val = in.readLong();
                initial.put(node, val);
            }
            return new VectorClock(initial);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VectorClock other)) {
            return false;
        }
        return clock.equals(other.clock);
    }

    @Override
    public int hashCode() {
        return clock.hashCode();
    }

    @Override
    public String toString() {
        return "VC" + clock;
    }
}
