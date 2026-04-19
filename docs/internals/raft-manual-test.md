# Manual Raft Cluster Testing

This guide shows you how to spin up a 3-node Raft cluster in a single JVM process
and observe leader election, log replication, and failover — all from a `main()` method.

---

## What you will see

By the end of this guide you will:
1. Watch a leader get elected automatically
2. Write a key to the leader and confirm it appeared on all 3 nodes
3. Kill the leader and watch a new one take over
4. Write again to the new leader and confirm the write succeeded

---

## Step 1: Create the test runner

Create the file `kiradb-raft/src/test/java/io/kiradb/raft/RaftClusterDemo.java`:

```java
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
 * Manual test: spin up 3 Raft nodes in-process, observe election and replication.
 *
 * Run with:
 *   ./gradlew :kiradb-raft:test --tests "io.kiradb.raft.RaftClusterDemo"
 *
 * Or from an IDE: right-click → Run 'RaftClusterDemo.main()'
 */
public final class RaftClusterDemo {

    // Raft RPC ports — one per node (must be free on your machine)
    private static final int PORT_1 = 17400;
    private static final int PORT_2 = 17401;
    private static final int PORT_3 = 17402;

    public static void main(String[] args) throws Exception {

        // ── 1. Create temp data directories ──────────────────────────────────
        Path base = Files.createTempDirectory("kiradb-demo-");
        System.out.println("Data dir: " + base);

        // ── 2. Create one LSM storage engine per node ─────────────────────────
        //    This is the state machine — committed Raft entries are applied here.
        StorageEngine sm1 = new LsmStorageEngine(base.resolve("sm1"));
        StorageEngine sm2 = new LsmStorageEngine(base.resolve("sm2"));
        StorageEngine sm3 = new LsmStorageEngine(base.resolve("sm3"));

        // ── 3. Build RaftConfig for each node ────────────────────────────────
        //    Each node needs to know its own port AND the address of every peer.
        Map<String, String> peers1 = Map.of(
                "node-2", "localhost:" + PORT_2,
                "node-3", "localhost:" + PORT_3);

        Map<String, String> peers2 = Map.of(
                "node-1", "localhost:" + PORT_1,
                "node-3", "localhost:" + PORT_3);

        Map<String, String> peers3 = Map.of(
                "node-1", "localhost:" + PORT_1,
                "node-2", "localhost:" + PORT_2);

        RaftConfig cfg1 = RaftConfig.cluster("node-1", peers1, PORT_1);
        RaftConfig cfg2 = RaftConfig.cluster("node-2", peers2, PORT_2);
        RaftConfig cfg3 = RaftConfig.cluster("node-3", peers3, PORT_3);

        // ── 4. Create RaftNodes ───────────────────────────────────────────────
        RaftNode node1 = new RaftNode(cfg1, base.resolve("raft1"), sm1);
        RaftNode node2 = new RaftNode(cfg2, base.resolve("raft2"), sm2);
        RaftNode node3 = new RaftNode(cfg3, base.resolve("raft3"), sm3);

        // ── 5. Start all three ────────────────────────────────────────────────
        //    Each node starts as FOLLOWER. One election timer fires first → LEADER.
        System.out.println("\n--- Starting 3-node cluster ---");
        node1.start();
        node2.start();
        node3.start();

        // ── 6. Wait for a leader to emerge ───────────────────────────────────
        System.out.println("Waiting for leader election (max 2s)...");
        Thread.sleep(1_000);

        RaftNode leader = findLeader(node1, node2, node3);
        if (leader == null) {
            System.out.println("ERROR: No leader elected. Check ports.");
            System.exit(1);
        }

        printRoles("After initial election", node1, node2, node3);

        // ── 7. Write a key to the leader ─────────────────────────────────────
        System.out.println("\n--- Writing 'city' = 'Bangalore' to leader (" + leader.nodeId() + ") ---");
        leader.propose(LogEntry.put("city".getBytes(), "Bangalore".getBytes(), -1L))
              .get(5, TimeUnit.SECONDS);
        System.out.println("Write committed.");

        // Give followers 200ms to apply the committed entry
        Thread.sleep(200);

        // ── 8. Read from ALL three nodes ─────────────────────────────────────
        System.out.println("\n--- Reading 'city' from all 3 storage engines ---");
        printValue("node-1", sm1, "city");
        printValue("node-2", sm2, "city");
        printValue("node-3", sm3, "city");

        // ── 9. Kill the leader ────────────────────────────────────────────────
        System.out.println("\n--- Killing leader (" + leader.nodeId() + ") ---");
        leader.close();

        // ── 10. Wait for re-election ─────────────────────────────────────────
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

        // ── 11. Write to the new leader ───────────────────────────────────────
        System.out.println("\n--- Writing 'status' = 'recovered' to new leader (" + newLeader.nodeId() + ") ---");
        newLeader.propose(LogEntry.put("status".getBytes(), "recovered".getBytes(), -1L))
                 .get(5, TimeUnit.SECONDS);
        System.out.println("Write committed on new leader.");

        Thread.sleep(200);

        // ── 12. Confirm both writes survived ──────────────────────────────────
        System.out.println("\n--- Final state of surviving nodes ---");
        StorageEngine survivor1 = (newLeader == node1) ? sm1 : (newLeader == node2) ? sm2 : sm3;
        StorageEngine survivor2 = getOtherSurvivor(node1, node2, node3, leader, newLeader,
                sm1, sm2, sm3);

        System.out.println("Survivor " + newLeader.nodeId() + ":");
        printValue("  city  ", survivor1, "city");
        printValue("  status", survivor1, "status");

        System.out.println("Other survivor:");
        printValue("  city  ", survivor2, "city");
        printValue("  status", survivor2, "status");

        // ── 13. Cleanup ───────────────────────────────────────────────────────
        System.out.println("\n--- Shutting down ---");
        for (RaftNode n : new RaftNode[]{node1, node2, node3}) {
            try { n.close(); } catch (Exception ignored) { }
        }
        for (StorageEngine sm : new StorageEngine[]{sm1, sm2, sm3}) {
            try { sm.close(); } catch (Exception ignored) { }
        }
        System.out.println("Done.");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RaftNode findLeader(RaftNode... nodes) {
        for (RaftNode n : nodes) {
            try {
                if (n.role() == RaftRole.LEADER) return n;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void printRoles(String label, RaftNode n1, RaftNode n2, RaftNode n3) {
        System.out.printf("%n[%s]%n", label);
        for (RaftNode n : new RaftNode[]{n1, n2, n3}) {
            String role;
            try {
                role = n.role().name();
            } catch (Exception e) {
                role = "CLOSED";
            }
            System.out.printf("  %-8s term=%-3s role=%s%n",
                    n.nodeId(), termOrUnknown(n), role);
        }
    }

    private static String termOrUnknown(RaftNode n) {
        try { return String.valueOf(n.currentTerm()); }
        catch (Exception e) { return "?"; }
    }

    private static void printValue(String label, StorageEngine sm, String key) {
        Optional<byte[]> val = sm.get(key.getBytes());
        System.out.printf("  %-10s → %s%n", label,
                val.map(String::new).orElse("(not found)"));
    }

    private static StorageEngine getOtherSurvivor(
            RaftNode n1, RaftNode n2, RaftNode n3,
            RaftNode dead, RaftNode newLeader,
            StorageEngine s1, StorageEngine s2, StorageEngine s3) {
        for (int i = 0; i < 3; i++) {
            RaftNode n = i == 0 ? n1 : i == 1 ? n2 : n3;
            StorageEngine s = i == 0 ? s1 : i == 1 ? s2 : s3;
            if (n != dead && n != newLeader) return s;
        }
        return s1;
    }
}
```

