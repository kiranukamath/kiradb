package io.kiradb.raft.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only, durable Raft log.
 *
 * <h2>On-disk format (per entry)</h2>
 * <pre>
 *   [8 bytes] index
 *   [8 bytes] term
 *   [1 byte ] type code  (0=NOOP, 1=PUT, 2=DELETE)
 *   [4 bytes] keyLen
 *   [keyLen ] key bytes
 *   [4 bytes] valueLen   (-1 = empty/null)
 *   [valueLen] value bytes (omitted when valueLen == -1)
 *   [8 bytes] expiryMillis
 *   [4 bytes] CRC32 of all preceding bytes in this entry
 * </pre>
 *
 * <h2>Truncation</h2>
 * <p>A follower may need to truncate its log when the leader sends conflicting entries
 * (i.e. same index, different term). The log tracks byte offsets for each entry so it can
 * truncate the underlying file with {@link FileChannel#truncate} — no full rewrite needed.
 *
 * <h2>Thread safety</h2>
 * <p>All calls happen under the {@code RaftNode}'s {@link java.util.concurrent.locks.ReentrantLock}.
 * The log itself does not add internal synchronization.
 */
public final class RaftLog implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RaftLog.class);

    private static final byte TYPE_NOOP   = 0;
    private static final byte TYPE_PUT    = 1;
    private static final byte TYPE_DELETE = 2;

    private final Path logFile;
    private final List<LogEntry> entries  = new ArrayList<>();
    /** Byte offset of each entry's first byte in the file. Parallel to {@code entries}. */
    private final List<Long>     offsets  = new ArrayList<>();
    private long currentOffset = 0;

    private FileOutputStream fileOut;
    private DataOutputStream dataOut;

    /**
     * Open (or create) the Raft log at the given path.
     * Replays any existing entries on startup.
     *
     * @param logFile path to the log file
     * @throws IOException if the file cannot be read or created
     */
    public RaftLog(final Path logFile) throws IOException {
        this.logFile = logFile;
        Files.createDirectories(logFile.getParent());
        replayExisting();
        openForAppend();
        LOG.info("RaftLog ready: {} entries in {}", entries.size(), logFile.getFileName());
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Append one entry to the end of the log and fsync to disk.
     *
     * @param entry the entry to append (index and term must already be set)
     * @throws IOException if the disk write fails
     */
    public void append(final LogEntry entry) throws IOException {
        offsets.add(currentOffset);
        byte[] bytes = serialize(entry);
        currentOffset += bytes.length;
        dataOut.write(bytes);
        dataOut.flush();
        fileOut.getFD().sync();
        entries.add(entry);
    }

    /**
     * Return the entry at the given 1-based index.
     *
     * @param index 1-based log index
     * @return the entry
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public LogEntry get(final long index) {
        return entries.get((int) (index - 1));
    }

    /**
     * Return all entries starting at the given 1-based index (inclusive).
     * Returns an empty list if {@code fromIndex > lastIndex()}.
     *
     * @param fromIndex 1-based starting index
     * @return unmodifiable view of entries from {@code fromIndex} onwards
     */
    public List<LogEntry> getFrom(final long fromIndex) {
        if (fromIndex > lastIndex()) {
            return Collections.emptyList();
        }
        int start = (int) (fromIndex - 1);
        return Collections.unmodifiableList(entries.subList(start, entries.size()));
    }

    /**
     * Return the index of the last entry, or {@code 0} if the log is empty.
     *
     * @return last log index
     */
    public long lastIndex() {
        return entries.size();
    }

    /**
     * Return the term of the last entry, or {@code 0} if the log is empty.
     *
     * @return last log term
     */
    public long lastTerm() {
        if (entries.isEmpty()) {
            return 0;
        }
        return entries.get(entries.size() - 1).term();
    }

    /**
     * Return the term of the entry at the given 1-based index, or {@code 0} for index 0
     * (the virtual "before the log" position).
     *
     * @param index 1-based log index; 0 returns term 0
     * @return the term at that index
     * @throws IndexOutOfBoundsException if index > lastIndex()
     */
    public long termAt(final long index) {
        if (index <= 0) {
            return 0;
        }
        return entries.get((int) (index - 1)).term();
    }

    /**
     * Remove all entries from {@code fromIndex} onwards (inclusive) and truncate the file.
     * Used by followers to correct their log when the leader sends conflicting entries.
     *
     * @param fromIndex 1-based index of the first entry to remove
     * @throws IOException if the file truncation fails
     */
    public void truncateFrom(final long fromIndex) throws IOException {
        if (fromIndex > lastIndex()) {
            return;
        }
        int listIdx = (int) (fromIndex - 1);
        long truncateAt = offsets.get(listIdx);

        entries.subList(listIdx, entries.size()).clear();
        offsets.subList(listIdx, offsets.size()).clear();
        currentOffset = truncateAt;

        // Close, truncate file, reopen in append mode
        dataOut.close();
        fileOut.close();
        try (FileChannel fc = FileChannel.open(logFile, StandardOpenOption.WRITE)) {
            fc.truncate(truncateAt);
        }
        openForAppend();
        LOG.info("RaftLog truncated from index {} (file now {} bytes)", fromIndex, truncateAt);
    }

    /**
     * True if the log contains an entry at {@code index} with the given {@code term}.
     * Used for the Log Matching check in AppendEntries.
     *
     * @param index log index (0 = always true, represents "before the log")
     * @param term  expected term
     * @return true if the entry exists with the given term
     */
    public boolean containsEntry(final long index, final long term) {
        if (index == 0) {
            return true; // virtual initial entry
        }
        if (index > lastIndex()) {
            return false;
        }
        return termAt(index) == term;
    }

    @Override
    public void close() throws IOException {
        dataOut.flush();
        dataOut.close();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void openForAppend() throws IOException {
        fileOut = new FileOutputStream(logFile.toFile(), true); // append mode
        dataOut = new DataOutputStream(new BufferedOutputStream(fileOut, 64 * 1024));
    }

    /**
     * Replay the existing log file into memory on startup.
     * Stops at the first entry with a bad CRC (truncated write from crash).
     */
    private void replayExisting() throws IOException {
        if (!logFile.toFile().exists() || logFile.toFile().length() == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(logFile.toFile())))) {
            while (true) {
                long startOffset = currentOffset;
                try {
                    LogEntry entry = readEntry(in);
                    offsets.add(startOffset);
                    entries.add(entry);
                    currentOffset = startOffset + entryByteSize(entry);
                } catch (CrcMismatchException e) {
                    LOG.warn("RaftLog CRC mismatch at byte {} — truncated entry, stopping replay",
                            currentOffset);
                    break;
                } catch (EOFException e) {
                    break;
                }
            }
        }
    }

    /**
     * Serialize one entry to bytes (payload + CRC32).
     */
    private static byte[] serialize(final LogEntry entry) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);

        out.writeLong(entry.index());
        out.writeLong(entry.term());
        out.writeByte(typeCode(entry.type()));
        out.writeInt(entry.key().length);
        out.write(entry.key());

        byte[] val = entry.value();
        if (val == null || val.length == 0) {
            out.writeInt(-1);
        } else {
            out.writeInt(val.length);
            out.write(val);
        }
        out.writeLong(entry.expiryMillis());
        out.flush();

        byte[] payload = buf.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        java.io.ByteArrayOutputStream full = new java.io.ByteArrayOutputStream(payload.length + 4);
        full.write(payload);
        DataOutputStream wrapper = new DataOutputStream(full);
        wrapper.writeInt((int) crc.getValue());
        wrapper.flush();
        return full.toByteArray();
    }

    /** Read one entry from the stream, verifying its CRC. */
    private static LogEntry readEntry(final DataInputStream in)
            throws IOException {
        java.io.ByteArrayOutputStream payloadBuf = new java.io.ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBuf);

        long index = in.readLong();   payload.writeLong(index);
        long term  = in.readLong();   payload.writeLong(term);
        byte typeByte = in.readByte(); payload.writeByte(typeByte);

        int keyLen = in.readInt();    payload.writeInt(keyLen);
        byte[] key = new byte[keyLen];
        in.readFully(key);            payload.write(key);

        int valueLen = in.readInt();  payload.writeInt(valueLen);
        byte[] value = new byte[0];
        if (valueLen > 0) {
            value = new byte[valueLen];
            in.readFully(value);
            payload.write(value);
        }

        long expiry = in.readLong();  payload.writeLong(expiry);
        payload.flush();

        int storedCrc = in.readInt();
        CRC32 crc = new CRC32();
        crc.update(payloadBuf.toByteArray());
        if (storedCrc != (int) crc.getValue()) {
            throw new CrcMismatchException();
        }

        return new LogEntry(index, term, typeFromCode(typeByte), key, value, expiry);
    }

    /** Approximate byte size of a serialized entry (for offset tracking during replay). */
    private static long entryByteSize(final LogEntry e) {
        long size = 8 + 8 + 1 + 4 + e.key().length + 4 + 8 + 4; // fixed fields
        if (e.value() != null && e.value().length > 0) {
            size += e.value().length;
        }
        return size;
    }

    private static byte typeCode(final LogEntryType type) {
        return switch (type) {
            case NOOP   -> TYPE_NOOP;
            case PUT    -> TYPE_PUT;
            case DELETE -> TYPE_DELETE;
        };
    }

    private static LogEntryType typeFromCode(final byte code) {
        return switch (code) {
            case TYPE_NOOP   -> LogEntryType.NOOP;
            case TYPE_PUT    -> LogEntryType.PUT;
            case TYPE_DELETE -> LogEntryType.DELETE;
            default -> throw new IllegalArgumentException("Unknown log entry type: " + code);
        };
    }

    /** Internal exception for CRC failures during replay. */
    private static final class CrcMismatchException extends IOException {
        CrcMismatchException() {
            super("RaftLog CRC mismatch");
        }
    }
}
