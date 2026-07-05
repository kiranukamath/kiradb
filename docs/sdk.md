# Java SDK

The `kiradb-client` module is a fluent Java client — no RESP3 knowledge
required. It has **zero third-party dependencies**: the wire protocol client
is hand-rolled over `java.net.Socket`, so depending on the SDK adds nothing
else to your classpath.

```java
try (KiraDB db = KiraDB.builder()
        .nodes("localhost:6379")
        .connectionPool(10)
        .connectTimeout(Duration.ofSeconds(2))
        .build()) {

    db.set("user:123", json, Duration.ofHours(24));
    Optional<String> val = db.get("user:123");
}
```

`KiraDB` implements `AutoCloseable` — always use try-with-resources or call
`close()` explicitly to release pooled connections.

## Builder options

| Method | Default | Meaning |
|---|---|---|
| `.nodes(String...)` | — (required) | `host:port` addresses; the first that answers `PING` at build time is used |
| `.connectionPool(int)` | 8 | max pooled connections; each in-flight command holds one for one round trip |
| `.connectTimeout(Duration)` | 2s | TCP connect timeout, and the pool's borrow-wait timeout |
| `.readTimeout(Duration)` | 5s | socket read timeout per command reply |
| `.validateOnBorrow(boolean)` | true | PING a pooled connection before handing it out |

**Sizing the pool:** one connection per concurrently in-flight command. If
your app issues ~N concurrent KiraDB calls under load, size the pool to N (or
slightly above, to absorb bursts) — not to your total thread count.

## Core key-value API

```java
db.set(key, value);
db.set(key, value, Duration.ofHours(1));   // with TTL
Optional<String> v = db.get(key);
boolean removed = db.del(key);
boolean present = db.exists(key);
long secondsLeft = db.ttl(key);            // -1 = no expiry, -2 = missing
boolean changed = db.expire(key, Duration.ofMinutes(5));
db.ping();
```

## Feature flags

```java
db.flags().set("dark-mode", true, 0.10);      // 10% rollout
boolean on = db.flags().isEnabled("dark-mode", userId);
db.flags().convert("dark-mode", userId);       // record a conversion
db.flags().kill("dark-mode");                  // force off, keep rollout %
db.flags().unkill("dark-mode");
List<String> names = db.flags().list();
FlagStats stats = db.flags().stats("dark-mode");
```

## Rate limiter

```java
RateLimiterClient limiter = db.rateLimiter("payments-api");
RateLimitResult r = limiter.allow("user:123", 100, Duration.ofMinutes(1));
if (!r.allowed()) {
    throw new RateLimitException(r.retryAfter());
}
RateLimitResult status = limiter.status("user:123", 100, Duration.ofMinutes(1)); // no consumption
```

## Config store

```java
long version = db.config().set("payment-service", "timeout", "3000");
Optional<String> value = db.config().get("payment-service", "timeout");
List<ConfigEntry> history = db.config().history("payment-service", "timeout");

try (WatchHandle handle = db.config().watch("payment-service", change ->
        log.info("{} = {} (v{})", change.key(), change.value(), change.version()))) {
    // handle.close() to stop watching
}
```

**Watch semantics:** the listener runs on a single dedicated reader thread
shared by every watch registered on this `KiraDB` instance — keep callbacks
fast and never call back into the client from inside one (see
[phase10-sdk.md](internals/phase10-sdk.md) for why this connection lives
outside the pool).

## Semantic cache

```java
Optional<String> cached = db.semanticCache().threshold(0.92).get(userPrompt);
db.semanticCache().set(prompt, llmResponse);                    // no TTL
db.semanticCache().set(prompt, llmResponse, Duration.ofHours(1)); // with TTL
db.semanticCache().delete(prompt);
SemanticCacheStats stats = db.semanticCache().stats();
```

`.threshold(double)` returns a **new** client rather than mutating the
receiver — safe to derive a stricter or looser client per call site from one
shared base.

## Error handling

Every SDK call throws `KiraDBException` (unchecked) on server errors, I/O
failures, or timeouts. There is no checked-exception ceremony — catch it where
you want to handle failures, let it propagate otherwise.

## Publishing (not yet done)

The SDK is not yet published to GitHub Packages — that's a Phase 12 go-live
step requiring Kiran's GitHub credentials. The `maven-publish` block to add
when ready:

```gradle
publishing {
    publications {
        maven(MavenPublication) {
            from components.java
            groupId = 'io.kiradb'
            artifactId = 'kiradb-client'
        }
    }
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/kirankamath/kiradb")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```
