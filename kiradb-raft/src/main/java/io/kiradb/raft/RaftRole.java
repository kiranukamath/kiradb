package io.kiradb.raft;

/**
 * The three possible roles a Raft node can be in at any point in time.
 *
 * <p>In normal operation there is exactly one {@link #LEADER} and the rest are
 * {@link #FOLLOWER}s. {@link #CANDIDATE} is a transient state during leader election.
 *
 * <p>Transitions:
 * <pre>
 *   FOLLOWER  → CANDIDATE  (election timeout fires, no heartbeat received)
 *   CANDIDATE → LEADER     (wins majority of votes)
 *   CANDIDATE → FOLLOWER   (sees higher term or another node wins)
 *   LEADER    → FOLLOWER   (sees higher term in any RPC)
 * </pre>
 */
public enum RaftRole {

    /** Default state. Listens for heartbeats, replicates the leader's log entries. */
    FOLLOWER,

    /** Transitional state. Requesting votes from peers to become the next leader. */
    CANDIDATE,

    /** Accepts all client writes, replicates them to followers, drives commit index forward. */
    LEADER
}