---

## Step 2: Run it

**From your IDE (recommended):**
Open [RaftClusterDemo.java](../../kiradb-raft/src/test/java/io/kiradb/raft/RaftClusterDemo.java),
right-click the `main()` method → **Run**.

**From the terminal:**
```bash
./gradlew :kiradb-raft:test \
  -PjvmArgs="--enable-preview" \
  --rerun-tasks 2>&1 | grep -v "^>"
```

> The demo is in `src/test/` so it has access to the test classpath (LsmStorageEngine etc.)
> but it is not a JUnit test — run it via the IDE `main()` for the cleanest output.

---

## What you should see

```
Data dir: /tmp/kiradb-demo-12345678

--- Starting 3-node cluster ---
Waiting for leader election (max 2s)...

[After initial election]
  node-1   term=1   role=LEADER
  node-2   term=1   role=FOLLOWER
  node-3   term=1   role=FOLLOWER

--- Writing 'city' = 'Bangalore' to leader (node-1) ---
Write committed.

--- Reading 'city' from all 3 storage engines ---
  node-1     → Bangalore
  node-2     → Bangalore
  node-3     → Bangalore

--- Killing leader (node-1) ---
Waiting for new leader (max 1s)...

[After failover]
  node-1   term=?   role=CLOSED
  node-2   term=2   role=LEADER
  node-3   term=2   role=FOLLOWER

--- Writing 'status' = 'recovered' to new leader (node-2) ---
Write committed on new leader.

--- Final state of surviving nodes ---
Survivor node-2:
  city       → Bangalore
  status     → recovered
Other survivor:
  city       → Bangalore
  status     → recovered

--- Shutting down ---
Done.
```

