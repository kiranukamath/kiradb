# KiraDB Command Reference

All commands use the RESP3 protocol. Any Redis client works.

---

## Connection

### PING
```
PING [message]
```
Returns `PONG`, or echoes the message if provided.

```
> PING
PONG

> PING "hello"
"hello"
```

---

## Key-Value

### SET
```
SET key value [EX seconds] [PX milliseconds]
```
Store a value. Returns `OK`.

```
> SET hello world
OK

> SET session abc123 EX 60
OK

> SET counter 0 PX 5000
OK
```

### GET
```
GET key
```
Retrieve a value. Returns the value or `nil` if the key does not exist or has expired.

```
> GET hello
"world"

> GET missing
(nil)
```

### DEL
```
DEL key [key ...]
```
Delete one or more keys. Returns the number of keys actually deleted.

```
> DEL hello
(integer) 1

> DEL k1 k2 k3
(integer) 3

> DEL missing
(integer) 0
```

### EXISTS
```
EXISTS key [key ...]
```
Count how many of the given keys exist. A key listed multiple times is counted multiple times.

```
> SET foo bar
OK

> EXISTS foo
(integer) 1

> EXISTS missing
(integer) 0

> EXISTS foo foo
(integer) 2
```

---

## Expiry

### SETEX
```
SETEX key seconds value
```
Store a value with a seconds-based expiry. Returns `OK`.

```
> SETEX session 3600 user123
OK
```

### PSETEX
```
PSETEX key milliseconds value
```
Store a value with a milliseconds-based expiry. Returns `OK`.

```
> PSETEX token 30000 abc123
OK
```

### EXPIRE
```
EXPIRE key seconds
```
Set a timeout on an existing key in seconds. Returns `1` if set, `0` if the key does not exist.

```
> SET counter 0
OK

> EXPIRE counter 60
(integer) 1
```

### PEXPIRE
```
PEXPIRE key milliseconds
```
Set a timeout on an existing key in milliseconds.

```
> PEXPIRE counter 60000
(integer) 1
```

### TTL
```
TTL key
```
Return remaining time-to-live in seconds.

| Return value | Meaning |
|---|---|
| `>= 0` | Remaining seconds |
| `-1` | Key exists, no expiry |
| `-2` | Key does not exist |

```
> SET foo bar
OK

> TTL foo
(integer) -1

> SET expiring value EX 30
OK

> TTL expiring
(integer) 29
```

### PTTL
```
PTTL key
```
Same as `TTL` but returns milliseconds.

```
> PTTL expiring
(integer) 28742
```

---

## CRDT (v0.5.0)

Conflict-free replicated data types. Each CRDT is **named** (string identifier) and persists across restarts.
See [CRDTs Deep Dive](../internals/crdts.md) for the math behind these.

### CRDT.INCR
```
CRDT.INCR name [delta]
```
Increment a `GCounter` (grow-only). `delta` defaults to 1, must be positive. Returns the new value.

```
> CRDT.INCR likes
(integer) 1

> CRDT.INCR likes 10
(integer) 11
```

### CRDT.GET
```
CRDT.GET name
```
Read a `GCounter` value. Returns 0 if the counter doesn't exist yet.

```
> CRDT.GET likes
(integer) 11
```

### CRDT.PNADD
```
CRDT.PNADD name delta
```
Apply a signed delta to a `PNCounter` (positive/negative). Returns the new value.

```
> CRDT.PNADD balance 100
(integer) 100

> CRDT.PNADD balance -30
(integer) 70
```

### CRDT.PNGET
```
CRDT.PNGET name
```
Read a `PNCounter` value (signed).

### CRDT.LWWSET / CRDT.LWWGET
```
CRDT.LWWSET name value
CRDT.LWWGET name
```
Set / read an `LWWRegister` (Last-Write-Wins). On merge, higher timestamp wins; ties broken by node id.

```
> CRDT.LWWSET feature.color blue
OK
> CRDT.LWWGET feature.color
"blue"
```

### CRDT.MVSET / CRDT.MVGET
```
CRDT.MVSET name value
CRDT.MVGET name
```
Set / read an `MVRegister` (Multi-Value). Concurrent writes from different nodes survive — `MVGET` returns an array of all currently-concurrent values. App resolves.

### CRDT.SADD / CRDT.SREM / CRDT.SMEMBERS
```
CRDT.SADD name member [member ...]
CRDT.SREM name member [member ...]
CRDT.SMEMBERS name
```
Operations on an `ORSet` (Observed-Remove Set). Concurrent add+remove on different nodes preserves intent — a concurrent re-add survives a remove.

```
> CRDT.SADD online-users alice bob
(integer) 2

> CRDT.SREM online-users alice
(integer) 1

> CRDT.SMEMBERS online-users
1) "bob"
```

### CRDT.MERGE
```
CRDT.MERGE TYPE name base64-state
```
Apply remote CRDT state to a local instance (gossip operation; intended for cluster sync).
Currently supports `TYPE=GCOUNTER`. Other types are tracked in the Phase 13 hardening backlog.

