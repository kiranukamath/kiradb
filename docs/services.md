# Built-in Services (v0.6.0)

Three services ship as native KiraDB primitives, all reachable from any Redis client via the standard `sendCommand` escape hatch:

1. **Feature Flags** — sticky percentage rollout, instant kill switch, per-cohort metrics
2. **Distributed Rate Limiter** — sliding-window counter enforced across all nodes
3. **Config Store** — append-only history, live server-push notifications

This guide is a hands-on tutorial. For full command syntax, see [`commands/reference.md`](commands/reference.md).
For the CRDT primitives that underpin all three, see [`internals/crdts.md`](internals/crdts.md).

---

## 1. Feature Flags

### Hello-world

Start KiraDB, then:

```
> FLAG.SET dark-mode 1 0.10
OK
> FLAG.GET dark-mode alice
(integer) 0
> FLAG.GET dark-mode bob
(integer) 1
> FLAG.GET dark-mode alice
(integer) 0    # sticky — alice always gets the same answer
```

`FLAG.SET name value [percent]` creates or replaces a flag. `value` is a boolean (`0|1|true|false|on|off`); `percent` is the rollout fraction in `[0.0, 1.0]`. Without an explicit percent, value=on means 100% rollout, value=off means 0%.

### How "sticky" works

The decision for each `(flag, userId)` is computed by hashing `flagName + ":" + userId` with SHA-256, taking the first 4 bytes as a non-negative int, and checking whether `bucket < percent * 10000`.

- The same `(flag, userId)` always produces the same bucket — UI doesn't flicker as users navigate between pages.
- The flag name is folded into the hash so `alice` doesn't get the same rollout slot for every flag.
- SHA-256 is uniform: at 10% rollout over 50,000 users, observed enabled count is reliably between 8% and 12%.

### Kill switch

```
> FLAG.SET checkout-v2 1 1.0
OK
> FLAG.GET checkout-v2 alice
(integer) 1

# Hot incident — disable the broken flag instantly
> FLAG.KILL checkout-v2
(integer) 1
> FLAG.GET checkout-v2 alice
(integer) 0

# Restore the previous rollout
> FLAG.UNKILL checkout-v2
(integer) 1
> FLAG.GET checkout-v2 alice
(integer) 1
```

`KILL` preserves the rollout percentage for restore; only the `killed` bit is flipped.

### Metrics

Every `FLAG.GET` records an impression for the user's cohort. Every `FLAG.CONVERT` records a conversion. Four `GCounter`s are maintained per flag:

```
flag:<name>:impressions:enabled
flag:<name>:impressions:disabled
flag:<name>:conversions:enabled
flag:<name>:conversions:disabled
```

`FLAG.STATS` returns them as a RESP3 map, plus per-cohort conversion rates:

```
> FLAG.STATS dark-mode
1# "enabled_impressions"     => (integer) 412
2# "disabled_impressions"    => (integer) 3712
3# "enabled_conversions"     => (integer) 17
4# "disabled_conversions"    => (integer) 89
5# "enabled_conversion_rate" => "0.0413"
6# "disabled_conversion_rate"=> "0.0240"
```

Why per-cohort, not just totals? Because the *future bandit* (Phase 14) needs to compare the enabled and disabled cohorts to decide whether the new variant is winning. Total stats wouldn't let it.

### From any language

```python
# Python — redis-py
import redis
r = redis.Redis(port=6379, decode_responses=True)
r.execute_command("FLAG.SET", "dark-mode", "1", "0.10")
enabled = r.execute_command("FLAG.GET", "dark-mode", "alice")
```

```javascript
// Node.js — node-redis 4.x
const client = createClient({ url: 'redis://localhost:6379' });
await client.connect();
await client.sendCommand(['FLAG.SET', 'dark-mode', '1', '0.10']);
const enabled = await client.sendCommand(['FLAG.GET', 'dark-mode', 'alice']);
```

```java
// Java — Jedis
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;

ProtocolCommand FLAG_GET = () -> "FLAG.GET".getBytes();

try (Jedis jedis = new Jedis("localhost", 6379)) {
    jedis.sendCommand(() -> "FLAG.SET".getBytes(), "dark-mode", "1", "0.10");
    Object enabled = jedis.sendCommand(FLAG_GET, "dark-mode", "alice");
}
```

This is the same `sendCommand` pattern every Redis SDK exposes — RedisJSON, RedisBloom, and RediSearch are all consumed this way.

### What's deferred (see Phase 13 / Phase 14)

- **Multi-armed bandit** that consumes per-cohort metrics and auto-adjusts rollout — Phase 14, trigger-driven.
- **Persistent flag-name index** so `FLAG.LIST` is reliably complete after a cold restart — Phase 13.

---

## 2. Distributed Rate Limiter

### The simple call

```
> RL.ALLOW payments-api user:123 100 60
(integer) 1
```

`RL.ALLOW limiter key limit periodSeconds` returns 1 (allowed) or 0 (denied). The first three args identify the limiter; the last two define the budget — *up to `limit` requests per `periodSeconds` window*.

### What "sliding-window counter" means

Picture time chopped into fixed buckets of `periodSeconds`. We always look at **two**: the current bucket and the previous one. Estimated usage is a weighted blend:

```
elapsedInCurrent = how far into the current window we are (ms)
weightOfPrevious = (periodMs - elapsedInCurrent) / periodMs
estimatedUsage = previousBucket * weightOfPrevious + currentBucket
```

The previous bucket fades linearly as we move through the current window. This solves the boundary problem of fixed-window limiters (where a user could do `limit` requests at the end of one window and `limit` at the start of the next, getting `2*limit` in seconds). The same algorithm is used by Cloudflare, Stripe, and RedisCell.

