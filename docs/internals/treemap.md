# TreeMap — How It Works and Why KiraDB Uses It

> This document explains the TreeMap data structure from first principles,
> then shows exactly where and why KiraDB uses it in the storage engine.

---

## The Problem: You Need Sorted Order

Imagine you have 1 million key-value pairs in memory. You need to:

1. Insert a new key-value pair — fast.
2. Look up a key by exact match — fast.
3. Iterate all keys **in alphabetical order** — fast.
4. Get all keys between `"apple"` and `"mango"` — fast.

A `HashMap` handles 1 and 2 in O(1), but completely fails at 3 and 4 — it has no order.
A sorted array handles 3 and 4, but insertion is O(n) because you have to shift elements.
A `TreeMap` handles all four in O(log n).

---

## What a TreeMap Is: A Red-Black Tree

A `TreeMap` is backed by a **Red-Black Tree** — a self-balancing binary search tree.

### Binary Search Tree basics

Every node has:
- A key
- A value
- A left child (all keys smaller)
- A right child (all keys larger)

```
          [mango]
         /       \
     [banana]   [zebra]
     /     \
  [apple]  [grape]
```

Looking up `grape`:
- Start at `mango` — `grape < mango`, go left
- At `banana` — `grape > banana`, go right
- At `grape` — found! 3 steps for 5 nodes.

This is O(log n) because at each step you eliminate half the remaining nodes.

### The Problem with Unbalanced Trees

Naive insertion breaks this guarantee. Insert in alphabetical order:

```
[apple]
      \
    [banana]
          \
        [cherry]
              \
            [grape]  ← now it's just a linked list: O(n) to find anything
```

### How Red-Black Tree Fixes This

A Red-Black Tree adds two rules:

1. Every node is colored **Red** or **Black**.
2. After every insert or delete, the tree **rebalances itself** using rotations to ensure no path from root to leaf is more than twice as long as any other.

The result: the tree height is always ≤ 2 log₂(n).
For 1 million entries: at most 40 steps for any operation.
For 1 billion entries: at most 60 steps.

You never need to think about the rotations — `TreeMap` handles them internally.

---

## TreeMap API and Complexity

```java
TreeMap<String, Integer> map = new TreeMap<>();

map.put("banana", 2);    // O(log n)
map.put("apple", 1);     // O(log n)
map.put("cherry", 3);    // O(log n)

map.get("banana");       // O(log n) → 2
map.containsKey("grape");// O(log n) → false

// The unique power of TreeMap: ordered views
map.firstKey();          // "apple"
map.lastKey();           // "cherry"
map.headMap("cherry");   // {"apple"→1, "banana"→2}  — keys < "cherry"
map.tailMap("banana");   // {"banana"→2, "cherry"→3} — keys ≥ "banana"
map.subMap("apple", "cherry"); // {"apple"→1, "banana"→2} — [apple, cherry)

// Iteration is always in sorted key order
for (Map.Entry<String, Integer> e : map.entrySet()) {
    System.out.println(e.getKey());  // apple, banana, cherry
}
```

**Compare with HashMap:**

| Operation | HashMap | TreeMap |
|-----------|---------|---------|
| put | O(1) avg | O(log n) |
| get | O(1) avg | O(log n) |
| Ordered iteration | ✗ not possible | ✓ always sorted |
| Range queries | ✗ not possible | ✓ headMap/tailMap/subMap |
| firstKey/lastKey | ✗ not possible | ✓ O(log n) |

---

## Custom Comparators — The Critical Detail

By default, `TreeMap` sorts by the natural ordering of the key type. For `String`, that's Unicode code point order. For `Integer`, that's numeric order.

But KiraDB uses `byte[]` as keys — and **byte arrays have no natural ordering in Java**. Two `byte[]` variables with identical contents are not equal by `==` because Java arrays use reference equality (memory address), not value equality.

```java
byte[] a = "hello".getBytes();
byte[] b = "hello".getBytes();
a == b;           // false — different objects in memory
a.equals(b);      // false — Object.equals() uses reference equality for arrays
Arrays.equals(a, b); // true — but TreeMap doesn't use this
```

This means if you try `new TreeMap<byte[], String>()`, lookups will always fail — the tree compares references, not content.

The fix: provide a custom `Comparator`:

```java
private static final Comparator<byte[]> KEY_ORDER = Arrays::compare;

private final TreeMap<byte[], StorageEntry> data = new TreeMap<>(KEY_ORDER);
```

`Arrays.compare(a, b)` does **lexicographic byte-by-byte comparison** — the same ordering used in dictionaries, SSTable files, and databases everywhere. This is correct and consistent.

---

## Where KiraDB Uses TreeMap