---

## Feature Flags (v0.6.0)

State backed by `LWWRegister`; per-cohort impressions/conversions tracked as `GCounter`s for the future bandit (Phase 14).
See [Built-in Services Guide](../services.md) for the full picture.

### FLAG.SET
```
FLAG.SET name value [percent]
```
Create or replace a flag. `value` is `0|1|true|false|on|off`; `percent` is a rollout in `[0.0, 1.0]` (defaults to 1.0 if value=on, 0.0 if value=off).

```
> FLAG.SET dark-mode 1 0.10
OK
```

### FLAG.GET
```
FLAG.GET name userId
```
Evaluate the flag for a user. Returns 1 (enabled) or 0 (disabled). The decision is **sticky** — same user always gets the same answer for the same `(flag, percent)`.

Records an impression for the user's cohort.

```
> FLAG.GET dark-mode alice
(integer) 0

> FLAG.GET dark-mode bob
(integer) 1
```

### FLAG.LIST
```
FLAG.LIST
```
Return all flag names known to this node, sorted.

### FLAG.KILL / FLAG.UNKILL
```
FLAG.KILL name
FLAG.UNKILL name
```
`KILL` forces the flag OFF for everyone, regardless of rollout. `UNKILL` restores the previous rollout. Returns 1 on success, 0 if the flag doesn't exist.

```
# Hot incident — disable the broken feature instantly
> FLAG.KILL checkout-v2
(integer) 1
```

### FLAG.CONVERT
```
FLAG.CONVERT name userId
```
Record a conversion for the user's cohort. Bandits (Phase 14) will consume these.

### FLAG.STATS
```
FLAG.STATS name
```
Return per-cohort impressions, conversions, and conversion rates as a RESP3 map.

```
> FLAG.STATS dark-mode
1# "enabled_impressions"     => (integer) 412
2# "disabled_impressions"    => (integer) 3712
3# "enabled_conversions"     => (integer) 17
4# "disabled_conversions"    => (integer) 89
5# "enabled_conversion_rate" => "0.0413"
6# "disabled_conversion_rate"=> "0.0240"
```

---

## Rate Limiter (v0.6.0)

Sliding-window counter algorithm over `GCounter`. Each node bumps its own slot on every `RL.ALLOW`; usage estimate combines current bucket + a fading prior bucket. Distributed by GCounter merge.

### RL.ALLOW
```
RL.ALLOW limiter key limit periodSeconds
```
Increment the counter and return whether the request is allowed. Returns 1 (allowed) or 0 (denied).

`limiter` is a namespace (e.g. `payments-api`); `key` is the keyed subject (e.g. `user:123`).

> Note: denied requests still consume a slot in the counter — same behaviour as `INCR-then-check`. Documented; doesn't compound.

```
# 100 requests per 60 seconds per user
> RL.ALLOW payments-api user:123 100 60
(integer) 1
```

### RL.STATUS
```
RL.STATUS limiter key limit periodSeconds
```
Inspect current usage without consuming a slot. Returns a RESP3 map.

```
> RL.STATUS payments-api user:123 100 60
1# "allowed"          => (true)
2# "used"             => (integer) 47
3# "limit"            => (integer) 100
4# "remaining"        => (integer) 53
5# "reset_at_millis"  => (integer) 1745773200000
```

### RL.RESET
```
RL.RESET limiter key
```
Currently returns an error — `GCounter` is grow-only, so reset requires gossip-coordinated tombstones (Phase 13 backlog). Wait for the natural window roll-over in the meantime.

---

## Config Store (v0.6.0)

Append-only history of versioned values. Live change notifications via Netty server-push.

### CFG.SET
```
CFG.SET scope key value
```
Append a new version. Returns the new version number (`1` for the first write).

```
> CFG.SET payment-service timeout 3000
(integer) 1

> CFG.SET payment-service timeout 5000
(integer) 2
```

### CFG.GET
```
CFG.GET scope key
```
Return the latest value, or `nil` if absent.

```
> CFG.GET payment-service timeout
"5000"
```

### CFG.HIST
```
CFG.HIST scope key
```
Return all historical versions in order (oldest first) as an array of RESP3 maps.

```
> CFG.HIST payment-service timeout
1) 1# "version"   => (integer) 1
   2# "timestamp" => (integer) 1745772900000
   3# "value"     => "3000"
2) 1# "version"   => (integer) 2
   2# "timestamp" => (integer) 1745772930000
   3# "value"     => "5000"
```

### CFG.WATCH / CFG.UNWATCH
```
CFG.WATCH   scope
CFG.UNWATCH scope
```
Subscribe/unsubscribe the current connection to changes in a scope. After `CFG.WATCH`, the server pushes a notification frame on the same connection whenever any key under that scope changes:

```
*6
$10
CFG.NOTIFY
$<scope>
$<key>
$<new value>
:<version number>
:<timestamp millis>
```

`CFG.UNWATCH` returns 1 if the connection was previously subscribed, 0 otherwise.
Subscriptions are auto-cleaned when the connection closes.