### Inspect without consuming

```
> RL.STATUS payments-api user:123 100 60
1# "allowed"          => (true)
2# "used"             => (integer) 47
3# "limit"            => (integer) 100
4# "remaining"        => (integer) 53
5# "reset_at_millis"  => (integer) 1745773200000
```

`RL.STATUS` reads the same buckets `RL.ALLOW` reads, but does not increment. Useful for showing users their remaining quota.

### Distribution: GCounter does the heavy lifting

Each `(limiter, key, time-bucket)` tuple is stored as one `GCounter`. Every node bumps its own slot in that GCounter when it serves a request. Sum across all node slots = global count for that bucket. Gossip merges periodically.

The flagship test (`RateLimiterStoreTest.distributedEnforcementAcrossThreeNodes`) proves this:

1. Three independent nodes each call `allow(...)` 40 times — all 120 calls return `allowed=true` because each node only sees its own slot.
2. Manually merge node1's and node2's bucket states into node3 (simulating gossip).
3. Now node3's GCounter has `{node-1: 40, node-2: 40, node-3: 40}` = total 120.
4. Request 121 to node3 returns `allowed=false`. ✓

### The trade-off you should know about

We **increment first, decide after**. A denied request still consumes a slot in the GCounter. This is the same as `INCR-then-check` in Redis. The alternative ("check, then increment if allowed") has races even on a single node, much worse distributed.

During gossip lag, two nodes can each independently see "below limit" and both allow a request — brief over-allowance proportional to gossip cadence and node count. For a "100 per minute" limit on a 3-node cluster, typical over-allowance is ~1%. For very small limits this becomes proportionally larger. **Pick larger limits or accept the trade.** Strict mode (route every check through a Raft leader) is a future option.

### `RL.RESET` (currently unsupported)

Returns an explicit error. `GCounter` is grow-only — proper reset requires gossip-coordinated tombstones. Wait for natural window roll-over, or use a smaller window. Filed in Phase 13.

---

## 3. Config Store

### Versioned writes

```
> CFG.SET payment-service timeout 3000
(integer) 1     # version number
> CFG.SET payment-service timeout 5000
(integer) 2
> CFG.GET payment-service timeout
"5000"          # latest value
```

Every `CFG.SET` appends a new version. `CFG.GET` returns the latest. Earlier versions are never deleted.

### Audit history

```
> CFG.HIST payment-service timeout
1) 1# "version"   => (integer) 1
   2# "timestamp" => (integer) 1745772900000
   3# "value"     => "3000"
2) 1# "version"   => (integer) 2
   2# "timestamp" => (integer) 1745772930000
   3# "value"     => "5000"
```

Each entry is a RESP3 map with `{version, timestamp, value}`. Useful for "when did we change this and to what?" forensics.

### The interesting part: live server-push via `CFG.WATCH`

This is the one place KiraDB breaks the standard request/response shape — the server pushes notifications on a long-lived connection.

```
# Connection A — subscriber
> CFG.WATCH payment-service
OK

# Connection B — admin pushes a change
> CFG.SET payment-service timeout 8000
(integer) 3

# Connection A receives a push frame *without* having sent another command:
*6
$10
CFG.NOTIFY
$15
payment-service
$7
timeout
$4
8000
:3
:1745773100000
```

The push frame is a standard RESP3 array starting with the literal `"CFG.NOTIFY"` — clients dispatch on that leading element the same way they handle Redis pub/sub messages. SDKs that already support pub/sub will be straightforward to wire to `CFG.WATCH`.

### Auto-cleanup on disconnect

`ConfigSubscriptionRegistry` (in `kiradb-server`) attaches a Netty `closeFuture` listener to each subscribing channel. When the client disconnects — clean shutdown, kill -9, network drop — the channel is removed from every scope it was subscribed to. No leaked entries.

The integration test (`ConfigIntegrationTest.watchReceivesPushNotificationOnChange`) verifies the full cycle: subscribe, write, push received, disconnect, count drops to 0.

### What's deferred (Phase 13)

- **Hierarchical fallback lookup** (`global → environment → service → instance`) — flat namespaces for v0.6.0.
- **`CFG.ROLLBACK scope key versionsBack`** — easy to add (read older version, append it as new latest).
- **`ConflictResolver` strategies** (`LAST_WRITE_WINS`, `MANUAL_REVIEW`) — meaningful only when multi-region replication ships.

---

## When to use which service

| Need | Reach for | Why |
|---|---|---|
| Toggle a feature on/off without redeploying | `FLAG.SET / GET / KILL` | Sticky per user, instant flip |
| Run an A/B test with conversion measurement | `FLAG.GET` + `FLAG.CONVERT` + `FLAG.STATS` | Per-cohort metrics, bandit-ready |
| "Don't let any one user hit the API more than X/min" | `RL.ALLOW` | No coordination on the hot path |
| "When my admin changes a config, all my services react in seconds" | `CFG.SET` + `CFG.WATCH` | Server-push, no polling |
| Audit "when and to what was this config changed?" | `CFG.HIST` | Append-only history |
| "How close am I to the rate limit right now?" | `RL.STATUS` | Read without consuming |

---

## What about exact-Redis-semantics for these commands?

KiraDB's `FLAG.*`, `RL.*`, `CFG.*`, and `CRDT.*` are KiraDB-native. They don't exist in Redis or Valkey, and that's by design — these are commands the database needs because the *database* is providing the service, not a separate sidecar. The Redis-compat surface is the wire protocol (RESP3), and that's enough to make every Redis SDK work via `sendCommand`.

The standard Redis commands (GET, SET, DEL, EXISTS, EXPIRE, TTL, etc.) work directly through the SDK's typed methods — `jedis.set("k","v")` does what you expect.
