package io.kiradb.core.storage.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log — append-only crash-recovery journal.
 *
 * <h2>Why WAL exists</h2>
 * <p>Before any write is acknowledged to a client, it must be recorded here.
 * If the server crashes mid-write, the WAL is replayed on restart to reconstruct
 * the MemTable. No acknowledged write is ever lost.
 *
 * <h2>Append-only design</h2>
 * <p>Every entry is appended to the end of the file — no seeking, no overwriting.
 * Sequential writes are the fastest possible disk operation. A new entry takes
 * exactly one syscall (or a buffered flush when the buffer fills).
 *
 * <h2>CRC32 checksums</h2>
 * <p>Each entry ends with a CRC32 checksum of all preceding bytes in that entry.
 * During replay, if a checksum fails, we stop at that point and log a warning.
 * This handles the case where the server crashed mid-write, leaving a partial
 * entry at the end of the file.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #append} is called under the write lock held by {@code LsmStorageEngine}.
 * No internal synchronization needed.
 */
public final class Wal implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(Wal.class);

    private final Path walPath;
    private final FileOutputStream fileOut;
    private final DataOutputStream out;

    /**
     * Open (or create) a WAL file at the given path.
     *
     * @param walPath path to the WAL file
     * @throws IOException if the file cannot be opened
     */
    public Wal(final Path walPath) throws IOException {
        this.walPath = walPath;
        // Keep a direct reference to FileOutputStream so we can call getFD().sync()
        this.fileOut = new FileOutputStream(walPath.toFile(), true); // append mode
        this.out = new DataOutputStream(new BufferedOutputStream(fileOut, 64 * 1024));
        LOG.info("WAL opened: {}", walPath);
    }

    /**
     * Append one entry to the WAL and fsync to durable storage.
     *
     * <p>The caller (LsmStorageEngine) holds a write lock, so this method
     * does not need to be synchronized itself.
     *
     * @param entry the entry to append
     */
    public void append(final WalEntry entry) {
        try {
            byte[] serialized = serialize(entry);
            out.write(serialized);
            out.flush();
            // fsync — ensure bytes are on physical media, not just OS buffer cache.
            // Without this, a power failure could lose the last few writes even
            // though we "wrote" them. Cost: ~1ms per fsync on SSD.
            fileOut.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("WAL append failed — this is fatal", e);
        }
    }

    /**
     * Read all valid entries from a WAL file.
     *
     * <p>Stops at the first entry with an invalid CRC32 (typically a partial
     * write from a crash). Entries before the corruption are returned intact.
     *
     * @param walPath path to the WAL file to replay
     * @return list of valid entries in write order
     * @throws IOException if the file cannot be read
     */
    public static List<WalEntry> replay(final Path walPath) throws IOException {
        List<WalEntry> entries = new ArrayList<>();

        if (!walPath.toFile().exists() || walPath.toFile().length() == 0) {
            LOG.info("WAL is empty or absent — nothing to replay: {}", walPath);
            return entries;
        }

        LOG.info("Replaying WAL: {}", walPath);
        try (DataInputStream in = new DataInputStream(new FileInputStream(walPath.toFile()))) {
            while (true) {
                try {
                    WalEntry entry = deserialize(in);
                    if (entry == null) {
                        break; // clean EOF
                    }
                    entries.add(entry);
                } catch (CrcMismatchException e) {
                    LOG.warn("WAL CRC mismatch at entry {} — truncated write detected, stopping replay",
                            entries.size());
                    break;
                } catch (EOFException e) {
                    break; // normal end of file
                }
            }
        }

        LOG.info("WAL replay complete: {} entries recovered", entries.size());
        return entries;
    }

    /**
     * Serialize one entry to the binary wire format.
     * The CRC32 in the entry record is ignored — we recompute it from the fields.
     */
    private byte[] serialize(final WalEntry entry) throws IOException {
        CRC32 crc = new CRC32();

        // Build the payload (everything before the checksum)
        java.io.ByteArrayOutputStream payloadBuf = new java.io.ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBuf);

        payload.writeByte(entry.op().code());
        payload.writeLong(entry.timestamp());
        payload.writeLong(entry.expiryMillis());
        payload.writeInt(entry.key().length);
        payload.write(entry.key());

        byte[] valueBytes = entry.value() == null ? new byte[0] : entry.value();
        payload.writeInt(valueBytes.length);
        payload.write(valueBytes);
        payload.flush();

        byte[] payloadBytes = payloadBuf.toByteArray();
        crc.update(payloadBytes);
        int checksum = (int) crc.getValue();

        // Final entry = payload + 4-byte CRC32
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(payloadBytes.length + 4);
        out.write(payloadBytes);
        DataOutputStream wrapper = new DataOutputStream(out);
        wrapper.writeInt(checksum);
        wrapper.flush();

        return out.toByteArray();
    }

    /**
     * Deserialize one entry from the stream.
     *
     * @return the entry, or null on clean EOF
     * @throws CrcMismatchException if the checksum does not match
     * @throws EOFException         if the stream ends mid-entry
     */
    private static WalEntry deserialize(final DataInputStream in)
            throws IOException {

        // Read the op byte — -1 means clean EOF, no partial entry
        int opByte = in.read();
        if (opByte == -1) {
            return null; // clean EOF
        }

        // Now read the full entry, tracking bytes for CRC
        CRC32 crc = new CRC32();
        java.io.ByteArrayOutputStream payloadBuf = new java.io.ByteArrayOutputStream();

        byte op = (byte) opByte;
        payloadBuf.write(op);

        long timestamp = in.readLong();
        writeToBuffer(payloadBuf, longToBytes(timestamp));

        long expiryMillis = in.readLong();
        writeToBuffer(payloadBuf, longToBytes(expiryMillis));

        int keyLen = in.readInt();
        writeToBuffer(payloadBuf, intToBytes(keyLen));

        byte[] key = new byte[keyLen];
        in.readFully(key);
        payloadBuf.write(key);

        int valueLen = in.readInt();
        writeToBuffer(payloadBuf, intToBytes(valueLen));

        byte[] value = new byte[valueLen];
        if (valueLen > 0) {
            in.readFully(value);
            payloadBuf.write(value);
        }

        int storedCrc = in.readInt();

        crc.update(payloadBuf.toByteArray());
        int computedCrc = (int) crc.getValue();

        if (storedCrc != computedCrc) {
            throw new CrcMismatchException(storedCrc, computedCrc);
        }

        WalEntry.Op walOp = WalEntry.Op.fromCode(op);
        return new WalEntry(walOp, timestamp, expiryMillis, key,
                valueLen > 0 ? value : null, storedCrc);
    }

    private static void writeToBuffer(
            final java.io.ByteArrayOutputStream buf, final byte[] bytes) {
        buf.write(bytes, 0, bytes.length);
    }

    private static byte[] longToBytes(final long val) {
        return new byte[]{
            (byte) (val >>> 56), (byte) (val >>> 48),
            (byte) (val >>> 40), (byte) (val >>> 32),
            (byte) (val >>> 24), (byte) (val >>> 16),
            (byte) (val >>> 8),  (byte) val
        };
    }

    private static byte[] intToBytes(final int val) {
        return new byte[]{
            (byte) (val >>> 24), (byte) (val >>> 16),
            (byte) (val >>> 8),  (byte) val
        };
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
        LOG.info("WAL closed: {}", walPath);
    }

    /** Thrown when a WAL entry's CRC32 does not match the stored checksum. */
    static final class CrcMismatchException extends IOException {

        /**
         * Construct a CRC mismatch exception.
         *
         * @param stored   the checksum stored in the file
         * @param computed the checksum computed from the entry bytes
         */
        CrcMismatchException(final int stored, final int computed) {
            super(String.format("WAL CRC mismatch: stored=0x%08X computed=0x%08X", stored, computed));
        }
    }
}
