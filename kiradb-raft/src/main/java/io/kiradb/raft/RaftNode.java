package io.kiradb.raft;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.raft.log.LogEntry;
import io.kiradb.raft.log.RaftLog;
import io.kiradb.raft.rpc.RaftRpc;
import io.kiradb.raft.rpc.RaftRpcClient;
import io.kiradb.raft.rpc.RaftRpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core Raft consensus state machine.
 *
 * <h2>Roles and transitions</h2>
 * <pre>
 *   FOLLOWER  → CANDIDATE  election timeout fires (no heartbeat from leader)
 *   CANDIDATE → LEADER     wins majority of votes
 *   CANDIDATE → FOLLOWER   sees higher term or another leader wins
 *   LEADER    → FOLLOWER   sees higher term in any incoming RPC
 * </pre>
 *
 * <h2>Write path (client → committed)</h2>
 * <ol>
 *   <li>Client calls {@link #propose(LogEntry)} — must be on the leader.</li>
 *   <li>Entry appended to Raft log with current term.</li>
 *   <li>Entry replicated to followers via {@link RaftRpc.AppendEntries}.</li>
 *   <li>When majority acknowledge, commitIndex advances.</li>
 *   <li>Committed entry applied to {@link StorageEngine}.</li>
 *   <li>{@link CompletableFuture} returned to caller completes.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>A single {@link ReentrantLock} guards all mutable state. The election timer and
 * heartbeat run on virtual threads and acquire the lock for state reads/writes.
 */
public final class RaftNode implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);

    private final RaftConfig      config;
    private final RaftLog         log;
    private final StorageEngine   stateMachine;
    private final RaftRpcServer   rpcServer;
    private final Map<String, RaftRpcClient> peers = new HashMap<>();

    // ── Raft persistent state (survive restarts) ──────────────────────────────
    private long   currentTerm = 0;
    private String votedFor    = null;   // nodeId voted for in currentTerm

    // ── Raft volatile state ───────────────────────────────────────────────────
    private RaftRole role        = RaftRole.FOLLOWER;
    private long     commitIndex = 0;
    private long     lastApplied = 0;
    private String   leaderId    = null;

    // ── Leader volatile state (reset on each election) ───────────────────────
    /** nextIndex[peerId] — next log index to send to that peer. */
    private final Map<String, Long> nextIndex  = new HashMap<>();
    /** matchIndex[peerId] — highest log index confirmed replicated on that peer. */
    private final Map<String, Long> matchIndex = new HashMap<>();

    // ── Synchronization ───────────────────────────────────────────────────────
    private final ReentrantLock lock      = new ReentrantLock();
    private final Condition     committed = lock.newCondition();

    /** Futures waiting for a log entry at a given index to be committed. */
    private final Map<Long, CompletableFuture<Void>> pendingCommits = new ConcurrentHashMap<>();

    // ── Timer threads ─────────────────────────────────────────────────────────
    private volatile long   lastHeartbeatMs = System.currentTimeMillis();
    private Thread electionTimerThread;
    private Thread heartbeatThread;

    /**
     * Create and start a Raft node.
     *
     * @param config       node and cluster configuration
     * @param dataDir      directory for the Raft log file
     * @param stateMachine the storage engine to apply committed entries to
     * @throws IOException if the Raft log file cannot be opened
     */
    public RaftNode(
            final RaftConfig config,
            final Path dataDir,
            final StorageEngine stateMachine) throws IOException {
        this.config       = config;
        this.stateMachine = stateMachine;
        this.log          = new RaftLog(dataDir.resolve("raft.log"));
        this.rpcServer    = new RaftRpcServer(config.raftPort(), this::handleRpc);

        // Build one RaftRpcClient per peer
        for (Map.Entry<String, String> e : config.peerAddresses().entrySet()) {
            peers.put(e.getKey(), new RaftRpcClient(e.getKey(), e.getValue()));
        }
    }

    /**
     * Start the RPC server and the election timer.
     *
     * @throws IOException if the RPC server cannot bind to the port
     */
    public void start() throws IOException {
        rpcServer.start();
        startElectionTimer();
        LOG.info("RaftNode {} started (role={}, term={})", config.nodeId(), role, currentTerm);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Propose a new entry to the cluster. Only succeeds on the leader.
     *
     * <p>The returned future completes when the entry has been committed by a majority
     * of the cluster and applied to the state machine.
     *
     * @param entry the log entry to propose (index and term will be assigned here)
     * @return future that completes when the entry is committed
     * @throws NotLeaderException if this node is not the leader
     */
    public CompletableFuture<Void> propose(final LogEntry entry) {
        lock.lock();
        try {
            if (role != RaftRole.LEADER) {
                throw new NotLeaderException(leaderId);
            }
            long index = log.lastIndex() + 1;
            LogEntry stamped = entry.withIndexAndTerm(index, currentTerm);
            try {
                log.append(stamped);
            } catch (IOException e) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            pendingCommits.put(index, future);
            // Trigger immediate replication (don't wait for heartbeat interval)
            broadcastAppendEntries();
            return future;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return the current role of this node.
     *
     * @return current Raft role
     */
    public RaftRole role() {
        lock.lock();
        try {
            return role;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return the current term.
     *
     * @return current Raft term
     */
    public long currentTerm() {
        lock.lock();
        try {
            return currentTerm;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return this node's ID.
     *
     * @return node ID from config
     */
    public String nodeId() {
        return config.nodeId();
    }

    @Override
    public void close() {
        if (electionTimerThread != null) {
            electionTimerThread.interrupt();
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        rpcServer.close();
        for (RaftRpcClient client : peers.values()) {
            client.close();
        }
        try {
            log.close();
        } catch (IOException e) {
            LOG.warn("Error closing RaftLog: {}", e.getMessage());
        }
    }

    // ── RPC dispatch ──────────────────────────────────────────────────────────

    /**
     * Dispatch an incoming RPC to the correct handler and return the response.
     * Called by {@link RaftRpcServer} on a virtual thread per connection.
     *
     * @param rpc the incoming RPC
     * @return the response to send back
     */
    private RaftRpc handleRpc(final RaftRpc rpc) {
        return switch (rpc) {
            case RaftRpc.RequestVote rv         -> handleRequestVote(rv);
            case RaftRpc.AppendEntries ae       -> handleAppendEntries(ae);
            default -> throw new IllegalArgumentException("Unexpected RPC: " + rpc);
        };
    }

    // ── RequestVote ───────────────────────────────────────────────────────────

    /**
     * Handle a RequestVote RPC from a candidate.
     *
     * <p>Grant vote if:
     * <ol>
     *   <li>Candidate's term ≥ our term.</li>
     *   <li>We haven't voted for anyone else in this term.</li>
     *   <li>Candidate's log is at least as up-to-date as ours.</li>
     * </ol>
     */
    private RaftRpc.RequestVoteResponse handleRequestVote(final RaftRpc.RequestVote rv) {
        lock.lock();
        try {
            if (rv.term() > currentTerm) {
                stepDownToFollower(rv.term());
            }

            boolean grant = false;
            if (rv.term() >= currentTerm) {
                boolean notVotedYet = votedFor == null || votedFor.equals(rv.candidateId());
                boolean candidateLogUpToDate = isCandidateLogUpToDate(
                        rv.lastLogIndex(), rv.lastLogTerm());
                if (notVotedYet && candidateLogUpToDate) {
                    votedFor = rv.candidateId();
                    grant    = true;
                    resetElectionTimeout(); // only reset when we grant a vote
                }
            }

            LOG.debug("{} vote for {} in term {}: {}",
                    config.nodeId(), rv.candidateId(), rv.term(), grant ? "YES" : "NO");
            return new RaftRpc.RequestVoteResponse(currentTerm, grant);
        } finally {
            lock.unlock();
        }
    }

    /**
     * True if the candidate's log is at least as up-to-date as ours.
     * Raft §5.4.1: compare last log term first, then last log index.
     */
    private boolean isCandidateLogUpToDate(final long candidateLastIndex,
                                            final long candidateLastTerm) {
        long myLastTerm  = log.lastTerm();
        long myLastIndex = log.lastIndex();
        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        return candidateLastIndex >= myLastIndex;
    }

    // ── AppendEntries ─────────────────────────────────────────────────────────

    /**
     * Handle an AppendEntries RPC from the leader.
     * Also serves as a heartbeat when {@code entries} is empty.
     */
    private RaftRpc.AppendEntriesResponse handleAppendEntries(final RaftRpc.AppendEntries ae) {
        lock.lock();
        try {
            // Rule 1: reject if leader's term is stale
            if (ae.term() < currentTerm) {
                return new RaftRpc.AppendEntriesResponse(currentTerm, false, 0);
            }

            // Valid leader — update term, step down if needed, reset election timer
            if (ae.term() > currentTerm) {
                stepDownToFollower(ae.term());
            }
            role     = RaftRole.FOLLOWER;
            leaderId = ae.leaderId();
            resetElectionTimeout();

            // Rule 2: Log Matching check — do we have prevLogIndex with prevLogTerm?
            if (!log.containsEntry(ae.prevLogIndex(), ae.prevLogTerm())) {
                return new RaftRpc.AppendEntriesResponse(currentTerm, false, 0);
            }

            // Rule 3: append new entries, truncating any conflicting tail first
            List<LogEntry> entries = ae.entries();
            for (LogEntry entry : entries) {
                long idx = entry.index();
                if (idx <= log.lastIndex()) {
                    // Conflict: same index, different term → truncate from here
                    if (log.termAt(idx) != entry.term()) {
                        try {
                            log.truncateFrom(idx);
                        } catch (IOException e) {
                            LOG.error("Failed to truncate log at {}", idx, e);
                            return new RaftRpc.AppendEntriesResponse(currentTerm, false, 0);
                        }
                    } else {
                        continue; // already have this entry
                    }
                }
                try {
                    log.append(entry);
                } catch (IOException e) {
                    LOG.error("Failed to append entry at {}", idx, e);
                    return new RaftRpc.AppendEntriesResponse(currentTerm, false, 0);
                }
            }

            // Rule 4: advance commit index
            if (ae.leaderCommit() > commitIndex) {
                commitIndex = Math.min(ae.leaderCommit(), log.lastIndex());
                applyCommitted();
            }

            return new RaftRpc.AppendEntriesResponse(currentTerm, true, log.lastIndex());
        } finally {
            lock.unlock();
        }
    }

    // ── Leader election ───────────────────────────────────────────────────────

    /** Start the election timer — fires if we don't hear from a leader in time. */
    private void startElectionTimer() {
        electionTimerThread = Thread.ofVirtual().name("raft-election-timer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int timeout = ThreadLocalRandom.current().nextInt(
                        config.electionTimeoutMinMs(), config.electionTimeoutMaxMs());
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                lock.lock();
                try {
                    long elapsed = System.currentTimeMillis() - lastHeartbeatMs;
                    if (role != RaftRole.LEADER && elapsed >= timeout) {
                        startElection();
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    /** Begin a new election: become candidate, increment term, request votes. */
    private void startElection() {
        role        = RaftRole.CANDIDATE;
        currentTerm++;
        votedFor    = config.nodeId(); // vote for self
        leaderId    = null;
        resetElectionTimeout();

        LOG.info("{} starting election for term {}", config.nodeId(), currentTerm);

        long term         = currentTerm;
        long lastLogIndex = log.lastIndex();
        long lastLogTerm  = log.lastTerm();

        int[] votesGranted = { 1 }; // already have our own vote

        // Single-node cluster: win immediately
        if (config.clusterSize() == 1) {
            becomeLeader();
            return;
        }

        // Request votes from all peers in parallel on virtual threads
        for (Map.Entry<String, RaftRpcClient> e : peers.entrySet()) {
            String peerId   = e.getKey();
            RaftRpcClient client = e.getValue();

            Thread.ofVirtual().name("raft-vote-" + peerId).start(() -> {
                RaftRpc response = client.send(new RaftRpc.RequestVote(
                        term, config.nodeId(), lastLogIndex, lastLogTerm));

                if (!(response instanceof RaftRpc.RequestVoteResponse rvr)) {
                    return;
                }

                lock.lock();
                try {
                    if (rvr.term() > currentTerm) {
                        stepDownToFollower(rvr.term());
                        return;
                    }
                    if (role != RaftRole.CANDIDATE || currentTerm != term) {
                        return; // election already resolved
                    }
                    if (rvr.voteGranted()) {
                        votesGranted[0]++;
                        if (votesGranted[0] >= config.majority()) {
                            becomeLeader();
                        }
                    }
                } finally {
                    lock.unlock();
                }
            });
        }
    }

    /** Transition to leader: initialize nextIndex/matchIndex, send immediate heartbeat. */
    private void becomeLeader() {
        role     = RaftRole.LEADER;
        leaderId = config.nodeId();
        LOG.info("{} became LEADER for term {}", config.nodeId(), currentTerm);

        // Initialize per-peer tracking
        for (String peerId : config.peerIds()) {
            nextIndex.put(peerId, log.lastIndex() + 1);
            matchIndex.put(peerId, 0L);
        }

        // Append a no-op to commit any pending entries from previous terms
        try {
            LogEntry noop = LogEntry.noop(log.lastIndex() + 1, currentTerm);
            log.append(noop);
        } catch (IOException e) {
            LOG.error("Failed to append no-op", e);
        }

        // Send immediate heartbeat, then start periodic heartbeat loop
        broadcastAppendEntries();
        startHeartbeat();
    }

    /** Start the leader heartbeat loop (sends empty AppendEntries every heartbeatIntervalMs). */
    private void startHeartbeat() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        heartbeatThread = Thread.ofVirtual().name("raft-heartbeat").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(config.heartbeatIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                lock.lock();
                try {
                    if (role == RaftRole.LEADER) {
                        broadcastAppendEntries();
                    } else {
                        break; // stepped down
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    // ── Log replication ───────────────────────────────────────────────────────

    /**
     * Send AppendEntries to all peers. Each peer gets entries starting from
     * its {@code nextIndex}. If nextIndex > lastIndex, the message is a heartbeat.
     * Caller must hold the lock.
     */
    private void broadcastAppendEntries() {
        // Single-node cluster: leader alone forms a majority — commit immediately.
        if (config.peerIds().isEmpty()) {
            advanceCommitIndex();
            return;
        }

        for (String peerId : config.peerIds()) {
            long next = nextIndex.getOrDefault(peerId, log.lastIndex() + 1);
            long prevLogIndex = next - 1;
            long prevLogTerm  = log.containsEntry(prevLogIndex, log.lastTerm())
                    ? (prevLogIndex > 0 ? log.termAt(prevLogIndex) : 0)
                    : 0;
            List<LogEntry> entries = log.getFrom(next);

            long term          = currentTerm;
            long leaderCommit  = commitIndex;
            RaftRpc.AppendEntries ae = new RaftRpc.AppendEntries(
                    term, config.nodeId(), prevLogIndex, prevLogTerm, entries, leaderCommit);

            RaftRpcClient client = peers.get(peerId);
            // Send on virtual thread so slow/dead peers don't block the lock
            Thread.ofVirtual().name("raft-ae-" + peerId).start(() -> {
                RaftRpc response = client.send(ae);
                if (!(response instanceof RaftRpc.AppendEntriesResponse aer)) {
                    return;
                }
                lock.lock();
                try {
                    if (aer.term() > currentTerm) {
                        stepDownToFollower(aer.term());
                        return;
                    }
                    if (role != RaftRole.LEADER || currentTerm != term) {
                        return;
                    }
                    if (aer.success()) {
                        matchIndex.put(peerId, aer.matchIndex());
                        nextIndex.put(peerId, aer.matchIndex() + 1);
                        advanceCommitIndex();
                    } else {
                        // Follower rejected — back up nextIndex and retry on next heartbeat
                        long current = nextIndex.getOrDefault(peerId, 1L);
                        nextIndex.put(peerId, Math.max(1L, current - 1));
                    }
                } finally {
                    lock.unlock();
                }
            });
        }
    }

    /**
     * Advance the leader's commitIndex to the highest index replicated on a majority.
     * Caller must hold the lock.
     */
    private void advanceCommitIndex() {
        // Find the highest index N such that:
        //   1. N > commitIndex
        //   2. log[N].term == currentTerm  (Raft §5.4.2 — only commit current term entries)
        //   3. majority of matchIndex[peer] >= N
        long lastIndex = log.lastIndex();
        for (long n = lastIndex; n > commitIndex; n--) {
            if (log.termAt(n) != currentTerm) {
                continue;
            }
            int replicatedOn = 1; // leader has it
            for (long m : matchIndex.values()) {
                if (m >= n) {
                    replicatedOn++;
                }
            }
            if (replicatedOn >= config.majority()) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    // ── State machine apply ───────────────────────────────────────────────────

    /**
     * Apply all committed but not-yet-applied log entries to the storage engine.
     * Caller must hold the lock.
     */
    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.get(lastApplied);
            applyEntry(entry);

            // Complete any pending client future waiting on this index
            CompletableFuture<Void> future = pendingCommits.remove(lastApplied);
            if (future != null) {
                future.complete(null);
            }
        }
        committed.signalAll();
    }

    /** Apply one committed entry to the storage engine state machine. */
    private void applyEntry(final LogEntry entry) {
        try {
            switch (entry.type()) {
                case PUT -> {
                    if (entry.expiryMillis() > 0) {
                        stateMachine.put(entry.key(), entry.value(), entry.expiryMillis());
                    } else {
                        stateMachine.put(entry.key(), entry.value());
                    }
                }
                case DELETE -> stateMachine.delete(entry.key());
                case NOOP   -> { /* no-op: does not modify state */ }
                default     -> LOG.warn("Unknown entry type: {}", entry.type());
            }
        } catch (Exception e) {
            LOG.error("Failed to apply log entry at index {}: {}", entry.index(), e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Step down to follower with a new term. Caller must hold the lock. */
    private void stepDownToFollower(final long newTerm) {
        LOG.info("{} stepping down: term {} → {}", config.nodeId(), currentTerm, newTerm);
        currentTerm = newTerm;
        role        = RaftRole.FOLLOWER;
        votedFor    = null;
        leaderId    = null;
    }

    /** Record that we heard from a leader right now (resets the election timer). */
    private void resetElectionTimeout() {
        lastHeartbeatMs = System.currentTimeMillis();
    }
}
