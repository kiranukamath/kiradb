package io.kiradb.raft;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.raft.log.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RaftNode}: single-node and 3-node cluster scenarios.
 *
 * <p>Single-node tests verify election, proposal and commit without network.
 * 3-node tests verify leader election, replication and failover.
 */
class RaftNodeTest {

    @TempDir
    Path tempDir;

    private RaftNode node1;
    private RaftNode node2;
    private RaftNode node3;
    private StorageEngine sm1;
    private StorageEngine sm2;
    private StorageEngine sm3;

    @AfterEach
    void tearDown() {
        closeQuietly(node1);
        closeQuietly(node2);
        closeQuietly(node3);
        closeQuietly(sm1);
        closeQuietly(sm2);
        closeQuietly(sm3);
    }

    // ── Single-node (no peers) ────────────────────────────────────────────────

    @Test
    void singleNodeBecomesLeaderImmediately() throws Exception {
        node1 = startSingleNode("node-1", 17300);
        waitForLeader(node1, 2_000);
        assertEquals(RaftRole.LEADER, node1.role());
    }

    @Test
    void singleNodeCommitsWrite() throws Exception {
        sm1   = newStorage(tempDir.resolve("sm1"));
        node1 = startSingleNodeWithStorage("node-1", 17301, sm1);
        waitForLeader(node1, 2_000);

        CompletableFuture<Void> future = node1.propose(
                LogEntry.put("hello".getBytes(), "world".getBytes(), -1L));
        future.get(3, TimeUnit.SECONDS);

        Optional<byte[]> result = sm1.get("hello".getBytes());
        assertTrue(result.isPresent(), "hello should be in storage after commit");
        assertArrayEquals("world".getBytes(), result.get());
    }

    @Test
    void singleNodeCommitsDelete() throws Exception {
        sm1   = newStorage(tempDir.resolve("sm1"));
        node1 = startSingleNodeWithStorage("node-1", 17302, sm1);
        waitForLeader(node1, 2_000);

        node1.propose(LogEntry.put("key".getBytes(), "value".getBytes(), -1L))
             .get(3, TimeUnit.SECONDS);
        node1.propose(LogEntry.delete("key".getBytes()))
             .get(3, TimeUnit.SECONDS);

        assertTrue(sm1.get("key".getBytes()).isEmpty(), "key should be gone after DELETE commit");
    }

    @Test
    void singleNodeMultipleWrites() throws Exception {
        sm1   = newStorage(tempDir.resolve("sm1"));
        node1 = startSingleNodeWithStorage("node-1", 17303, sm1);
        waitForLeader(node1, 2_000);

        for (int i = 0; i < 20; i++) {
            node1.propose(LogEntry.put(
                    ("key-" + i).getBytes(), ("val-" + i).getBytes(), -1L))
                 .get(3, TimeUnit.SECONDS);
        }

        for (int i = 0; i < 20; i++) {
            Optional<byte[]> result = sm1.get(("key-" + i).getBytes());
            assertTrue(result.isPresent(), "key-" + i + " should be present");
            assertArrayEquals(("val-" + i).getBytes(), result.get());
        }
    }

    @Test
    void proposeOnFollowerThrowsNotLeaderException() throws Exception {
        // Single node is a follower until it wins election — test immediately
        RaftConfig cfg = RaftConfig.singleNode("node-1", 17304);
        sm1 = newStorage(tempDir.resolve("sm1"));
        node1 = new RaftNode(cfg, tempDir.resolve("raft1"), sm1);
        // Do NOT call start() — it stays FOLLOWER
        assertThrows(NotLeaderException.class, () ->
                node1.propose(LogEntry.put("k".getBytes(), "v".getBytes(), -1L)));
    }

    // ── 3-node cluster ────────────────────────────────────────────────────────

    @Test
    void threeNodeClusterElectsOneLeader() throws Exception {
        startThreeNodeCluster(17310, 17311, 17312);

        // Wait for exactly one leader to emerge
        Thread.sleep(1_000);

        int leaders = countLeaders(node1, node2, node3);
        assertEquals(1, leaders, "Exactly one leader must exist");
    }

    @Test
    void threeNodeClusterReplicatesWrite() throws Exception {
        startThreeNodeCluster(17313, 17314, 17315);
        Thread.sleep(1_000);

        RaftNode leader = findLeader(node1, node2, node3);
        assertNotNull(leader, "A leader must have been elected");

        leader.propose(LogEntry.put("hello".getBytes(), "world".getBytes(), -1L))
              .get(5, TimeUnit.SECONDS);

        // Give followers time to apply
        Thread.sleep(300);

        // All three state machines must have the value
        for (StorageEngine sm : new StorageEngine[]{sm1, sm2, sm3}) {
            Optional<byte[]> result = sm.get("hello".getBytes());
            assertTrue(result.isPresent(), "All nodes must have the committed value");
            assertArrayEquals("world".getBytes(), result.get());
        }
    }

