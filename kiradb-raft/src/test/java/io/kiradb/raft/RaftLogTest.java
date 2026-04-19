package io.kiradb.raft;

import io.kiradb.raft.log.LogEntry;
import io.kiradb.raft.log.RaftLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RaftLog}: append, truncate, replay, and the Log Matching check.
 */
class RaftLogTest {

    @TempDir
    Path dir;

    @Test
    void appendAndRetrieve() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            log.append(entry(2, 1, "SET", "b", "2"));
            log.append(entry(3, 2, "SET", "c", "3"));

            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());
            assertEquals("a", new String(log.get(1).key()));
            assertEquals("b", new String(log.get(2).key()));
            assertEquals("c", new String(log.get(3).key()));
        }
    }

    @Test
    void emptyLogHasZeroIndexAndTerm() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            assertEquals(0, log.lastIndex());
            assertEquals(0, log.lastTerm());
        }
    }

    @Test
    void containsEntryMatchesByIndexAndTerm() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "k", "v"));

            assertTrue(log.containsEntry(0, 0));   // virtual before-log position
            assertTrue(log.containsEntry(1, 1));   // exists with correct term
            assertFalse(log.containsEntry(1, 2));  // wrong term
            assertFalse(log.containsEntry(2, 1));  // index beyond log
        }
    }

    @Test
    void getFromReturnsEntriesFromIndex() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            log.append(entry(2, 1, "SET", "b", "2"));
            log.append(entry(3, 1, "SET", "c", "3"));

            List<LogEntry> from2 = log.getFrom(2);
            assertEquals(2, from2.size());
            assertEquals("b", new String(from2.get(0).key()));
            assertEquals("c", new String(from2.get(1).key()));
        }
    }

    @Test
    void getFromBeyondLastReturnsEmpty() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            assertTrue(log.getFrom(99).isEmpty());
        }
    }

    @Test
    void truncateRemovesTailEntries() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            log.append(entry(2, 1, "SET", "b", "2"));
            log.append(entry(3, 1, "SET", "c", "3"));

            log.truncateFrom(2); // remove entries 2 and 3

            assertEquals(1, log.lastIndex());
            assertEquals("a", new String(log.get(1).key()));
        }
    }

    @Test
    void truncateAndAppendNewEntries() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            log.append(entry(2, 1, "SET", "b", "2")); // will be truncated

            log.truncateFrom(2);
            log.append(entry(2, 2, "SET", "x", "99")); // different term

            assertEquals(2, log.lastIndex());
            assertEquals(2, log.lastTerm());
            assertEquals("x", new String(log.get(2).key()));
        }
    }

    @Test
    void persistsAcrossRestart() throws IOException {
        Path logFile = dir.resolve("raft.log");

        // Session 1: write 3 entries
        try (RaftLog log = new RaftLog(logFile)) {
            log.append(entry(1, 1, "SET", "k1", "v1"));
            log.append(entry(2, 1, "SET", "k2", "v2"));
            log.append(entry(3, 2, "SET", "k3", "v3"));
        }

        // Session 2: reopen — all entries must be replayed
        try (RaftLog log = new RaftLog(logFile)) {
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());
            assertEquals("k1", new String(log.get(1).key()));
            assertEquals("k2", new String(log.get(2).key()));
            assertEquals("k3", new String(log.get(3).key()));
        }
    }

    @Test
    void appendAfterRestart() throws IOException {
        Path logFile = dir.resolve("raft.log");

        try (RaftLog log = new RaftLog(logFile)) {
            log.append(entry(1, 1, "SET", "a", "1"));
        }
        try (RaftLog log = new RaftLog(logFile)) {
            log.append(entry(2, 1, "SET", "b", "2"));
            assertEquals(2, log.lastIndex());
            assertEquals("b", new String(log.get(2).key()));
        }
    }

    @Test
    void termAtReturnsCorrectTerm() throws IOException {
        try (RaftLog log = new RaftLog(dir.resolve("raft.log"))) {
            log.append(entry(1, 1, "SET", "a", "1"));
            log.append(entry(2, 3, "SET", "b", "2")); // term jumps to 3

            assertEquals(0, log.termAt(0)); // virtual before-log
            assertEquals(1, log.termAt(1));
            assertEquals(3, log.termAt(2));
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static LogEntry entry(
            final long index, final long term,
            final String cmd, final String key, final String value) {
        return new LogEntry(index, term,
                io.kiradb.raft.log.LogEntryType.PUT,
                key.getBytes(), value.getBytes(), -1L);
    }
}
