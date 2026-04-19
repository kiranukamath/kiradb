package io.kiradb.raft.rpc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.kiradb.raft.log.LogEntry;

import java.util.List;

/**
 * Sealed interface for all Raft RPC messages.
 *
 * <p>Raft uses four RPCs:
 * <ul>
 *   <li>{@link RequestVote} — candidate asks peers for votes during an election</li>
 *   <li>{@link RequestVoteResponse} — peer's reply to a vote request</li>
 *   <li>{@link AppendEntries} — leader sends log entries (also used as heartbeat when empty)</li>
 *   <li>{@link AppendEntriesResponse} — follower's reply to an AppendEntries RPC</li>
 * </ul>
 *
 * <p>Jackson serializes these as newline-delimited JSON over TCP, with a {@code "type"} field
 * discriminating between subtypes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RaftRpc.RequestVote.class,          name = "RequestVote"),
    @JsonSubTypes.Type(value = RaftRpc.RequestVoteResponse.class,  name = "RequestVoteResponse"),
    @JsonSubTypes.Type(value = RaftRpc.AppendEntries.class,        name = "AppendEntries"),
    @JsonSubTypes.Type(value = RaftRpc.AppendEntriesResponse.class, name = "AppendEntriesResponse")
})
public sealed interface RaftRpc permits
        RaftRpc.RequestVote,
        RaftRpc.RequestVoteResponse,
        RaftRpc.AppendEntries,
        RaftRpc.AppendEntriesResponse {

    /**
     * Sent by a candidate to gather votes during an election.
     *
     * <p>A peer grants its vote if:
     * <ol>
     *   <li>It has not already voted in this term.</li>
     *   <li>The candidate's log is at least as up-to-date as the peer's log
     *       (compared by {@code lastLogTerm}, then {@code lastLogIndex}).</li>
     * </ol>
     *
     * @param term         the candidate's current term
     * @param candidateId  the candidate's node ID
     * @param lastLogIndex index of the candidate's last log entry
     * @param lastLogTerm  term of the candidate's last log entry
     */
    record RequestVote(
            long term,
            String candidateId,
            long lastLogIndex,
            long lastLogTerm) implements RaftRpc { }

    /**
     * A peer's reply to a {@link RequestVote} RPC.
     *
     * @param term        the responder's current term (candidate uses this to step down if stale)
     * @param voteGranted true if the vote was granted
     */
    record RequestVoteResponse(
            long term,
            boolean voteGranted) implements RaftRpc { }

    /**
     * Sent by the leader to replicate log entries and as a heartbeat (empty {@code entries}).
     *
     * <p>The {@code prevLogIndex} and {@code prevLogTerm} fields implement the Log Matching
     * consistency check: a follower only appends entries if its log already contains an entry
     * at {@code prevLogIndex} with term {@code prevLogTerm}.
     *
     * @param term         leader's current term
     * @param leaderId     leader's node ID (followers use this to redirect clients)
     * @param prevLogIndex index of the log entry immediately before the new ones
     * @param prevLogTerm  term of the entry at {@code prevLogIndex}
     * @param entries      entries to append (empty = heartbeat)
     * @param leaderCommit leader's commit index (followers advance their commit index to this)
     */
    record AppendEntries(
            long term,
            String leaderId,
            long prevLogIndex,
            long prevLogTerm,
            List<LogEntry> entries,
            long leaderCommit) implements RaftRpc { }

    /**
     * A follower's reply to an {@link AppendEntries} RPC.
     *
     * @param term       the follower's current term (leader uses this to step down if stale)
     * @param success    true if the follower's log consistency check passed
     * @param matchIndex the highest log index the follower has confirmed (only valid on success)
     */
    record AppendEntriesResponse(
            long term,
            boolean success,
            long matchIndex) implements RaftRpc { }
}
