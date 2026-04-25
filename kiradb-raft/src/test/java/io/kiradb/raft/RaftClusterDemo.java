package io.kiradb.raft;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.raft.log.LogEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manual demo: spin up 3 Raft nodes in-process and observe:
 * <ol>
 *   <li>Leader election</li>
 *   <li>Log replication to all followers</li>
 *   <li>Leader failover and re-election</li>
 *   <li>Writes continuing after failover</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :kiradb-raft:test --tests "io.kiradb.raft.RaftClusterDemo"
 * </pre>
 *
 * <p>See {@code docs/internals/raft-manual-test.md} for the full walkthrough.
 */
@SuppressWarnings({"PMD", "checkstyle:all"})
public final class RaftClusterDemo {

    private static final int PORT_1 = 17400;
    private static final int PORT_2 = 17401;
    private static final int PORT_3 = 17402;

    private RaftClusterDemo() { }

    /**
     * Entry point — spin up 3 nodes, observe election, replication, and failover.
     *
     * @param args unused
     * @throws Exception if any node fails to start
     */
    public static void main(final String[] args) throws Exception {
        // 1. Data directories
        Path base = Files.createTempDirectory("kiradb-demo-");
        System.out.println("Data dir: " + base);

        // 2. One LSM storage engine per node (the state machine)
        StorageEngine sm1 = new LsmStorageEngine(base.resolve("sm1"));
        StorageEngine sm2 = new LsmStorageEngine(base.resolve("sm2"));
        StorageEngine sm3 = new LsmStorageEngine(base.resolve("sm3"));

        // 3. Peer address maps — each node knows about the other two
        Map<String, String> peers1 = Map.of(
                "node-2", "localhost:" + PORT_2,
                "node-3", "localhost:" + PORT_3);
        Map<String, String> peers2 = Map.of(
                "node-1", "localhost:" + PORT_1,
                "node-3", "localhost:" + PORT_3);
        Map<String, String> peers3 = Map.of(
                "node-1", "localhost:" + PORT_1,
                "node-2", "localhost:" + PORT_2);

        // 4. Create nodes
        RaftNode node1 = new RaftNode(RaftConfig.cluster("node-1", peers1, PORT_1),
                base.resolve("raft1"), sm1);
        RaftNode node2 = new RaftNode(RaftConfig.cluster("node-2", peers2, PORT_2),
                base.resolve("raft2"), sm2);
        RaftNode node3 = new RaftNode(RaftConfig.cluster("node-3", peers3, PORT_3),
                base.resolve("raft3"), sm3);

        // 5. Start all three — each begins as FOLLOWER, election timers start
        System.out.println("\n--- Starting 3-node cluster ---");
        node1.start();
        node2.start();
        node3.start();

        // 6. Wait for one leader to emerge
        System.out.println("Waiting for leader election (max 2s)...");
        Thread.sleep(1_000);

        RaftNode leader = findLeader(node1, node2, node3);
        if (leader == null) {
            System.out.println("ERROR: No leader elected. Check that ports are free.");
            System.exit(1);
        }

        printRoles("After initial election", node1, node2, node3);

        // 7. Write a key to the leader — blocks until majority committed it
        System.out.println("\n--- Writing 'city' = 'Bangalore' to leader (" + leader.nodeId() + ") ---");
        leader.propose(LogEntry.put("city".getBytes(), "Bangalore".getBytes(), -1L))
              .get(5, TimeUnit.SECONDS);
        System.out.println("Write committed.");

        Thread.sleep(200); // give followers time to apply

        // 8. Read from all 3 nodes — all should have the value
        System.out.println("\n--- Reading 'city' from all 3 storage engines ---");
        printValue("node-1", sm1, "city");
        printValue("node-2", sm2, "city");
        printValue("node-3", sm3, "city");

        // 9. Kill the leader
        System.out.println("\n--- Killing leader (" + leader.nodeId() + ") ---");
        leader.close();

        // 10. Wait for re-election
        System.out.println("Waiting for new leader (max 1s)...");
        Thread.sleep(1_000);

        RaftNode newLeader = null;
        for (RaftNode n : new RaftNode[]{node1, node2, node3}) {
            try {
                if (n != leader && n.role() == RaftRole.LEADER) {
                    newLeader = n;
                    break;
                }
            } catch (Exception ignored) { }
        }

        printRoles("After failover", node1, node2, node3);

        if (newLeader == null) {
            System.out.println("ERROR: No new leader elected after failover.");
            System.exit(1);
        }

        // 11. Write to the new leader
        System.out.println("\n--- Writing 'status' = 'recovered' to new leader (" + newLeader.nodeId() + ") ---");
        newLeader.propose(LogEntry.put("status".getBytes(), "recovered".getBytes(), -1L))
                 .get(5, TimeUnit.SECONDS);
        System.out.println("Write committed on new leader.");

        Thread.sleep(200);

        // 12. Both writes must survive on surviving nodes
        System.out.println("\n--- Final state of surviving nodes ---");
        StorageEngine newLeaderSm = smFor(newLeader, node1, node2, node3, sm1, sm2, sm3);
        StorageEngine otherSm     = otherSurvivorSm(node1, node2, node3, leader, newLeader, sm1, sm2, sm3);

        System.out.println("New leader (" + newLeader.nodeId() + "):");
        printValue("  city  ", newLeaderSm, "city");
        printValue("  status", newLeaderSm, "status");

        System.out.println("Other survivor:");
        printValue("  city  ", otherSm, "city");
        printValue("  status", otherSm, "status");

        // 13. Cleanup
        System.out.println("\n--- Shutting down ---");
        for (RaftNode n : new RaftNode[]{node1, node2, node3}) {
            try {
                n.close();
            } catch (Exception ignored) { }
        }
        for (StorageEngine sm : new StorageEngine[]{sm1, sm2, sm3}) {
            try {
                sm.close();
            } catch (Exception ignored) { }
        }
        System.out.println("Done.");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RaftNode findLeader(final RaftNode... nodes) {
        for (RaftNode n : nodes) {
            try {
                if (n.role() == RaftRole.LEADER) {
                    return n;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void printRoles(final String label,
                                   final RaftNode n1,
                                   final RaftNode n2,
                                   final RaftNode n3) {
        System.out.printf("%n[%s]%n", label);
        for (RaftNode n : new RaftNode[]{n1, n2, n3}) {
            String role;
            try {
                role = n.role().name();
            } catch (Exception e) {
                role = "CLOSED";
            }
            System.out.printf("  %-8s  term=%-3s  role=%s%n",
                    n.nodeId(), termOrUnknown(n), role);
        }
    }

    private static String termOrUnknown(final RaftNode n) {
        try {
            return String.valueOf(n.currentTerm());
        } catch (Exception e) {
            return "?";
        }
    }

    private static void printValue(final String label,
                                   final StorageEngine sm,
                                   final String key) {
        Optional<byte[]> val = sm.get(key.getBytes());
        System.out.printf("  %-10s  →  %s%n", label,
                val.map(String::new).orElse("(not found)"));
    }

    private static StorageEngine smFor(final RaftNode target,
                                       final RaftNode n1, final RaftNode n2, final RaftNode n3,
                                       final StorageEngine s1, final StorageEngine s2,
                                       final StorageEngine s3) {
        if (target == n1) {
            return s1;
        }
        if (target == n2) {
            return s2;
        }
        return s3;
    }

    private static StorageEngine otherSurvivorSm(
            final RaftNode n1, final RaftNode n2, final RaftNode n3,
            final RaftNode dead, final RaftNode newLeader,
            final StorageEngine s1, final StorageEngine s2, final StorageEngine s3) {
        if (n1 != dead && n1 != newLeader) {
            return s1;
        }
        if (n2 != dead && n2 != newLeader) {
            return s2;
        }
        return s3;
    }
}
