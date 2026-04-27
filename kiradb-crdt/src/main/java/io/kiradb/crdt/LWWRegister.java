package io.kiradb.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Last-Write-Wins register — a single value with a (timestamp, nodeId) version.
 *
 * <p>On merge, the entry with the higher timestamp wins. If timestamps tie, the
 * lexicographically larger {@code nodeId} wins (deterministic tiebreak so all
 * replicas converge to the same answer).
 *
 * <h2>The honest disclaimer</h2>
 * Wall clocks lie. NTP drift, leap seconds, VM pauses, suspended laptops — any
 * of these can cause a write to be silently discarded by a "newer" write that
 * really happened earlier. For data where loss is unacceptable, use
 * {@link MVRegister} instead and let the application resolve.
 *
 * <p>LWW is appropriate for: feature flag values, last-known-good config,
 * "current value" of monitoring metrics. It is NOT appropriate for: shopping
 * carts, audit logs, financial state.
 */
public final class LWWRegister {

    private static final byte FORMAT_VERSION = 1;

    private final String localNodeId;
    private byte[] value;
    private long timestampMillis;
    private String writerNodeId;

    /**
     * Create an empty register owned by the given node.
     *
     * @param localNodeId node identity used as the tiebreaker on equal timestamps
     */
    public LWWRegister(final String localNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.value = null;
        this.timestampMillis = Long.MIN_VALUE;
        this.writerNodeId = "";
    }

    private LWWRegister(
            final String localNodeId,
            final byte[] value,
            final long timestampMillis,
            final String writerNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.value = value;
        this.timestampMillis = timestampMillis;
        this.writerNodeId = writerNodeId;
    }

    /**
     * Write a new value, stamped with the current wall clock and local node id.
     *
     * @param newValue the new value (may be null to clear)
     */
    public void set(final byte[] newValue) {
        set(newValue, System.currentTimeMillis());
    }

    /**
     * Write a new value with an explicit timestamp (useful for tests and when
     * the caller has a hybrid logical clock).
     *
     * @param newValue the new value (may be null to clear)
     * @param timestamp epoch-millis tagged on this write
     */
    public synchronized void set(final byte[] newValue, final long timestamp) {
        if (winsOver(timestamp, localNodeId, this.timestampMillis, this.writerNodeId)) {
            this.value = newValue;
            this.timestampMillis = timestamp;
            this.writerNodeId = localNodeId;
        }
    }

    /**
     * @return current value, or null if never set
     */
    public synchronized byte[] get() {
        return value;
    }

    /**
     * @return timestamp of the current value
     */
    public synchronized long timestamp() {
        return timestampMillis;
    }

    /**
     * @return node id that wrote the current value
     */
    public synchronized String writer() {
        return writerNodeId;
    }

    /**
     * Merge another register's state into this one (in place).
     *
     * @param other another LWWRegister
     */
    public synchronized void merge(final LWWRegister other) {
        if (winsOver(other.timestampMillis, other.writerNodeId,
                this.timestampMillis, this.writerNodeId)) {
            this.value = other.value;
            this.timestampMillis = other.timestampMillis;
            this.writerNodeId = other.writerNodeId;
        }
    }

    /**
     * Decide whether (tA, idA) wins over (tB, idB).
     * Higher timestamp wins; ties broken by lexicographically larger node id.
     */
    private static boolean winsOver(
            final long tA, final String idA,
            final long tB, final String idB) {
        if (tA > tB) {
            return true;
        }
        if (tA < tB) {
            return false;
        }
        return idA.compareTo(idB) > 0;
    }

    /**
     * Serialize state.
     *
     * <p>Format: {@code [version:byte] [tsMillis:long] [writerId:utf] [hasValue:byte] (if 1: [len:int] [bytes])}.
     *
     * @return wire-format bytes
     */
    public synchronized byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeLong(timestampMillis);
            out.writeUTF(writerNodeId);
            if (value == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1);
                out.writeInt(value.length);
                out.write(value);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct from serialized form.
     *
     * @param localNodeId local node identity
     * @param bytes       output of {@link #serialize()}
     * @return reconstructed register
     */
    public static LWWRegister deserialize(final String localNodeId, final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown LWWRegister format version: " + version);
            }
            long ts = in.readLong();
            String writer = in.readUTF();
            byte hasValue = in.readByte();
            byte[] val = null;
            if (hasValue == 1) {
                int len = in.readInt();
                val = in.readNBytes(len);
            }
            return new LWWRegister(localNodeId, val, ts, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
