package io.kiradb.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Multi-Value register — surfaces concurrent writes instead of silently picking one.
 *
 * <p>State is a set of {@code (value, vectorClock)} pairs. On each write, the
 * local VC is bumped and any existing pair causally dominated by the new VC is
 * dropped. On merge, the union is taken and any pair causally dominated by some
 * other pair in the union is dropped. What survives are the "maximal elements
 * of the partial order" — concurrent writes that nobody has yet seen and resolved.
 *
 * <p>This is the original Dynamo-paper semantics (2007). The shopping-cart
 * use case: phone adds "socks" while disconnected, tablet adds "shirt" while
 * disconnected — both pairs survive, the cart SDK applies a domain rule
 * (union for carts), writes back a single value with a VC that dominates both,
 * and the conflict is resolved.
 *
 * <p>Note: AWS DynamoDB the product hides this and uses LWW by default.
 * "DynamoDB-style consistency" usually refers to the original paper, not the AWS service.
 */
public final class MVRegister {

    private static final byte FORMAT_VERSION = 1;

    /**
     * One concurrent value, stamped with the vector clock under which it was written.
     *
     * @param value the value bytes
     * @param vc    vector clock at the moment of the write
     */
    public record Entry(byte[] value, VectorClock vc) { }

    private final String localNodeId;
    private final VectorClock localVc;
    private List<Entry> entries;

    /**
     * Create an empty multi-value register owned by the given node.
     *
     * @param localNodeId stable node identity
     */
    public MVRegister(final String localNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.localVc = new VectorClock();
        this.entries = new ArrayList<>();
    }

    private MVRegister(
            final String localNodeId,
            final VectorClock localVc,
            final List<Entry> entries) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.localVc = localVc;
        this.entries = entries;
    }

    /**
     * Write a new value. Bumps the local VC, drops everything it dominates,
     * and stores the new (value, VC) pair.
     *
     * @param newValue value to write
     */
    public synchronized void set(final byte[] newValue) {
        localVc.increment(localNodeId);
        VectorClock writeVc = localVc.copy();

        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            // Drop entries strictly dominated by the new write.
            if (e.vc.compare(writeVc) != VectorClock.Ordering.BEFORE) {
                kept.add(e);
            }
        }
        kept.add(new Entry(newValue, writeVc));
        this.entries = kept;
    }

    /**
     * @return all currently surviving values (size 1 in the absence of conflict, &gt;1 otherwise)
     */
    public synchronized List<byte[]> values() {
        List<byte[]> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.value);
        }
        return out;
    }

    /**
     * @return read-only view of the underlying (value, VC) pairs
     */
    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Merge another MVRegister into this one (in place).
     * Keeps the maximal pairs of the combined partial order.
     *
     * @param other another MVRegister
     */
    public synchronized void merge(final MVRegister other) {
        // Bring our local VC up to date so subsequent writes dominate everything we've seen.
        localVc.mergeMax(other.localVc);

        // Combine pairs and drop those dominated by any other.
        List<Entry> combined = new ArrayList<>(this.entries);
        combined.addAll(other.entries());

        List<Entry> kept = new ArrayList<>();
        for (int i = 0; i < combined.size(); i++) {
            Entry candidate = combined.get(i);
            boolean dominated = false;
            for (int j = 0; j < combined.size(); j++) {
                if (i == j) {
                    continue;
                }
                if (candidate.vc.compare(combined.get(j).vc) == VectorClock.Ordering.BEFORE) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated && !containsSamePair(kept, candidate)) {
                kept.add(candidate);
            }
        }
        this.entries = kept;
    }

    /** Avoid duplicate-VC duplicates in the survivor set after a merge of identical states. */
    private static boolean containsSamePair(final List<Entry> list, final Entry e) {
        for (Entry other : list) {
            if (other.vc.equals(e.vc) && java.util.Arrays.equals(other.value, e.value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Serialize state.
     *
     * <p>Format: {@code [version:byte] [vcLen:int] [vcBytes] [entryCount:int]
     *               N x ([valLen:int] [valBytes] [vcLen:int] [vcBytes])}
     *
     * @return wire-format bytes
     */
    public synchronized byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            byte[] vcBytes = localVc.serialize();
            out.writeInt(vcBytes.length);
            out.write(vcBytes);
            out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeInt(e.value.length);
                out.write(e.value);
                byte[] eVcBytes = e.vc.serialize();
                out.writeInt(eVcBytes.length);
                out.write(eVcBytes);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct from serialized form.
     *
     * @param localNodeId node identity
     * @param bytes       output of {@link #serialize()}
     * @return reconstructed MVRegister
     */
    public static MVRegister deserialize(final String localNodeId, final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown MVRegister format version: " + version);
            }
            int vcLen = in.readInt();
            byte[] vcBytes = in.readNBytes(vcLen);
            VectorClock vc = VectorClock.deserialize(vcBytes);

            int count = in.readInt();
            List<Entry> entryList = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int valLen = in.readInt();
                byte[] val = in.readNBytes(valLen);
                int eVcLen = in.readInt();
                byte[] eVcBytes = in.readNBytes(eVcLen);
                entryList.add(new Entry(val, VectorClock.deserialize(eVcBytes)));
            }
            return new MVRegister(localNodeId, vc, entryList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return number of distinct concurrent values currently held
     */
    public int size() {
        return entries.size();
    }

    @Override
    public synchronized String toString() {
        Set<String> vals = new HashSet<>();
        for (Entry e : entries) {
            vals.add(new String(e.value));
        }
        return "MVRegister{node=" + localNodeId + ", localVc=" + localVc + ", values=" + vals + "}";
    }
}
