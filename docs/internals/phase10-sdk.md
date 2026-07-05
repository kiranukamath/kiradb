# Phase 10 — Java SDK: Design Notes & Deep Dive

> Written during the autonomous build. Usage guide: [sdk.md](../sdk.md).

---

## What was built

| Piece | Location | Role |
|---|---|---|
| `protocol/RespValue`, `RespReader`, `RespWriter` | `kiradb-client/.../protocol/` | Hand-rolled RESP3 wire client — zero dependencies |
| `protocol/RespConnection` | same package | One blocking socket; `command()` = write + read one round trip |
| `pool/ConnectionPool` | `.../pool/` | Fixed-size borrow/return pool, create-on-demand, validate-on-borrow |
| `KiraDB` | `kiradb-client/.../KiraDB.java` | Builder + core KV API + facade factories + shared internals |
| `FlagsClient`, `RateLimiterClient`, `ConfigClient`, `SemanticCacheClient` | same package | Fluent per-subsystem facades over `KiraDB.call(...)` |
| `WatchDispatcher` | same package | Dedicated subscriber connection + reader thread for `CFG.WATCH` push frames |
| `Replies` | same package | Shape-asserting reply decoders shared by every facade |

---

## The design decisions, and why

### 1. Hand-rolled RESP3 client, not a Jedis wrapper

| Option | Learning value | Effort | Feature completeness |
|---|---|---|---|
| Wrap Jedis, add fluent facades on top | low — Jedis already solved it | small | inherits Jedis's connection handling, pub/sub, pooling |
| Write RESP3 from scratch | **high** — exactly how wire-protocol clients are built | larger | scoped to what KiraDB needs |

This is a from-scratch-in-Java learning project (see `CLAUDE.md`). A wrapped
Jedis client would ship faster but teach nothing about the protocol the
server itself implements — and Kiran already built the *server* side of
RESP3 in Phase 2. Writing the client side closes the loop: same wire format,
both directions, in one head.

The zero-dependency constraint (`kiradb-client/build.gradle` has no main
dependencies) is deliberate too: a database client that drags in a specific
HTTP/pool/JSON stack is a worse citizen in someone else's dependency tree
than one built on nothing but the JDK.

### 2. Blocking I/O + virtual threads, not async NIO

`RespConnection` is one `Socket`, one blocking read/write pair, one
in-flight command at a time. The alternative — async NIO with callbacks or a
`CompletableFuture` API — is objectively more scalable per OS thread, but:

- **Correctness is free with blocking I/O.** There's no partial-frame state
  machine to get wrong (contrast with the server's Netty decoder, which
  *must* handle partial buffers because Netty owns the event loop). `RespReader`
  is a straightforward recursive descent parser that blocks until each field
  is available.
- **Virtual threads erase the traditional cost of blocking.** A pool of 100
  blocked connections costs ~100 small stack allocations, not 100 OS threads.
  The `connectionPoolHandlesConcurrentLoad` test spins 20 virtual threads ×
  50 ops each through a 10-connection pool with zero contention drama.
- Concurrency comes from the **pool**, not from multiplexing one socket. This
  mirrors how most real-world Redis clients are actually built (Jedis is
  blocking-per-connection too; Lettuce is the async outlier, and it pays for
  it with materially more internal complexity).

### 3. Connection pool: borrow/validate/return, PING-on-borrow by default

Design lifted directly from mature pool implementations (Apache Commons
Pool, HikariCP): idle queue for the fast path, create-on-demand up to a cap,
bounded wait on exhaustion (fail fast — a hung caller waiting forever on a
pool is worse than a clear `KiraDBException` telling you to size up).

**Why validate on borrow by default:** a pooled socket can silently die
between uses (server restart, idle timeout, NAT drop) and the *first* symptom
is usually the caller's real command failing with a confusing I/O error. A
PING costs microseconds on localhost and turns "your write mysteriously
failed" into "a stale connection was quietly replaced." The knob exists
(`validateOnBorrow(false)`) for latency-sensitive callers willing to handle
retries themselves.

### 4. Dedicated subscriber connection for `CFG.WATCH` (the pub/sub problem)

This is the one place a pooled request/response connection genuinely cannot
work: once a connection sends `CFG.WATCH`, the server may push a `CFG.NOTIFY`
frame at *any* moment, including in the gap between another command and its
reply. A pooled connection handed to a different borrower mid-subscription
would silently lose that subscription, or worse, misinterpret a push frame as
someone else's reply.