---

## What each step proves

| Step | Raft property being tested |
|---|---|
| Leader elected in <1s | Randomized election timeouts work |
| All 3 nodes read `Bangalore` | Log replication: AppendEntries delivered to all followers |
| New leader has higher term | Terms are monotonically increasing |
| Write succeeds after failover | Cluster is available as long as majority (2 of 3) are alive |
| Both `city` and `status` survive | Previously committed entries are never lost |

---

## Experiment: what happens if you kill 2 nodes?

Modify the demo to close two nodes after the initial write:

```java
node1.close();  // kill leader
node2.close();  // kill a follower too

Thread.sleep(1_000);

// Only node3 is alive — it can't reach majority (needs 2 of 3)
// node3 will keep starting elections and incrementing term, but never win
System.out.println("node-3 role: " + node3.role());  // CANDIDATE, never LEADER

// This will timeout after 5 seconds — no majority available
node3.propose(LogEntry.put("key".getBytes(), "value".getBytes(), -1L))
     .get(5, TimeUnit.SECONDS);  // throws TimeoutException
```

This demonstrates Raft's **availability trade-off**: it requires a majority to make progress.
With only 1 of 3 nodes alive, the cluster stops accepting writes — protecting consistency
over availability. No split-brain, no data corruption. Just a clean timeout.

---

## Experiment: observe term numbers

Add this loop before killing the leader:

```java
// Write 5 more keys and watch log index grow
for (int i = 0; i < 5; i++) {
    leader.propose(LogEntry.put(
        ("key-" + i).getBytes(), ("val-" + i).getBytes(), -1L))
        .get(3, TimeUnit.SECONDS);
}
// After these 5 writes + 1 NOOP on election = log index is now at 7
// All followers have identical logs up to index 7
```

Kill the leader, then write to the new leader and watch the term increment from 1 → 2.
Each election always produces a strictly higher term — that is what prevents split-brain.

---

## Ports used

| Node | Raft RPC port |
|---|---|
| node-1 | 17400 |
| node-2 | 17401 |
| node-3 | 17402 |

If you see `Address already in use`, another test is holding the port. Change the constants at
the top of `RaftClusterDemo` to any free ports above 10000.
