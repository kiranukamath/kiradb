package io.kiradb.raft;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a Raft node.
 *
 * <p>All time values are in milliseconds.
 *
 * <p>Use the static factory methods for common configurations:
 * <ul>
 *   <li>{@link #singleNode(String, int)} — standalone node (no peers, majority = 1)</li>
 *   <li>{@link #cluster(String, Map, int)} — 3+ node cluster</li>
 * </ul>
 *
 * @param nodeId               unique identifier for this node (e.g. {@code "node-1"})
 * @param peerIds              IDs of all other nodes in the cluster (excluding self)
 * @param peerAddresses        map of nodeId → {@code "host:raftPort"} for each peer
 * @param raftPort             TCP port this node listens on for Raft RPCs
 * @param electionTimeoutMinMs minimum election timeout (default 150ms)
 * @param electionTimeoutMaxMs maximum election timeout (default 300ms)
 * @param heartbeatIntervalMs  how often the leader sends heartbeats (default 50ms)
 */
public record RaftConfig(
        String nodeId,
        List<String> peerIds,
        Map<String, String> peerAddresses,
        int raftPort,
        int electionTimeoutMinMs,
        int electionTimeoutMaxMs,
        int heartbeatIntervalMs) {

    /**
     * Create a single-node configuration (no peers, majority = 1).
     * The node becomes leader immediately on startup after its own election timeout.
     *
     * @param nodeId   node identifier
     * @param raftPort Raft RPC port (not used for inbound in single-node mode)
     * @return single-node config
     */
    public static RaftConfig singleNode(final String nodeId, final int raftPort) {
        return new RaftConfig(nodeId, List.of(), Map.of(), raftPort, 150, 300, 50);
    }

    /**
     * Create a cluster configuration.
     *
     * @param nodeId        this node's identifier
     * @param peerAddresses map of peerId → {@code "host:raftPort"} for every other node
     * @param raftPort      this node's Raft RPC listen port
     * @return cluster config
     */
    public static RaftConfig cluster(
            final String nodeId,
            final Map<String, String> peerAddresses,
            final int raftPort) {
        return new RaftConfig(
                nodeId,
                List.copyOf(peerAddresses.keySet()),
                Map.copyOf(peerAddresses),
                raftPort,
                150, 300, 50);
    }

    /**
     * Cluster size (this node + all peers).
     *
     * @return total number of nodes
     */
    public int clusterSize() {
        return peerIds.size() + 1;
    }

    /**
     * Minimum number of nodes required to form a majority.
     *
     * @return majority threshold (e.g. 2 for a 3-node cluster)
     */
    public int majority() {
        return clusterSize() / 2 + 1;
    }
}
