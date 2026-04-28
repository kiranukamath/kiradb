# Getting Started with KiraDB

This guide gets KiraDB running locally and shows you how to connect with `redis-cli`
or any Redis client. Total time: about 5 minutes.

---

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java | 25 | `java --version` |
| Gradle | 9+ (or use the wrapper) | `./gradlew --version` |
| redis-cli | any | `redis-cli --version` (optional) |

> You do not need Redis installed. `redis-cli` is just the client tool — it talks
> to KiraDB over the same RESP3 protocol.

---

## 1. Clone and Build

```bash
git clone https://github.com/kirankamath/kiradb.git
cd kiradb
./gradlew build
```

Expected output:

```
BUILD SUCCESSFUL in 4s
```

If you see `BUILD FAILED`, check that `java --version` reports Java 25.

---

## 2. Run the Server

```bash
./gradlew :kiradb-server:run
```

Or build a JAR and run it directly:

```bash
./gradlew :kiradb-server:jar
java --enable-preview -jar kiradb-server/build/libs/kiradb-server.jar
```

You should see:

```
INFO  KiraDB starting...
INFO  Listening on port 6379 (RESP3)
INFO  Connect with: redis-cli -p 6379
```

KiraDB is now listening on port **6379** — the same port Redis uses.

---

## 3. Connect with redis-cli

In a new terminal:

```bash
redis-cli -p 6379
```

---

## 4. Try the Commands

### PING — health check
```
127.0.0.1:6379> PING
PONG

127.0.0.1:6379> PING "hello"
"hello"
```

### SET and GET — basic key-value
```
127.0.0.1:6379> SET hello world
OK

127.0.0.1:6379> GET hello
"world"

127.0.0.1:6379> GET missing-key
(nil)
```

### SET with expiry
```
127.0.0.1:6379> SET session abc123 EX 60
OK

127.0.0.1:6379> TTL session
(integer) 59

127.0.0.1:6379> PTTL session
(integer) 58742
```

### DEL — delete a key
```
127.0.0.1:6379> DEL hello
(integer) 1

127.0.0.1:6379> GET hello
(nil)

127.0.0.1:6379> DEL missing-key
(integer) 0
```

### EXISTS — check if a key exists
```
127.0.0.1:6379> SET foo bar
OK

127.0.0.1:6379> EXISTS foo
(integer) 1

127.0.0.1:6379> EXISTS missing
(integer) 0
```

### EXPIRE — add a TTL to an existing key
```
127.0.0.1:6379> SET counter 0
OK

127.0.0.1:6379> EXPIRE counter 30
(integer) 1

127.0.0.1:6379> TTL counter
(integer) 29
```

---

## 5. Connect with a Java Client (Jedis)

KiraDB is wire-compatible with Redis. Any Redis client works:

```xml
<!-- Maven -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.2.0</version>
</dependency>
```

```java
import redis.clients.jedis.Jedis;

try (Jedis db = new Jedis("localhost", 6379)) {
    db.set("hello", "world");
    String value = db.get("hello");
    System.out.println(value); // world
}
```

---

## 6. Run the Tests

```bash
./gradlew test
```

The integration tests start a real KiraDB server on port 16379, connect with Jedis,
and exercise all commands. You should see:

```
KiraDBServerTest > ping() PASSED
KiraDBServerTest > setAndGet() PASSED
KiraDBServerTest > del() PASSED
KiraDBServerTest > exists() PASSED
...

11 tests completed, 0 failed
BUILD SUCCESSFUL
```

---

## Current Limitations (as of v0.6.0)

| Limitation | Resolved in |
|---|---|
| No authentication | Phase 13 hardening backlog |
| No real cluster gossip wire for CRDTs (single-node persistence works; cross-node merge proven via in-process tests) | Phase 13 hardening backlog |
| No semantic cache | v0.7.0 (Phase 8 — in progress) |
| No Java SDK; non-RESP3 commands need `sendCommand` | v0.8.0 (Phase 10) |
| No production benchmarks | v1.0.0 (Phase 11) |

> Earlier limitations are resolved: data persists via the LSM tree (v0.2.0),
> a 3-node Raft cluster works (v0.3.0), tiered storage with adaptive promotion
> ships in v0.4.0, CRDTs in v0.5.0, and feature flags / rate limiter / config
> store in v0.6.0.

---

## Try the v0.5.0+ commands

Once the server is running, beyond the basic key-value commands you can also:

```
# CRDT counters (v0.5.0)
127.0.0.1:6379> CRDT.INCR votes
(integer) 1
127.0.0.1:6379> CRDT.INCR votes 10
(integer) 11

# Feature flags with sticky percentage rollout (v0.6.0)
127.0.0.1:6379> FLAG.SET dark-mode 1 0.10
OK
127.0.0.1:6379> FLAG.GET dark-mode alice
(integer) 0
127.0.0.1:6379> FLAG.GET dark-mode bob
(integer) 1

# Distributed rate limiter (v0.6.0)
127.0.0.1:6379> RL.ALLOW api user:1 5 60
(integer) 1
127.0.0.1:6379> RL.ALLOW api user:1 5 60
(integer) 1
# ...after 5 calls, the 6th returns 0 (denied)

# Config store with version history (v0.6.0)
127.0.0.1:6379> CFG.SET payment-service timeout 3000
(integer) 1
127.0.0.1:6379> CFG.SET payment-service timeout 5000
(integer) 2
127.0.0.1:6379> CFG.GET payment-service timeout
"5000"
```

See [Built-in Services Guide](services.md) for the full guide including
`CFG.WATCH` server-push notifications.

---

## What's Next

- [Built-in Services Guide](services.md) — practical tutorial for FLAG.*, RL.*, CFG.*
- [Command Reference](commands/reference.md) — full list of all supported commands
- [CRDTs Deep Dive](internals/crdts.md) — how the conflict-free counters and sets work
- [Netty Deep Dive](internals/netty.md) — how the networking layer works
