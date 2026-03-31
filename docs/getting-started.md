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

## Current Limitations (v0.1.0)

| Limitation | Resolved in |
|---|---|
| Data does not persist across restarts | v0.2.0 (WAL + LSM Tree) |
| Single node only | v0.3.0 (Raft cluster) |
| No authentication | v0.5.0 |
| No cluster replication | v0.3.0 |

---

## What's Next

- [Architecture](architecture.md) — understand how all the pieces fit together
- [Netty Deep Dive](internals/netty.md) — how the networking layer works
- [Command Reference](commands/reference.md) — full list of supported commands
- [Contributing](contributing.md) — how to add a new command or feature
