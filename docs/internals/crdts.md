# CRDTs in KiraDB

> A CRDT is a data structure whose `merge` operation is **commutative**, **associative**, and **idempotent**. The math guarantees that any two replicas which have seen the same set of updates converge to the same state — regardless of message order, duplication, or partition length.

This doc covers the five CRDTs implemented in `kiradb-crdt`: `GCounter`, `PNCounter`, `LWWRegister`, `MVRegister`, `ORSet`, plus the `VectorClock` building block underneath `MVRegister`.

For the user-facing commands, see the [`CRDT.*` section in the command reference](../commands/reference.md#crdt-v050).

---

## Why CRDTs alongside Raft?

Raft (Phase 4) gives strong consistency through a single leader: every write goes through one node, gets ordered, gets replicated. Beautiful for state where order matters (cluster membership, schema, leadership of a key range).

But Raft has a cost:
- **Round-trip per write.** A user in Tokyo writing to a leader in Virginia eats 150ms.
- **Throughput cap.** One leader serializes all writes.
- **Unavailable during partition** until a new leader is elected.

For workloads like *like counters on a viral tweet*, *online-presence sets in a chat room*, or *config-flag values changed by an admin*, you don't need a global order. You need every node to accept writes locally, and the math to guarantee convergence when nodes can talk again.

That's CRDTs. **Both** models live in the same database; you pick per data type, not for the whole system.

---

## The three magic properties

A CRDT's `merge(A, B)` function must satisfy:

| Property | Meaning |
|---|---|
| **Commutative** | `merge(A, B) == merge(B, A)` — order of arrival doesn't matter |
| **Associative** | `merge(merge(A, B), C) == merge(A, merge(B, C))` — grouping doesn't matter |
| **Idempotent** | `merge(A, A) == A` — duplicate gossip is a no-op |

If all three hold, any two replicas seeing the same updates eventually converge — no coordination needed.

KiraDB's CRDTs are all **state-based** (CvRDTs): nodes gossip the whole state and `merge` on receive. The alternative (op-based) is bandwidth-cheaper but requires causal-broadcast and exactly-once delivery — both hard. State-based wins on robustness; the bandwidth cost is rarely the bottleneck.

---

## GCounter — grow-only counter

**State:** `Map<NodeId, Long>` — one slot per writing node.
**Value:** sum of all slots.
**Merge:** element-wise max per slot.

```
Node A's view:        { node-a: 5, node-b: 3, node-c: 7 }      value = 15
Node B's view:        { node-a: 4, node-b: 8, node-c: 7 }      value = 19
After merge:          { node-a: 5, node-b: 8, node-c: 7 }      value = 20
```

**Why max-merge is safe:** only node N ever increments slot N. So if two replicas disagree on slot N, the higher value strictly contains more information — no node could have written less.

**File:** `kiradb-crdt/src/main/java/io/kiradb/crdt/GCounter.java`. The API enforces the invariant: `increment()` takes no node-id argument; the instance is bound to its own id at construction.

**Use cases inside KiraDB:** rate limiter buckets, flag impression/conversion counters.

---

## PNCounter — positive/negative counter

**State:** two `GCounter`s — one for additions (P), one for subtractions (N).
**Value:** `P.value() - N.value()`.

This is the standard "decompose what you can't do into two things you can do" pattern. A single counter that can go both up and down would not be safe under max-merge. By restricting each underlying counter to monotonic growth, the merge math stays trivially correct.

```
Increment by 5:  P slot += 5
Decrement by 2:  N slot += 2
Value:           P.sum() - N.sum() = 5 - 2 = 3
```

**File:** `PNCounter.java`. Three lines of merge, each calling already-correct `GCounter.merge`.

---

## LWWRegister — last-write-wins

**State:** `(value, timestamp, writerNodeId)`.
**Merge:** higher timestamp wins. Ties broken by lexicographically larger node id.

```java
private static boolean winsOver(long tA, String idA, long tB, String idB) {
    if (tA > tB) return true;
    if (tA < tB) return false;
    return idA.compareTo(idB) > 0;
}
```

**The honest disclaimer:** wall clocks lie. NTP drift, leap seconds, VM pauses. A write from a clock-skewed node can silently discard a more-recently-written value. **Do not** use LWW for data where loss is unacceptable; use `MVRegister` instead.

**Where LWW is fine in KiraDB:** feature flag values (admins update slowly; "last admin wins" matches the mental model). Configuration values used through the Phase 7 path.

**A real bug that hit us, fixed in Phase 7:** if a single node performs two `set()` calls within the same millisecond, the original implementation rejected the second because `idA.compareTo(idA) == 0` (not strictly greater). Fix: local writes always win over their own prior state via a monotonically-advancing timestamp. The LWW tiebreaker only meaningfully applies between *different* nodes. Regression test: `LWWRegisterTest.rapidSameNodeRewritesAllSucceed`.

---

## VectorClock — causality without wall clocks

A vector clock is a `Map<NodeId, Long>` that captures the causal order between distributed events. Three rules:

| Event | Rule |
|---|---|
| Local event on node N | `clock[N] += 1` |
| Send a message from N | Attach a copy of N's clock |
| Receive at N with attached `V` | `clock[i] = max(clock[i], V[i])` for every i, then `clock[N] += 1` |

Comparison between two clocks A and B yields one of:

- **BEFORE**: `A[i] ≤ B[i]` for all i, with at least one strict — A causally precedes B.
- **AFTER**: B precedes A.
- **EQUAL**: identical.
- **CONCURRENT**: neither dominates — events with no causal relationship.

The trick: every node only ever increments its own slot. So if two clocks disagree on slot N, the higher value strictly knows more about what happened on N. Nothing is lost.

**File:** `VectorClock.java`. Used by `MVRegister`.

---

## MVRegister — multi-value (Dynamo-paper style)

**State:** a *set* of `(value, vectorClock)` pairs.
**Write:** bump local VC, drop pairs strictly dominated by the new VC, append `(newValue, copy of VC)`.
**Merge:** union the pairs, then drop those dominated by some other pair in the union.
**Read:** return the surviving values — usually 1, more than 1 if there are unresolved concurrent writes.

The classic shopping-cart use case:

```
T1: A writes "socks"   →  A = { ("socks", {A:1}) }
T2: B writes "shirt"   →  B = { ("shirt", {B:1}) }   (concurrent — A and B haven't synced)
T3: A and B sync. Combined: { ("socks", {A:1}), ("shirt", {B:1}) }
    Neither dominates → both survive.
T4: read() → ["socks", "shirt"]. App applies its rule (union for carts), writes back "socks+shirt".
T5: A writes "socks+shirt"; A's VC is now {A:1, B:1}, then increments A → {A:2, B:1}.
    All prior pairs are now dominated → dropped.
    Final: { ("socks+shirt", {A:2, B:1}) }.
```

**Important nomenclature:** this is the *original Dynamo paper* (2007). Amazon DynamoDB the AWS product (2012) actually uses LWW by default and hides conflicts. When people say "Dynamo-style consistency" they almost always mean the paper, not the AWS service. Riak is the closest open-source heir to the paper.

**File:** `MVRegister.java`.

---

## ORSet — observed-remove set

**The trick:** every `add(x)` produces a fresh `UUID` tag. The element is "in the set" iff at least one of its tags is alive. `remove(x)` only kills the tags it has *currently observed* on this replica.

This means a concurrent `add(x)` on another replica (with a fresh UUID the remover never saw) **survives** the remove — matching real-world intent in shopping carts and presence sets ("better resurrect than lose").

```
A:  add("apple")            → liveAdds: {apple → {tag-1}}
B:  observes A's add via merge.
A:  remove("apple")         → A: liveAdds={}, tombstones={tag-1}
B:  add("apple")            → B: liveAdds: {apple → {tag-1, tag-2}}   (fresh UUID!)

After A.merge(B) and B.merge(A):
  liveAdds = {apple → {tag-1, tag-2}}
  tombstones = {tag-1}
  Strip tombstoned tags: {apple → {tag-2}}
  → "apple" survives in both replicas. ✓
```

The defining test that locks this in: `ORSetTest.concurrentAddSurvivesConcurrentRemove`.

**Known limitation:** tombstones grow forever. Real production CRDTs garbage-collect via *causal stability* — once every replica has acknowledged seeing tag T, the tombstone for T is safe to drop. This requires a per-replica gossip "I have seen up to dot D" digest plus a stability tracker. Filed in **Phase 13 — Deferred Hardening Backlog** in `CLAUDE.md`.

**File:** `ORSet.java`.

---

## CrdtStore — the orchestrator

`CrdtStore` (in `kiradb-crdt/.../CrdtStore.java`) is the layer that owns named CRDT instances and persists their state via the `StorageEngine`. Each CRDT type lives under a reserved key prefix:

```
crdt:g:<name>     →  GCounter state
crdt:pn:<name>    →  PNCounter state
crdt:lww:<name>   →  LWWRegister state
crdt:mv:<name>    →  MVRegister state
crdt:or:<name>    →  ORSet state
```

CRDT instances are cached in-memory per name (lazy-loaded on first read). After every mutation, the new state is serialized and written back to storage. `ConcurrentHashMap.computeIfAbsent` ensures two threads racing on the same name converge on a single instance — no torn state.

The **identity invariant** is critical: `localNodeId` is supplied at construction and must be **stable across restarts**. A drifting node id splits one logical node into two — counters double, set elements duplicate. KiraDB resolves the id from `-Dkiradb.node.id`, falling back to the hostname.

---

## What's deferred

These items are tracked in the Phase 13 hardening backlog in `CLAUDE.md`, each with an explicit *Trigger* condition for when to pull them forward:

- **Real cluster gossip wire** — Phase 6 proves convergence in tests by calling `merge()` directly. Production needs node-to-node gossip; Raft `AppendEntries` piggybacking is one option.
- **ORSet tombstone GC** via causal stability.
- **Generalize `CRDT.MERGE` wire command** beyond `GCOUNTER` (currently the only supported type).
- **Dotted version vectors** to handle vector-clock explosion when many concurrent clients write to the same `MVRegister`.

---

## Reading list

- *Conflict-Free Replicated Data Types* — Shapiro et al., 2011 (the canonical paper)
- *Dynamo: Amazon's Highly Available Key-Value Store* — DeCandia et al., 2007 (where MVRegister came from)
- *Riak* documentation on siblings / dotted version vectors (production hardening of MVRegister)
- The kiradb-crdt unit tests under `kiradb-crdt/src/test/java/io/kiradb/crdt/` — every test is a worked example of one property
