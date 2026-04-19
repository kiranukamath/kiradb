package io.kiradb.raft.log;

/**
 * One entry in the Raft log.
 *
 * <p>An entry is immutable once written. The combination of {@code (index, term)} uniquely
 * identifies an entry across the cluster — this is the basis of Raft's Log Matching Property:
 * if two logs agree on an entry's index and term, they agree on everything before it too.
 *
 * <p>For {@link LogEntryType#NOOP} and {@link LogEntryType#DELETE} entries, {@code value}
 * is empty. The {@code expiryMillis} field is only meaningful for {@link LogEntryType#PUT};
 * use {@code -1} to indicate no expiry.
 *
 * @param index        1-based position in the log; monotonically increasing per leader
 * @param term         the term when this entry was created by the leader
 * @param type         the operation type (NOOP / PUT / DELETE)
 * @param key          storage key bytes (empty for NOOP)
 * @param value        storage value bytes (empty for DELETE/NOOP)
 * @param expiryMillis absolute epoch-millis expiry for PUT; {@code -1} = no expiry
 */
public record LogEntry(
        long index,
        long term,
        LogEntryType type,
        byte[] key,
        byte[] value,
        long expiryMillis) {

    private static final byte[] EMPTY = new byte[0];

    /**
     * Create an unindexed PUT entry (index=0, term=0).
     * The leader assigns the real index and term via {@link #withIndexAndTerm}.
     *
     * @param key          storage key
     * @param value        storage value
     * @param expiryMillis expiry epoch-millis, or {@code -1} for no expiry
     * @return unindexed PUT entry
     */
    public static LogEntry put(final byte[] key, final byte[] value, final long expiryMillis) {
        return new LogEntry(0, 0, LogEntryType.PUT, key, value, expiryMillis);
    }

    /**
     * Create an unindexed DELETE entry.
     *
     * @param key the key to delete
     * @return unindexed DELETE entry
     */
    public static LogEntry delete(final byte[] key) {
        return new LogEntry(0, 0, LogEntryType.DELETE, key, EMPTY, -1);
    }

    /**
     * Create a NOOP entry with the given index and term.
     * Used by new leaders to commit entries from previous terms.
     *
     * @param index the log index assigned by the leader
     * @param term  the current leader term
     * @return NOOP entry
     */
    public static LogEntry noop(final long index, final long term) {
        return new LogEntry(index, term, LogEntryType.NOOP, EMPTY, EMPTY, -1);
    }

    /**
     * Return a copy of this entry with the given index and term.
     * Called by the leader when appending a client-proposed entry.
     *
     * @param newIndex the log index
     * @param newTerm  the current leader term
     * @return new entry with index and term set
     */
    public LogEntry withIndexAndTerm(final long newIndex, final long newTerm) {
        return new LogEntry(newIndex, newTerm, type, key, value, expiryMillis);
    }
}
