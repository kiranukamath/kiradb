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
