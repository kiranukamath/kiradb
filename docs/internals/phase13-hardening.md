# Phase 13 — Deferred Hardening Backlog: Design Notes & Deep Dive

> Written during the autonomous build (2026-07-05), on branch `semantic-cache`.
> Scope: select items from CLAUDE.md's Phase 13 backlog, implemented one at a
> time with `./gradlew build` kept green after each. See CLAUDE.md's
> "PHASE 13 — Deferred Hardening Backlog" section for the original trigger
> conditions and design discussion each item references.

---

## What was implemented (7 of 7 priority items)

| # | Item | Module(s) | Status |
|---|---|---|---|
| 1 | Persistent flag-name index for `FLAG.LIST` | kiradb-services, kiradb-crdt | Done |
| 2 | `CFG.ROLLBACK scope key versionsBack` | kiradb-services, kiradb-server, kiradb-client | Done |
| 3 | Byte-based MemCache capacity | kiradb-core | Done |
| 4 | AccessTracker hard cap | kiradb-core | Done |
| 5 | Token bucket rate limiter (single-node-correct, scoped down) | kiradb-services, kiradb-server | Done |
| 6 | Generalize `CRDT.MERGE` beyond GCounter | kiradb-crdt, kiradb-server | Done |
| 7 | Metric publication (Micrometer) | kiradb-core, kiradb-server | Done |

All 7 priority items from the task list shipped. Nothing was skipped due to
budget. The "skip entirely" list (ORSet tombstone GC, real cluster gossip wire,
dotted version vectors for MVRegister, hierarchical config fallback,
ConflictResolver strategies, interned key storage, off-heap value storage) was
correctly left untouched, per CLAUDE.md's own instruction that these need
production signals before they're worth building. Their triggers are restated
in "What's still deferred" below.

---

## 1. Persistent flag-name index for `FLAG.LIST`

**File:** `kiradb-services/src/main/java/io/kiradb/services/flags/FlagStore.java`

### The bug being fixed

`FlagStore.knownFlags` was a plain in-memory `ConcurrentHashMap<String, Boolean>`,
populated lazily on every `set()`/`get()`. If a node restarted and a flag
existed on disk (as an `LWWRegister`) but no client had touched it yet on the
*new* process, `FLAG.LIST` simply wouldn't show it — the in-memory index had no
way to know the flag existed without being told.

### The fix: an ORSet as a durable name index

Every flag name that's ever been `set()` is now *also* added to an
`io.kiradb.crdt.ORSet` named `flag:_index` (via `CrdtStore.orSetAdd`), which is
already persisted by `CrdtStore` the same way GCounters and LWWRegisters are.
On `FlagStore` construction, the in-memory `knownFlags` cache is seeded by
reading `crdtStore.orSet("flag:_index").elements()` — so a fresh `FlagStore`
instance (simulating a restart, since a real restart just means "new process,
same storage") sees every flag name immediately, without needing to have
re-read or re-written any of them.

```java
public FlagStore(final CrdtStore crdtStore) {
    this.crdtStore = Objects.requireNonNull(crdtStore, "crdtStore");
    for (String name : crdtStore.orSet(FLAG_INDEX_NAME).elements()) {
        knownFlags.put(name, Boolean.TRUE);
    }
}
```

### Why ORSet and not, say, a GCounter or a plain persisted `Set<String>`

- **GCounter** doesn't hold arbitrary strings — it's a monotonic number.
- **A hand-rolled persisted `Set<String>`** would need its own serialize/merge
  logic, duplicating what ORSet already does correctly (see Phase 6). ORSet is
  the *set* CRDT in the toolbox; using anything else would be reinventing it
  with worse testing.
- ORSet's `add()` is idempotent in effect (each add still burns a UUID tag, see
  below), so it's safe to call repeatedly without corrupting anything.

### The one deliberate optimization: don't index on every `set()`

