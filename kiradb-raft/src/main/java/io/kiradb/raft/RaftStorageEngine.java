package io.kiradb.raft;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.raft.log.LogEntry;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link StorageEngine} implementation that routes all writes through Raft consensus
 * before applying them to the underlying {@link StorageEngine}.
 *
 * <h2>Write path</h2>
 * <ol>
 *   <li>Client calls {@code put()} or {@code delete()}.</li>
 *   <li>We propose a {@link LogEntry} to the {@link RaftNode}.</li>
 *   <li>The leader replicates it to a majority, then commits it.</li>
 *   <li>The committed entry is applied to the underlying {@code StorageEngine}.</li>
 *   <li>We block until the future completes (or timeout).</li>
 * </ol>
 *
 * <h2>Read path</h2>
 * <p>Reads go directly to the underlying storage engine. This gives "read your own writes"
 * on the leader. For strict linearizable reads you would need a "read index" round-trip
 * through Raft — that is a Phase 4+ extension.
 *
 * <h2>Not the leader?</h2>
 * <p>If this node is not the leader, writes throw {@link NotLeaderException} with the
 * known leader's ID so callers can redirect.
 */
public final class RaftStorageEngine implements StorageEngine {

    private static final long WRITE_TIMEOUT_MS = 5_000;

    private final RaftNode      raftNode;
    private final StorageEngine local;

    /**
     * Create a Raft-backed storage engine.
     *
     * @param raftNode the Raft consensus node
     * @param local    the underlying local storage engine (reads + state machine applies)
     */
    public RaftStorageEngine(final RaftNode raftNode, final StorageEngine local) {
        this.raftNode = raftNode;
        this.local    = local;
    }

    // ── Writes go through Raft ────────────────────────────────────────────────

    @Override
    public void put(final byte[] key, final byte[] value) {
        propose(LogEntry.put(key, value, -1L));
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long expiryMillis) {
        propose(LogEntry.put(key, value, expiryMillis));
    }

    @Override
    public boolean delete(final byte[] key) {
        boolean existed = local.exists(key);
        propose(LogEntry.delete(key));
        return existed;
    }

    @Override
    public boolean expire(final byte[] key, final long expiryMillis) {
        Optional<byte[]> current = local.get(key);
        if (current.isEmpty()) {
            return false;
        }
        propose(LogEntry.put(key, current.get(), expiryMillis));
        return true;
    }

    // ── Reads go directly to local storage ───────────────────────────────────

    @Override
    public Optional<byte[]> get(final byte[] key) {
        return local.get(key);
    }

    @Override
    public boolean exists(final byte[] key) {
        return local.exists(key);
    }

    @Override
    public long ttlMillis(final byte[] key) {
        return local.ttlMillis(key);
    }

    @Override
    public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
        return local.scan(startKey, endKey);
    }

    @Override
    public void close() {
        raftNode.close();
        local.close();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Propose an entry to Raft and block until it is committed.
     * Throws {@link NotLeaderException} if this node is not the leader.
     * Throws {@link RuntimeException} wrapping any timeout or interruption.
     */
    private void propose(final LogEntry entry) {
        CompletableFuture<Void> future = raftNode.propose(entry);
        try {
            future.get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException("Raft write failed: " + e.getCause().getMessage(),
                    e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Raft write timed out after " + WRITE_TIMEOUT_MS + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Raft write interrupted");
        }
    }
}
