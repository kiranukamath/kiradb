package io.kiradb.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Positive-Negative counter — supports both increments and decrements.
 *
 * <p>Built from two {@link GCounter}s: one for additions ({@code P}), one for
 * subtractions ({@code N}). Value is {@code P.value() - N.value()}.
 *
 * <h2>Why decompose into two grow-only counters?</h2>
 * GCounter is provably conflict-free. A single counter that allows both
 * increment and decrement is not — concurrent {@code +1} and {@code -1} from
 * different replicas would not converge under max-merge. By restricting each
 * underlying counter to monotonic growth, the merge math stays trivially safe.
 * This "decompose what you can't do into two things you can do" pattern
 * recurs throughout CRDT design.
 */
public final class PNCounter {

    private static final byte FORMAT_VERSION = 1;

    private final String localNodeId;
    private final GCounter positive;
    private final GCounter negative;

    /**
     * Create a fresh PNCounter owned by the given node.
     *
     * @param localNodeId stable node identity
     */
    public PNCounter(final String localNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.positive = new GCounter(localNodeId);
        this.negative = new GCounter(localNodeId);
    }

    private PNCounter(final String localNodeId, final GCounter p, final GCounter n) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.positive = p;
        this.negative = n;
    }

    /**
     * Apply a signed delta. Positive routes to the P counter; negative to N.
     * Zero is a no-op.
     *
     * @param delta signed change to apply
     */
    public void add(final long delta) {
        if (delta > 0) {
            positive.increment(delta);
        } else if (delta < 0) {
            negative.increment(-delta);
        }
    }

    /**
     * Increment by 1.
     */
    public void increment() {
        positive.increment();
    }

    /**
     * Decrement by 1.
     */
    public void decrement() {
        negative.increment();
    }

    /**
     * @return current signed value: {@code P - N}
     */
    public long value() {
        return positive.value() - negative.value();
    }

    /**
     * Merge another PNCounter into this one (in place).
     *
     * @param other another PNCounter
     */
    public synchronized void merge(final PNCounter other) {
        positive.merge(other.positive);
        negative.merge(other.negative);
    }

    /**
     * Serialize state. Format: {@code [version:byte] [P bytes len:int] [P bytes] [N bytes len:int] [N bytes]}.
     *
     * @return wire-format bytes
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            byte[] pBytes = positive.serialize();
            byte[] nBytes = negative.serialize();
            out.writeInt(pBytes.length);
            out.write(pBytes);
            out.writeInt(nBytes.length);
            out.write(nBytes);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct from serialized form.
     *
     * @param localNodeId node identity to bind this instance to
     * @param bytes       output of {@link #serialize()}
     * @return reconstructed PNCounter
     */
    public static PNCounter deserialize(final String localNodeId, final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown PNCounter format version: " + version);
            }
            int pLen = in.readInt();
            byte[] pBytes = in.readNBytes(pLen);
            int nLen = in.readInt();
            byte[] nBytes = in.readNBytes(nLen);
            return new PNCounter(
                    localNodeId,
                    GCounter.deserialize(localNodeId, pBytes),
                    GCounter.deserialize(localNodeId, nBytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "PNCounter{node=" + localNodeId
                + ", value=" + value()
                + ", P=" + positive.value()
                + ", N=" + negative.value() + "}";
    }
}