A naive implementation would call `orSetAdd` on every `FLAG.SET`, including
re-sets of an already-known flag (e.g. an admin flipping a rollout percentage
ten times a day). Each `ORSet.add()` call mints a fresh UUID tag that lives
forever (see Phase 6's documented tombstone-growth limitation, restated below).
Ten years of a popular flag being tweaked daily would mean ten years of
add-tags for a single flag name, none of which ever get removed.

The fix is a guard: `orSetAdd` is only called the *first* time a given name is
seen in `knownFlags` (checked via `Map.put`'s return value being `null`):

```java
if (knownFlags.put(flag.name(), Boolean.TRUE) == null) {
    crdtStore.orSetAdd(FLAG_INDEX_NAME, flag.name());
}
```

This keeps the ORSet's growth proportional to the number of *distinct flag
names ever created*, not the number of `FLAG.SET` calls — which is the
actually-interesting cardinality for an index.

### What a reviewer should scrutinize

- This still doesn't solve ORSet tombstone growth in general — see "skip
  entirely" list. For a flag index specifically, growth is capped by "number
  of flags a team creates", which is realistically in the hundreds-to-low-
  thousands even for a large org, so this is a fine trade for this use case
  specifically, even though the general-purpose ORSet tombstone problem is
  still open.
- Multi-node correctness: if two nodes each independently `set()` a
  brand-new flag `"foo"` before gossip has propagated, both will add `"foo"`
  to their local ORSet with different tags. On merge, both tags survive (as
  they should — ORSet doesn't dedupe by value, only by tag), but
  `elements()` correctly reports `"foo"` exactly once (elements are deduped,
  tags are not) — so this doesn't cause double-listing. Verified implicitly
  by ORSet's existing Phase 6 test suite; not re-tested here since the
  merge/convergence math didn't change.

### Test coverage

`FlagStoreTest` gained:
- `listFlagsSurvivesRestartEvenForUntouchedFlags` — the flagship test:
  constructs a `FlagStore` over a `FakeStorage`, sets 3 flags, then builds a
  **second, independent** `CrdtStore` + `FlagStore` pair over the *same*
  backing storage (this is the realistic simulation of "process restarted,
  disk survived") and asserts `listFlags()` sees all 3 names with no prior
  in-memory state carried over.
- `reSettingAnExistingFlagDoesNotDuplicateIndexEntries` — guards the
  optimization above; re-setting one flag three times still yields exactly
  one entry in `listFlags()`.

---

## 2. `CFG.ROLLBACK scope key versionsBack`

**Files:** `ConfigStore.java` (kiradb-services), `ConfigHandler.java` +
`KiraDBServer.java` (kiradb-server), `ConfigClient.java` (kiradb-client).

### Design: rollback is itself a new version, never a rewrite

`ConfigStore`'s entire design is append-only — `history()` never shrinks, and
every `CFG.SET` adds a version rather than mutating one. Rollback had to honor
that invariant to be consistent with the rest of the class, so
`ConfigStore.rollback(scope, key, versionsBack)`:

1. Loads the full version list for `(scope, key)`.
2. Resolves `targetIndex = versions.size() - 1 - versionsBack` — `versionsBack=0`
   means "the current value" (still appends a new version, even though the
   value doesn't change — the audit trail records the operator's rollback
   *action*, not just a value transition).
3. Reads `versions.get(targetIndex).value()`.
4. Appends that value as a **new** version via the same code path `set()` uses
   internally (bump `versionNumber`, stamp `System.currentTimeMillis()`,
   persist, notify listeners).
5. Returns `Optional.empty()` if `versionsBack` doesn't address an existing
   version (negative, or larger than the history length, or the key has no
   history at all) — no new version is appended in that case.

```java
int targetIndex = versions.size() - 1 - versionsBack;
if (targetIndex < 0 || targetIndex >= versions.size()) {
    return Optional.empty();
}
```

### Why this matters: rollback triggers `CFG.WATCH` pushes for free

Because `rollback()` reuses the exact same "append + persist + notifyListeners"
tail as `set()`, a client watching a scope via `CFG.WATCH` gets a
`CFG.NOTIFY` push when someone rolls a config back — no special-casing needed
in `ConfigSubscriptionRegistry`. This is a case where reusing the existing
code path (rather than writing a bespoke rollback-specific write) bought
correctness for a feature (`CFG.WATCH` interop) that wasn't even the task at
hand.

### Wire format

`CFG.ROLLBACK scope key versionsBack` returns the new version number as a
RESP3 integer (matching `CFG.SET`'s reply shape) or RESP3 nil if
`versionsBack` didn't address an existing version — following the same
"integer on success, nil on absence" convention `CFG.GET` already uses.

### SDK

`ConfigClient.rollback(scope, key, versionsBack)` returns `Optional<Long>`
(empty on nil), matching the existing fluent-client conventions in that class
(`get()` also returns `Optional`).

### What a reviewer should scrutinize

- `versionsBack` is parsed as `int` on the wire (`Integer.parseInt`), while
  version *numbers* internally are `long`. This is intentional — nobody
  reasonably has more than ~2 billion versions of one config key, and using
  `int` for the wire argument keeps the RESP3 parsing simple. If that
  assumption is ever wrong, it's a one-line change to `Long.parseLong`.
- No `ConflictResolver` is invoked during rollback — same as every other
  `ConfigStore` write today. This is intentionally deferred (see "skip
  entirely" list); rollback doesn't introduce a new conflict class beyond
  what plain `set()` already has (none, today).

### Test coverage

`ConfigStoreTest` gained 5 tests: happy path (rollback 2 versions back,
verify value + history length grows by exactly one), `versionsBack=0`
re-applies the current value as a new version, `versionsBack` beyond history
returns empty *and appends nothing*, rollback on a completely absent key
returns empty, and negative `versionsBack` returns empty.

`ConfigIntegrationTest` gained 2 tests over the real Netty wire path:
rollback appends correctly and `CFG.GET`/`CFG.HIST` reflect it, and
out-of-range `versionsBack` returns a RESP3 nil (Jedis surfaces this as
Java `null`).

---

## 3. Byte-based MemCache capacity

**File:** `kiradb-core/src/main/java/io/kiradb/core/storage/tier/MemCache.java`

### The problem this solves

The Phase 5 design capped MemCache capacity by **entry count**
(`maxEntries`). CLAUDE.md's own memory-footprint math (Phase 5 section) shows
per-entry RAM cost scales with `key.length + value.length` — so a cache
holding 1M 10-byte counters and a cache holding 1M 10KB JSON blobs consume
wildly different amounts of heap for the *same* `maxEntries`, even though the
whole point of the "35% of JVM heap" sizing rule is to track an actual byte
budget.

### The fix: a second capacity mode, not a replacement

`MemCache` now supports two mutually exclusive modes:

- **Entry-count mode** (unchanged): `new MemCache(maxEntries, tracker)`.
- **Byte-budget mode** (new): `MemCache.ofMaxBytes(maxBytes, tracker)`.

The mode is chosen at construction and fixed for the instance's lifetime;
`isByteBudgetMode()` reports which one is active. `TieredStorageEngine`'s
existing constructors are untouched (still entry-count mode) — this was a
deliberate scoping choice from the task: don't force every existing caller to
migrate, add the new mode alongside.

### The per-entry byte estimate

```
estimatedEntryBytes(key, value) = key.length + value.length + PER_ENTRY_OVERHEAD_BYTES
PER_ENTRY_OVERHEAD_BYTES = 78
```

The `78` constant is lifted directly from CLAUDE.md's Phase 5 memory-footprint
table (`~78 + ceil8(K+16) + ceil8(V+16)` bytes for a HOT MemCache entry). We
deliberately do **not** replicate the `ceil8()` 8-byte alignment rounding on
key/value lengths here — this is a *budget estimate* used to decide when to
evict, not an exact heap accounting tool. Getting it exactly right would
require tracking JVM object header layout details (compressed oops on/off,
specific JDK build) that aren't worth the complexity for a "roughly track my
byte budget" cache.

### Eviction: looped, not single-shot

In entry-count mode, one eviction always makes room for exactly one new entry
(1-for-1 swap). In byte-budget mode this isn't true — an incoming 10KB value
might need to evict *several* small entries to fit. `put()` in byte-budget
mode loops `evictOne()` until either the incoming entry fits under budget or
there's nothing left to evict:

```java
while (currentBytes.get() - currentSizeOf(cacheKey) + incomingBytes > maxBytes
        && evictOne()) {
    continue;
}
```

`currentSizeOf(cacheKey)` handles the tricky case where the incoming write is
a *replacement* of an existing key — we don't want to double-count or
double-subtract that key's old size, especially since `evictOne()` might
itself pick this exact key as the lowest-score victim (if it happens to be the
coldest key in the cache). Re-querying `currentSizeOf` after each eviction
loop iteration, rather than tracking "did we replace it" as a separate boolean,
sidesteps that edge case entirely — whatever's left in the map for that key
(0 bytes if it got evicted, its old size if it survived) is exactly the amount
that needs "backing out" before adding the incoming size.

### The one behavior worth calling out explicitly

If a single incoming entry is larger than the *entire* configured byte
budget, it is still admitted — MemCache evicts everything else and accepts
being temporarily over budget rather than rejecting the write outright. This
matches the class's existing "best-effort cache, not authoritative storage"
philosophy (Tier 2 always has the real copy) and is documented in the class
Javadoc and covered by
`byteBudgetModeAllowsTemporaryOverBudgetWhenSingleEntryExceedsCapacity`.

### What a reviewer should scrutinize

- `currentBytes` is an `AtomicLong`, updated in several places (`put`,
  `remove`, `evictOne`, `clear`). Under concurrent puts to *different* keys,
  the byte accounting is exact-modulo-races the same way entry-count mode's
  `cache.size() >= maxEntries` check already was — both are "best effort,
  cache may transiently exceed budget under concurrency" by design, not a new
  weakness introduced here.
- The byte estimate ignores CRC32/WAL/SSTable overhead — that's Tier 2's
  concern (a separate ~70 B/entry estimate already documented in CLAUDE.md's
  Phase 5 section), not MemCache's.

### Test coverage

`MemCacheTest` gained 9 tests covering: default mode is still entry-count
(regression guard), running byte-size tracking on put, eviction of the
lowest-score entry when a budget-crossing put arrives, multi-entry eviction
for one oversized incoming value, the "still admits an entry bigger than the
whole budget" edge case, remove/replace decrementing the running total
correctly, plus hit/miss/eviction counters added for item #7 below.

---

## 4. AccessTracker hard cap

**File:** `kiradb-core/src/main/java/io/kiradb/core/storage/tier/AccessTracker.java`

### The problem this solves

`AccessTracker`'s only defense against unbounded growth was `TierManager`'s
5-minute purge cycle, which drops any tracked key whose decayed score falls
below `MIN_TRACK_SCORE`. Between purge cycles, a pathological access pattern —
a full-keyspace scan that touches every key exactly once and never returns —
adds one tracker entry per key touched, none of which are purgeable yet
(they all have a nonzero score right after being touched). A large-enough scan
between purge cycles can push AccessTracker's heap footprint arbitrarily high.

### The fix: a hard ceiling with LRU-by-recency eviction

`AccessTracker` now has a second constructor, `AccessTracker(int maxTrackedEntries)`.
The no-arg constructor still exists and is unbounded (`UNBOUNDED = -1`) —
**deliberately kept as the default** so tests and any external callers that
construct a bare `AccessTracker()` don't silently start dropping entries; the
hard cap must be opted into explicitly.

`TieredStorageEngine` opts in automatically, using the recommended default:

```java
this.accessTracker = new AccessTracker(
        AccessTracker.recommendedMaxTrackedEntries(maxCacheEntries));
// recommendedMaxTrackedEntries(n) = 10 * n, per CLAUDE.md's Phase 13 note
```

When `recordAccess()` is called for a genuinely new key and the tracker is at
capacity, the tracked entry with the **oldest `lastAccessMs`** is evicted
first — i.e., LRU by *recency of last touch*, not by hit count. A key
accessed 1000 times two hours ago is evicted before a key accessed once thirty
seconds ago, which is the correct behavior for a cap whose whole purpose is
"forget what hasn't mattered recently," matching the same time-decay
philosophy the scoring formula itself already uses.

### Why O(n) scan instead of a min-heap

This is called out explicitly in both the code and CLAUDE.md's original
backlog note, and worth repeating here: eviction only fires when the tracker
is genuinely full, and with the generous 10x default headroom, hitting that
ceiling should be rare in realistic workloads (it's specifically a backstop
against pathological scans, not the steady-state code path). A linear scan
for the minimum `lastAccessMs` is the simplest correct implementation and
avoids maintaining a second data structure — a min-heap keyed on
`lastAccessMs` — that itself needs an update on *every* access (since every
touch changes a key's position in recency order), which would add overhead to
the hot path (`recordAccess`) to speed up a cold path (eviction-at-cap). If
profiling ever shows this path actually running hot, the documented upgrade
is a min-heap with lazy deletion.

### What a reviewer should scrutinize

- The eviction check only fires for **new** keys (`!data.containsKey(byteKey)`).
  Repeated access to an already-tracked key never triggers eviction, even
  at capacity — correct, since re-touching an existing key doesn't grow the
  tracker. Covered by `hardCapDoesNotEvictOnRepeatedTouchOfExistingKey`.
- `TieredStorageEngineTest`'s existing tests didn't need changes — the hard
  cap is generous enough (10x a MemCache configured for 10 entries = 100
  tracked keys) that none of the existing small-scale tests come close to it.

### Test coverage

`AccessTrackerTest` gained 6 tests: unbounded-by-default regression guard,
the `10x` recommended-multiplier formula, hard cap actually bounds tracker
size under a flood of distinct keys, eviction picks the oldest-*touched* key
(not oldest-inserted) by re-touching one key and verifying it survives while
an untouched sibling gets evicted, repeated touches of an existing key never
trigger eviction, and the constructor rejects `maxTrackedEntries < 1`.

---

## 5. Token bucket rate limiter (scoped down to single-node-correct)

**Files:** `TokenBucket.java` (new, kiradb-services), `RateLimitHandler.java`
(kiradb-server).

### Why token bucket alongside sliding window

`RateLimiterStore`'s sliding-window-counter (Phase 7) enforces a strict cap
per period but has no burst tolerance — a client idle for 59 seconds of a
100-per-minute window still can't send more than ~100 requests in a sudden
burst; it's throttled based purely on the rolling window math, not on unused
"saved-up" capacity. Token bucket is the classic alternative: capacity
accumulates as tokens up to a burst ceiling while idle, then drains at a
steady refill rate — the algorithm behind most CDN edge limiters and network
traffic shapers.

### The explicit scope-down: single-node-correct only

CLAUDE.md's own backlog note calls out that **true cross-node-correct** token
bucket is the hard version — naive per-node buckets allow up to
`numNodes × burst` of allowance briefly when a burst hits multiple nodes at
once, and real correctness needs either virtual tokens refilled from a
wall-clock origin all nodes can compute deterministically (reconciled via
CRDT merge) or routing through a single coordinator. Given the budget for this
pass, `TokenBucket` implements **only** the single-node-correct version: each
KiraDB process holds its own unreplicated in-memory bucket state.

This is documented prominently — in the class Javadoc, in the constructor
comments, and in `RateLimitHandler`'s command-summary Javadoc — specifically
so nobody mistakes "shipped" for "cross-node correct." Behind a single node it
is exactly correct; behind an N-node fleet with no shared state, a client
round-robining across nodes can receive up to N× the configured burst in the
worst case. **True cross-node correctness remains Phase 13 backlog** — this
implementation doesn't reduce the size of that remaining work, it just gives
operators the "smooth burst" algorithm shape today for single-node or
per-process use cases where global coordination isn't the point.

### The algorithm

```
tokens(t) = min(burstCapacity, tokens(lastRefill) + (t - lastRefill) * refillRatePerMs)
tryConsume() = tokens available >= 1 ? consume 1, allow : deny
```

Refill is computed **lazily** from elapsed wall-clock time on each call — there
is no background ticking thread topping up buckets on a schedule. This keeps
the implementation exact (no timer drift, no wasted CPU ticking idle buckets)
at the cost of an untouched bucket not "visibly" refilling until the next
check, which is fine since nothing observes an idle bucket anyway.

Per-bucket state (`tokens`, `lastRefillMillis`) lives in an
`AtomicReference<State>`, updated via `updateAndGet`. Because
`updateAndGet`'s update function can be invoked multiple times under
contention (it retries on CAS failure) and must be side-effect-free, whether a
token was actually consumed is captured through a one-element array closed
over by the lambda, inspected *after* the CAS settles — this avoids the bug
of a losing CAS retry "phantom consuming" a token that never actually got
persisted.

### Wire integration: an algorithm selector, not a new command family

Rather than add `TB.ALLOW`/`TB.STATUS` as parallel commands, `RL.ALLOW` and
`RL.STATUS` gained an optional 5th argument: `SLIDING` (default, backward
compatible with every existing caller) or `TOKEN`. This was a deliberate API
choice — token bucket and sliding window answer the same question ("is this
request allowed, and what does it cost"), just with different math, so they
belong under the same command family with an algorithm switch rather than as
a wholly separate namespace a caller has to know to look for. For `TOKEN`,
`limit` is reinterpreted as burst capacity and `periodSec` as "seconds over
which that many tokens refill" (i.e. `refillRate = limit / periodSec`
tokens/second) — this keeps the 4 required positional arguments meaningful
for both algorithms without needing a 5-argument minimum even for the default
case.

### What a reviewer should scrutinize

- `RL.RESET` still returns the same "not implemented" error for both
  algorithms, even though `TokenBucket` state — unlike GCounter — genuinely
  *could* be reset (it's plain local `AtomicReference` state, not a grow-only
  CRDT). No wire command was wired up for that in this pass; `TokenBucket`
  does expose a package-visible `clear()` for tests/admin tooling that a
  future `RL.RESET` could call.
- Unknown algorithm strings return a RESP3 error (`unknown rate limit
  algorithm 'X' — expected SLIDING or TOKEN`) rather than silently defaulting
  to one or the other — fail loud on a typo rather than silently enforcing
  the wrong policy.

### Test coverage

`TokenBucketTest` (new, 8 tests): burst up to capacity succeeds, request
beyond burst capacity throttles, refill happens over time at the configured
rate, tokens never exceed the configured burst ceiling even after a long
idle period, `status()` doesn't itself consume a token, different keys have
independent buckets, non-positive capacity always denies, `clear()` resets
state. `RateLimitIntegrationTest` gained 2 tests over the real wire path:
`TOKEN` bursts then throttles, and an unknown algorithm string surfaces as a
Jedis-visible error.

---

## 6. Generalize `CRDT.MERGE` beyond GCounter

**Files:** `CrdtStore.java` (kiradb-crdt), `CrdtHandler.java` (kiradb-server).

### What was missing

`CRDT.MERGE` only ever supported `type=GCOUNTER`. All 5 CRDT types
(`GCounter`, `PNCounter`, `LWWRegister`, `MVRegister`, `ORSet`) had a working
`merge()` method since Phase 6 — the wire command just never grew branches for
the other 4, presumably because the gossip transport that would actually call
`CRDT.MERGE` in production didn't exist yet either (still doesn't — see "skip
entirely" list).

### The fix

`CrdtStore` gained 4 new methods mirroring the existing `mergeGCounter`
pattern exactly — deserialize incoming bytes with the local node id,
`merge()` into the locally-cached instance under its own lock, persist:

```java
public void mergePnCounter(String name, byte[] bytes) { ... }
public void mergeLwwRegister(String name, byte[] bytes) { ... }
public void mergeMvRegister(String name, byte[] bytes) { ... }
public void mergeOrSet(String name, byte[] bytes) { ... }
```

`CrdtHandler.handleMerge` now switches on the (uppercased) type argument and
dispatches to the matching method:

```java
switch (type) {
    case "GCOUNTER" -> crdtStore.mergeGCounter(name, state);
    case "PNCOUNTER" -> crdtStore.mergePnCounter(name, state);
    case "LWWREGISTER" -> crdtStore.mergeLwwRegister(name, state);
    case "MVREGISTER" -> crdtStore.mergeMvRegister(name, state);
    case "ORSET" -> crdtStore.mergeOrSet(name, state);
    default -> { return Resp3Value.error(...); }
}
```

A deserialization failure (e.g. malformed base64-decoded bytes for the given
type, or bytes that don't match the type's format version) now surfaces as a
clean RESP3 error rather than an uncaught exception bubbling up through the
command dispatcher.

### Why this is safe to ship ahead of a real gossip transport

This is explicitly wire-protocol-only work: it doesn't change how any CRDT
merges (that logic is untouched, Phase 6 tested), and it doesn't add a gossip
transport (still deferred — see below). What it does is remove an artificial
limitation on a command that already exists — today it can be exercised
manually (as the tests do: serialize a peer's CRDT state, base64-encode it,
send it over `CRDT.MERGE`) and is ready the moment a real gossip layer wants
to use it for anything beyond counters.

### What a reviewer should scrutinize

- ORSet's `deserialize` is a static factory with no node-id parameter (unlike
  the other 4 types, which take `localNodeId`) — this is consistent with
  `ORSet`'s existing design (it has no concept of node identity, only
  add-tags), not a new inconsistency introduced here.
- `MVRegister` mutations still aren't persisted anywhere in `CrdtStore` except
  via `mergeMvRegister` (which does persist) — `CrdtHandler.handleMvSet`
  mutates the cached in-memory instance directly without a `CrdtStore`
  persist-on-write helper. This is a **pre-existing gap** from Phase 6/7, not
  introduced or worsened here, but worth flagging: an `MVSET` write is lost on
  restart today. Filed as a new gap below rather than silently worked around.

### Test coverage

`CrdtStoreTest` gained 4 unit tests (one per newly-supported type), each
following the existing `mergeGCounterAcceptsRemoteState` pattern: local write,
independently-constructed "peer" CRDT, merge, verify converged value, verify
persistence survives a fresh `CrdtStore` over the same backing storage.
`CrdtIntegrationTest` gained 5 tests over the real Netty wire path — one per
type plus a rejection test for an unsupported type string.

---

## 7. Metric publication (Micrometer)

**Files:** `MemCache.java`, `AccessTracker.java`, `TierManager.java`,
`TieredStorageEngine.java` (kiradb-core); `HttpApiServer.java` (kiradb-server);
`kiradb-core/build.gradle`.

### The two-layer design: plain counters first, Micrometer on top

Rather than sprinkling Micrometer `Counter`/`Gauge` calls directly through
`MemCache`'s and `TierManager`'s hot paths, metrics are tracked as plain
`AtomicLong` fields first (`MemCache.hitCount/missCount/evictionCount`,
`TierManager.cumulativePromoted/cumulativeEvicted/cumulativePurged`), each
exposed via a simple public accessor. `TieredStorageEngine` then registers
Micrometer `Gauge`s that read from those accessors:

```java
Gauge.builder("kiradb.memcache.hits", memCache, MemCache::hitCount)
        .description("Cumulative MemCache hit count")
        .register(registry);
```

This buys two things: `MemCache` and `TierManager` stay usable (and testable)
with zero Micrometer imports — the plain counters work identically whether or
not anyone ever wires a registry — and it means the *only* new dependency
surface is `TieredStorageEngine`'s constructor, not two other classes' entire
public APIs.

### Why gauges, not Micrometer `Counter`s, for monotonic values

All published meters are `Gauge`s, even the ones tracking cumulative
(monotonic) counts like hits and evictions. This looks backwards at first —
Micrometer has a dedicated `Counter` type for exactly this — but the
underlying source of truth already lives as a plain `AtomicLong` outside
Micrometer's control (see above). A Micrometer `Counter` expects to *own* the
increment operations (`counter.increment()`); retrofitting one onto a value
that's incremented elsewhere means either double-bookkeeping (increment both
the `AtomicLong` and the `Counter`) or making `Counter` the sole source of
truth (which would mean pulling Micrometer into `MemCache`, defeating the
whole point of the two-layer design above). A `Gauge` that reads "current
value of this already-existing counter" is the correct Micrometer primitive
for an externally-maintained cumulative count — this is a documented,
intentional choice, called out in `registerMetrics`'s Javadoc.

### Non-breaking constructor wiring

`TieredStorageEngine` gained a 5-argument constructor
(`tier2, maxCacheEntries, orchestrator, tierScanIntervalMs, MeterRegistry`).
The existing 2-arg and 4-arg constructors are preserved unchanged and now
default to a **private** `SimpleMeterRegistry()` instance — in-memory only,
registered nowhere, exported nowhere. This was verified against every
existing call site (`KiraDBServer`, `HttpApiServerTest`,
`TierLatencyBenchmark`, `TieredStorageEngineTest`) before landing — all three
compiled and ran unchanged.

`meterRegistry()` exposes whichever registry is in play (private default or
caller-supplied) so callers — notably `HttpApiServer` — can read published
meters without `TieredStorageEngine` needing to know anything about HTTP or
JSON.

### Meter names

```
kiradb.memcache.size                current MemCache entry count
kiradb.memcache.hits                cumulative MemCache hits
kiradb.memcache.misses              cumulative MemCache misses
kiradb.memcache.evictions           cumulative MemCache evictions (capacity-driven)
kiradb.accesstracker.size           current AccessTracker tracked-key count
kiradb.tiermanager.promotions       cumulative WARM->HOT promotions
kiradb.tiermanager.evictions        cumulative HOT->WARM evictions
kiradb.tiermanager.purges           cumulative AccessTracker purges
```

### `/api/metrics` — JSON, not Prometheus text format

`HttpApiServer` gained a `/api/metrics` endpoint that dumps every meter on
`context.tieredStorage().meterRegistry()` as JSON:
`[{name, tags: [...], measurements: [{statistic, value}]}]`. The task
explicitly allowed skipping a real Prometheus registry ("nice-to-have, don't
block on it"); given that, JSON was chosen over hand-rolling Prometheus's
`# HELP`/`# TYPE`/sample text exposition format for one reason: every other
dashboard endpoint on this server already speaks JSON, and the endpoint's only
current consumer is this project's own React dashboard. Adding
`micrometer-registry-prometheus` as a real dependency, or hand-rolling the
text format, would be solving a problem ("a Prometheus scrape target wants to
poll this server") that doesn't exist yet. If it ever does, the meters
themselves don't need to change — only the registry type passed into
`TieredStorageEngine`'s constructor and the format `HttpApiServer` renders
them in.

### Dependency shape: `api`, not `implementation`

`kiradb-core/build.gradle` declares `micrometer-core` as an `api` dependency
(requiring the `java-library` plugin), not `implementation`. This is
necessary — not stylistic — because `TieredStorageEngine`'s public
constructor accepts a `MeterRegistry` parameter, so any module compiling
against `TieredStorageEngine` (i.e. `kiradb-server`) needs `MeterRegistry` on
its own compile classpath too. `implementation` would have hidden the
dependency from consumers and broken the build the moment `KiraDBServer` or
`HttpApiServer` tried to reference `MeterRegistry` or `Gauge` types directly.

### What a reviewer should scrutinize

- No Prometheus registry, no `/metrics` Prometheus-format endpoint — by
  design, see above. If real external scraping is ever wanted, swapping
  `SimpleMeterRegistry` for `PrometheusMeterRegistry` at the `KiraDBServer`
  wiring layer plus adding `micrometer-registry-prometheus` is the entire
  change; nothing in `MemCache`/`AccessTracker`/`TierManager` would need to
  move.
- `TieredStorageEngine.meterRegistry()` returns whatever registry the
  constructor received (or the private default) with no access control — any
  caller with a reference to the engine can read (but not mutate/remove) the
  registered meters. Consistent with the rest of the dashboard API's
  "read-only ops plane" philosophy (see `HttpApiServer`'s own class Javadoc).

### Test coverage

`MemCacheTest` gained 3 tests for hit/miss/eviction counters.
`TieredStorageEngineTest` gained 3 tests: the default constructor provides a
non-null private registry, an explicitly-supplied registry receives the
MemCache/AccessTracker gauges with sane values after some traffic, and the
default-registry path is reachable through `engine.meterRegistry()`.
`HttpApiServerTest` gained a test asserting all 7 documented meter names
appear in `/api/metrics`'s JSON array after driving some traffic through the
router.

---

## What's still deferred (explicitly out of scope this pass)

Per the task's "skip entirely" list — these need production signals per
CLAUDE.md's own trigger conditions, and were correctly not attempted:

- **ORSet tombstone garbage collection.** Every `add()` still mints a UUID tag
  that lives forever once removed via `remove()`. *Trigger (unchanged from
  CLAUDE.md): Phase 11 benchmarks confirm tombstone growth is real, OR a
  workload with high add/remove churn hits memory limits.* Note: the new
  `flag:_index` ORSet (item #1 above) adds one more ORSet instance to the
  system, but its growth is bounded by "number of distinct flags ever
  created," not general churn — doesn't change the priority of this item.
- **Real cluster gossip wire.** `CRDT.MERGE` (item #6) is now type-complete,
  but still requires a human/test to manually serialize and ship bytes across
  the wire — there's no automatic node-to-node propagation. *Trigger
  (unchanged): implementing real multi-node deployment for any of the
  CRDT-backed services (rate limiter, feature flags).*
- **Dotted version vectors for MVRegister.** Untouched. *Trigger (unchanged):
  a single MVRegister's serialized size crosses ~10 KB OR cluster size
  exceeds ~50 nodes.*
- **Hierarchical config fallback lookup.** Untouched — `ConfigStore` is still
  flat `(scope, key)`. *Trigger (unchanged): first real platform deployment
  where operators want a layered config story.*
- **`ConflictResolver` strategies for Config Store.** Untouched — every
  `CFG.SET`/`CFG.ROLLBACK` is still unconditionally applied. *Trigger
  (unchanged): multi-region deployment AND a customer report of concurrent
  writes stomping each other.*
- **Interned key storage.** Untouched. *Trigger (unchanged): tracked-key
  count exceeds ~10M and per-key memory shows up in heap dumps.*
- **Off-heap value storage for MemCache.** Untouched. *Trigger (unchanged):
  profiling shows GC pause time > 5 ms p99.*

### A new gap discovered during this pass (not previously tracked)

- **`MVRegister` writes are not persisted via `CrdtStore`.** Found while
  implementing item #6 (`CRDT.MERGE` generalization) and writing its tests:
  `CrdtHandler.handleMvSet` (`CRDT.MVSET`) mutates the in-memory `MVRegister`
  instance returned by `crdtStore.mvRegister(name)` directly, but
  `CrdtStore` has no `mvSet`-style method that also calls `storage.put(...)`
  the way `lwwSet`, `orSetAdd`, and `pnCounterAdd` all do. A node restart
  loses every `CRDT.MVSET` write that hasn't separately been reached via
  `mergeMvRegister` (which *does* persist). This was a pre-existing gap from
  Phase 6/7, not introduced by this pass — flagging it here since it surfaced
  directly while testing adjacent code and no other backlog entry currently
  captures it. *Suggested trigger to pull forward: first `CRDT.MVSET` value
  observed missing after a restart, or before any production use of
  `MVRegister` for data that must survive a restart.*

---

## How to make this better

### Which deferred item is now highest-priority, given what shipped in Phases 8–12

Before this pass, the Phase 13 backlog was long and mostly zero-signal (no
production traffic to trigger any of it). Two things changed that ranking:

1. **The newly-discovered MVRegister persistence gap (above) is now the
   single highest-priority item in the whole Phase 13 list** — higher than
   anything that was already on it. Every *other* backlog item requires a
   production signal (a benchmark result, a customer report, an OOM) before
   it's worth the engineering time. This one doesn't: it's a straightforward
   correctness bug (data loss on restart) sitting in code that's already
   shipped and reachable via a public RESP3 command (`CRDT.MVSET`). It should
   be fixed before Phase 14 (AI Rollout Bandit) touches anything that might
   lean on MVRegister for surfacing concurrent bandit decisions, and probably
   before the semantic cache (Phase 8) or dashboard (Phase 9) work builds any
   feature that assumes `CRDT.MVSET` durability.
2. **Real cluster gossip wire** moves up in relative priority now that
   `CRDT.MERGE` (item #6) is type-complete for all 5 CRDTs. Before this pass,
   building a gossip transport would have meant building it for GCounter only
   and then re-touching it four more times as other types needed merging.
   That rework is now avoided — gossip wire work, whenever it's triggered, has
   a complete `CRDT.MERGE` surface to build against on day one.

### New hardening gaps discovered while doing this work

- **MemCache/AccessTracker metric *rates*, not just cumulative counts.** The
  Micrometer gauges published in item #7 expose raw cumulative counters
  (hits, misses, evictions, promotions). A dashboard consumer computing "hit
  rate over the last minute" has to sample this endpoint twice and compute a
  delta client-side — there's no server-side rate/rolling-window computation.
  Micrometer's `FunctionCounter` + a `Timer`-backed rate would be the natural
  next step once the dashboard (Phase 9) actually wants to chart these over
  time, rather than just display point-in-time numbers.
- **No metrics for `RateLimiterStore`, `FlagStore`, or `ConfigStore`.** This
  pass only wired Micrometer through `TieredStorageEngine` (per the task's
  explicit scope). The other Phase 7 services still have zero Micrometer
  coverage — `FlagStats`/`RateLimitDecision` are readable via RESP3 commands
  and the existing `/api/flags`/`/api/ratelimit` JSON endpoints, but none of
  that is exposed as a scrapeable Micrometer meter. Worth doing in the same
  "plain counter + Micrometer Gauge on top" shape once there's a concrete
  dashboard chart or alert that needs it.
- **`TokenBucket` has no eviction/expiry for idle bucket entries.** Every
  distinct `(limiter, key)` pair that ever calls `tryConsume`/`status` gets a
  permanent entry in `TokenBucket`'s internal `ConcurrentHashMap`, with no
  purge mechanism analogous to `AccessTracker`'s `MIN_TRACK_SCORE` cycle. For
  a rate limiter keyed on something high-cardinality and churny (e.g. one
  bucket per API key across millions of ephemeral trial accounts), this is an
  unbounded-growth shape similar to the AccessTracker problem this very phase
  just fixed (item #4) — worth flagging before `TOKEN` sees production use
  with a high-cardinality key space. A time-based sweep (drop buckets whose
  `lastRefillMillis` is older than, say, `10 * periodSeconds`) would be a
  reasonable first cut, following the same "hard cap + backstop" playbook as
  item #4.
- **`Locale.ROOT` needed adding in two places (`CrdtHandler`,
  `RateLimitHandler`) for `String.toUpperCase()`** to satisfy checkstyle/i18n
  best practice — a small thing, but worth normalizing: a quick codebase grep
  for other bare `.toUpperCase()`/`.toLowerCase()` calls (there's at least one
  pre-existing one in `CrdtHandler` that this pass touched anyway) would catch
  any other latent locale-dependent-casing bugs (e.g. the notorious Turkish
  "I" problem) before they surface as a hard-to-reproduce bug report from a
  user running a non-English system locale.