### 1. MemTable ([MemTable.java](../../kiradb-core/src/main/java/io/kiradb/core/storage/memtable/MemTable.java))

```java
private final TreeMap<byte[], StorageEntry> data = new TreeMap<>(KEY_ORDER);
```

**Why TreeMap here specifically:**

When the MemTable is full and needs to be flushed to an SSTable, the SSTable writer iterates all entries in **sorted key order**. The SSTable binary format stores entries sorted — this is what enables binary search when reading.

```java
// SSTableWriter.java
Iterator<StorageEntry> it = memTable.iterator(); // TreeMap gives sorted iteration
while (it.hasNext()) {
    writeDataEntry(out, it.next()); // written in key order to disk
}
```

If we used a `HashMap`, we'd have to sort all entries before writing — an extra O(n log n) step on every flush. With `TreeMap`, the sort is free because the structure is always sorted. **The sort happened incrementally at insert time (O(log n) per insert) rather than all at once at flush time.**

The `scan(startKey, endKey)` method also becomes trivial:
```java
// MemTable.java
public Iterator<StorageEntry> scan(byte[] startKey, byte[] endKey) {
    Map<byte[], StorageEntry> range;
    if (startKey == null && endKey == null) {
        range = data;
    } else if (startKey == null) {
        range = data.headMap(endKey);       // O(log n) to find boundary
    } else if (endKey == null) {
        range = data.tailMap(startKey);
    } else {
        range = data.subMap(startKey, endKey);
    }
    return range.values().stream()
            .filter(StorageEntry::isAlive)
            .iterator();
}
```

`subMap`, `headMap`, `tailMap` are O(log n) to find the boundary, then O(k) to iterate k results. With a HashMap, a range query requires scanning every entry: O(n).

### 2. InMemoryStorageEngine ([InMemoryStorageEngine.java](../../kiradb-server/src/main/java/io/kiradb/server/storage/InMemoryStorageEngine.java))

```java
// For the scan() method — range queries over all stored keys
TreeMap<String, Entry> sorted = new TreeMap<>(store); // snapshot copy
Map<String, Entry> range = sorted.subMap(start, end);
```

The main store is a `ConcurrentHashMap` (concurrent writes + reads), but when a range scan is needed, a snapshot is taken into a `TreeMap` for ordered iteration.

---

## The Key Insight: Why Sort Matters for LSM Trees

The entire LSM Tree design depends on sorted order at every layer:

```
MemTable (TreeMap, sorted in memory)
    │
    │  flush when full
    ▼
SSTable (sorted on disk — written from TreeMap's sorted iteration)
    │
    │  binary search on reads (only possible because file is sorted)
    ▼
Compactor merges SSTables (N-way merge sort — merging sorted files is O(n))
```

If any layer lost sorted order, the next layer breaks:
- SSTable can't be binary searched → must scan every byte → reads become O(n) per file
- Compaction can't use merge sort → must sort again → O(n log n) instead of O(n)

TreeMap is the foundation that makes the whole chain work.

---

## When to Use TreeMap vs HashMap

Use **HashMap** when:
- You only do exact-match lookups and inserts
- You never need keys in order
- Performance is critical and O(1) matters more than O(log n)
- Example: `CommandRouter.handlers` — you only look up commands by exact name

Use **TreeMap** when:
- You need to iterate keys in sorted order
- You need range queries (give me all keys between X and Y)
- You need min/max key (`firstKey()`, `lastKey()`)
- You need sorted iteration as a free byproduct of inserts (like flushing a MemTable)
- Example: `MemTable.data` — every flush requires sorted iteration

Use **LinkedHashMap** when:
- You need insertion-order iteration (not sorted, but predictable)
- Example: LRU caches

---

## Visualizing a MemTable Put

```
Initial tree (empty):
  (nothing)

put("mango", v1):
  [mango]

put("apple", v2):
  [mango]
  /
[apple]

put("zebra", v3):
  [mango]
  /     \
[apple]  [zebra]

put("banana", v4):
         [mango]           ← Red-Black rotation happened here to keep balanced
        /       \
    [banana]   [zebra]
    /
 [apple]

Iteration order from TreeMap.values().iterator():
  apple → banana → mango → zebra    ← always sorted, no extra work
```

This sorted iteration is exactly what `SSTableWriter` reads to write the SSTable file.

---

## Summary

| Concept | Detail |
|---------|--------|
| Data structure | Red-Black Tree (self-balancing BST) |
| All operations | O(log n) |
| Sorted iteration | Free — always in key order |
| Range queries | O(log n + k) where k = number of results |
| Custom comparator | Required for `byte[]` keys — use `Arrays::compare` |
| KiraDB MemTable use | Guarantees SSTable files are written in sorted order without an extra sort step |
| Alternative | HashMap — O(1) but no ordering, no range queries |