    @Test
    void leaderFailoverElectsNewLeader() throws Exception {
        startThreeNodeCluster(17316, 17317, 17318);
        Thread.sleep(1_000);

        RaftNode leader = findLeader(node1, node2, node3);
        assertNotNull(leader, "Initial leader must exist");
        long term1 = leader.currentTerm();

        // Kill the leader
        leader.close();

        // Wait for re-election (up to 1 second per CLAUDE.md requirement)
        Thread.sleep(1_000);

        RaftNode newLeader = null;
        for (RaftNode n : new RaftNode[]{node1, node2, node3}) {
            if (n != leader && !isInterrupted(n) && n.role() == RaftRole.LEADER) {
                newLeader = n;
                break;
            }
        }
        assertNotNull(newLeader, "A new leader must be elected after old leader fails");
        assertTrue(newLeader.currentTerm() > term1, "New leader must have a higher term");
    }

    @Test
    void writesSucceedAfterLeaderFailover() throws Exception {
        startThreeNodeCluster(17319, 17320, 17321);
        Thread.sleep(1_000);

        RaftNode leader = findLeader(node1, node2, node3);
        assertNotNull(leader, "Initial leader must exist");

        // Write before failover
        leader.propose(LogEntry.put("pre".getBytes(), "failover".getBytes(), -1L))
              .get(5, TimeUnit.SECONDS);

        // Kill the leader
        leader.close();
        Thread.sleep(1_000);

        // Find new leader
        RaftNode newLeader = null;
        for (RaftNode n : new RaftNode[]{node1, node2, node3}) {
            try {
                if (n != leader && n.role() == RaftRole.LEADER) {
                    newLeader = n;
                    break;
                }
            } catch (Exception ignored) { }
        }
        assertNotNull(newLeader, "New leader must be elected");

        // Write after failover
        newLeader.propose(LogEntry.put("post".getBytes(), "failover".getBytes(), -1L))
                 .get(5, TimeUnit.SECONDS);

        // Surviving nodes must have both writes
        StorageEngine survivor = (newLeader == node1) ? sm1 : (newLeader == node2) ? sm2 : sm3;
        assertTrue(survivor.get("pre".getBytes()).isPresent());
        assertTrue(survivor.get("post".getBytes()).isPresent());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RaftNode startSingleNode(final String id, final int port) throws IOException {
        sm1 = newStorage(tempDir.resolve(id));
        return startSingleNodeWithStorage(id, port, sm1);
    }

    private RaftNode startSingleNodeWithStorage(
            final String id, final int port, final StorageEngine sm) throws IOException {
        RaftConfig cfg = RaftConfig.singleNode(id, port);
        RaftNode node = new RaftNode(cfg, tempDir.resolve("raft-" + id), sm);
        node.start();
        return node;
    }

    private void startThreeNodeCluster(
            final int port1, final int port2, final int port3) throws IOException {
        sm1 = newStorage(tempDir.resolve("sm1"));
        sm2 = newStorage(tempDir.resolve("sm2"));
        sm3 = newStorage(tempDir.resolve("sm3"));

        Map<String, String> peers1 = Map.of(
                "node-2", "localhost:" + port2,
                "node-3", "localhost:" + port3);
        Map<String, String> peers2 = Map.of(
                "node-1", "localhost:" + port1,
                "node-3", "localhost:" + port3);
        Map<String, String> peers3 = Map.of(
                "node-1", "localhost:" + port1,
                "node-2", "localhost:" + port2);

        node1 = new RaftNode(RaftConfig.cluster("node-1", peers1, port1),
                tempDir.resolve("raft1"), sm1);
        node2 = new RaftNode(RaftConfig.cluster("node-2", peers2, port2),
                tempDir.resolve("raft2"), sm2);
        node3 = new RaftNode(RaftConfig.cluster("node-3", peers3, port3),
                tempDir.resolve("raft3"), sm3);

        node1.start();
        node2.start();
        node3.start();
    }

    private static void waitForLeader(final RaftNode node, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (node.role() == RaftRole.LEADER) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Node did not become leader within " + timeoutMs + "ms");
    }

    private static RaftNode findLeader(final RaftNode... nodes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode n : nodes) {
                try {
                    if (n.role() == RaftRole.LEADER) {
                        return n;
                    }
                } catch (Exception ignored) { }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static int countLeaders(final RaftNode... nodes) {
        int count = 0;
        for (RaftNode n : nodes) {
            try {
                if (n.role() == RaftRole.LEADER) {
                    count++;
                }
            } catch (Exception ignored) { }
        }
        return count;
    }

    private static boolean isInterrupted(final RaftNode node) {
        try {
            node.role();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private StorageEngine newStorage(final Path dir) throws IOException {
        return new LsmStorageEngine(dir);
    }

    private static void closeQuietly(final AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) { }
    }
}