Real Redis clients hit this exact problem with pub/sub and solve it the same
way: **subscriptions get their own connection**, permanently owned by one
reader. `WatchDispatcher` does exactly that — one virtual thread reads
everything off the socket forever; frames starting with `"CFG.NOTIFY"` go to
listeners, everything else (replies to `CFG.WATCH`/`CFG.UNWATCH` the
dispatcher itself sent) goes to a queue the calling thread blocks on. One
dispatcher, lazily created, shared by every `watch()` call on a `KiraDB`
instance — not one connection per watched scope.

The regular pooled path also defends against push frames arriving on an
ordinary connection (`RespConnection.command()` skips any `Push` frames
before returning) even though in practice only the dedicated subscriber
connection subscribes at all. Defense in depth for a protocol detail that's
easy to get subtly wrong.

### 5. `SemanticCacheClient.threshold()` returns a new instance, not a mutation

`db.semanticCache()` and its `.threshold(double)` derivation are immutable —
calling `.threshold()` never changes the receiver. This lets a shared base
client be specialized per call site without accidentally corrupting a
threshold another part of the codebase depends on:

```java
SemanticCacheClient base = db.semanticCache();          // server default threshold
SemanticCacheClient strict = base.threshold(0.95);       // independent
```

The sentinel `SERVER_DEFAULT_THRESHOLD = -1.0` (an otherwise-invalid
similarity score) lets `get()` omit the `THRESHOLD` argument entirely when no
override was requested, so the server's own configured default applies.

### 6. Error mapping: everything becomes `KiraDBException`

RESP `-ERR` replies, I/O failures, and timeouts all surface as the single
unchecked `KiraDBException`. No checked-exception ceremony for callers, and
no attempt to build a taxonomy of server error codes yet — the message string
carries the detail. A typed hierarchy (e.g. `RateLimitExceededException`,
`NotLeaderException` once Raft is wired into the client path) is a reasonable
future addition once error codes are stable enough to build on.

---

## What a reviewer should scrutinize

1. **Pool exhaustion under sustained overload.** `borrow()` fails fast after
   `connectTimeout` once the pool is saturated — correct behavior, but a
   caller retry-looping on that exception without backoff could hammer the
   server. No retry policy exists in the SDK today (see below).
2. **`RespReader` has no length limits.** A malicious or buggy server could
   send an enormous `$` length prefix and the client will allocate that much.
   Fine for a client talking to a trusted server; a hardening item if KiraDB
   is ever exposed to untrusted peers via the client role.
3. **`WatchDispatcher` listener exceptions are swallowed** (caught and
   dropped) so one bad listener can't kill the reader thread or block other
   listeners — correct for isolation, but silent. No logging framework is
   pulled into the zero-dependency main sources to fix this cheaply; worth
   reconsidering if watch usage grows.
4. **`RateLimiterClient.allow()` costs two round trips** (`RL.ALLOW` then
   `RL.STATUS`) because `RL.ALLOW` only returns the decision bit on the wire.
   Correct, but doubles latency on the hot rate-limit path. A server-side
   `RL.ALLOW` that returns the full accounting map would remove this — noted
   below.

## How to make this better

1. **Multi-node routing + failover.** `nodes(...)` today picks the first
   healthy node at build time and never re-evaluates. Real usage needs
   request retry against the next node on connection failure, and eventually
   Raft-aware routing (route writes to the leader, reads anywhere) once the
   server exposes cluster topology to clients.
2. **`RL.ALLOW` returning full accounting** — server-side change so the SDK's
   `allow()` becomes one round trip instead of two.
3. **Async/reactive API variant** — a `CompletableFuture`-based facade over
   the same connections, for callers already in a non-blocking pipeline
   (e.g. inside a Netty handler) who can't afford to block a platform thread.
4. **Command pipelining** — batch several commands onto one connection
   without waiting for each reply before sending the next; meaningful
   throughput win for bulk loads (e.g. warming the semantic cache).
5. **RESP3 `HELLO` negotiation** — currently the client assumes RESP3 from
   the first byte; a real handshake would let the SDK degrade gracefully
   against a hypothetical RESP2-only server or negotiate auth.
6. **Retry policy with backoff** for transient I/O failures and pool
   exhaustion, configurable per builder.
7. **TLS support** for connections crossing untrusted networks — currently
   plaintext only, fine for same-host/VPC deployments, not for anything else.
8. **Typed exception hierarchy** once server error codes stabilize (rate
   limit exceeded, not-leader, wrong-type) instead of one flat
   `KiraDBException`.
9. **Publish to GitHub Packages** — the `maven-publish` config is ready in
   [sdk.md](../sdk.md) but needs Kiran's GitHub token; a go-live action item,
   not an engineering one.
