package io.kiradb.raft;

/**
 * Thrown when a write is attempted on a node that is not the current Raft leader.
 *
 * <p>Callers can inspect {@link #leaderId()} to redirect the client to the known leader.
 * If {@link #leaderId()} returns {@code null}, the cluster has no known leader (e.g. an
 * election is in progress) and the caller should retry after a short delay.
 */
public final class NotLeaderException extends RuntimeException {

    private final String leaderId;

    /**
     * Construct a not-leader exception.
     *
     * @param leaderId the node ID of the current leader, or {@code null} if unknown
     */
    public NotLeaderException(final String leaderId) {
        super("Not the leader" + (leaderId != null ? "; known leader: " + leaderId : ""));
        this.leaderId = leaderId;
    }

    /**
     * Return the node ID of the current leader, or {@code null} if unknown.
     *
     * @return leader node ID, or null if the cluster has no known leader
     */
    public String leaderId() {
        return leaderId;
    }
}
