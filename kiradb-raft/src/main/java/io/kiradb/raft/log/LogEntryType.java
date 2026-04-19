package io.kiradb.raft.log;

/**
 * The type of operation encoded in a {@link LogEntry}.
 */
public enum LogEntryType {

    /**
     * No-op entry. Appended by a new leader immediately after election to advance the
     * commit index past any uncommitted entries from previous terms.
     * Does not modify the storage engine state machine.
     */
    NOOP,

    /** Write (insert or overwrite) a key-value pair in the storage engine. */
    PUT,

    /** Delete a key from the storage engine (writes a tombstone). */
    DELETE
}
