# Netty Deep Dive — How KiraDB's Network Layer Works

This document explains every Netty concept used in KiraDB, from first principles to
production-level detail. By the end you will understand exactly what happens at the
network layer when a client connects and sends a command.

**Audience:** Engineers working on or contributing to KiraDB's server module.

---

## Table of Contents

1. [The Problem Netty Solves](#1-the-problem-netty-solves)
2. [The Reactor Pattern](#2-the-reactor-pattern)
3. [Core Concepts Map](#3-core-concepts-map)
4. [EventLoop and EventLoopGroup](#4-eventloop-and-eventloopgroup)
5. [Channel](#5-channel)
6. [ChannelPipeline](#6-channelpipeline)
7. [ChannelHandler](#7-channelhandler)
8. [ByteBuf](#8-bytebuf)
9. [Bootstrap](#9-bootstrap)
10. [How KiraDB Wires It Together](#10-how-kiradb-wires-it-together)
11. [Request Lifecycle — Step by Step](#11-request-lifecycle--step-by-step)
12. [Why Certain Decisions Were Made](#12-why-certain-decisions-were-made)
13. [Common Pitfalls](#13-common-pitfalls)

---

## 1. The Problem Netty Solves

### The naive approach and why it fails

The simplest possible TCP server in Java:

```java
ServerSocket server = new ServerSocket(6379);
while (true) {
    Socket client = server.accept();                 // blocks until client connects
    new Thread(() -> {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        // handle this client until it disconnects
        handle(in, out);                             // blocks in read()
    }).start();
}
```

This is one OS thread per connection. On a modern Linux server:

- Each OS thread uses ~1MB of stack memory by default
- Each thread consumes a kernel scheduling slot
- Switching between 10,000 threads causes **context switch overhead**

```
10,000 connections × 1MB stack = 10 GB of memory just for stacks
```

For a database that needs to handle 100,000+ concurrent connections (think: a
connection pool from each of 1,000 microservice instances with 100 connections each),
this model collapses.

### The NIO approach (what Netty is built on)

Java NIO (New I/O, since Java 1.4) provides non-blocking I/O:

```java
Selector selector = Selector.open();
ServerSocketChannel server = ServerSocketChannel.open();
server.configureBlocking(false);
server.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    selector.select();   // ask the OS: which sockets are ready?
    for (SelectionKey key : selector.selectedKeys()) {
        if (key.isAcceptable()) {
            SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        }
        if (key.isReadable()) {
            SocketChannel client = (SocketChannel) key.channel();
            ByteBuffer buf = ByteBuffer.allocate(256);
            client.read(buf);    // does NOT block — returns immediately
            // process buf...
        }
    }
}
```

The OS-level call here is `epoll` on Linux, `kqueue` on macOS. The OS maintains a
watch list of file descriptors (sockets). You ask: "tell me which ones have bytes ready".
The OS answers immediately. You process those, then ask again.

**Result:** One thread handles thousands of connections. No blocking, no idle waiting.

**Problem:** Writing correct, efficient, production-grade NIO code is extremely hard:

- Partial reads (TCP delivers bytes in chunks, not in messages)
- Buffer management without leaking memory
- Error handling per-connection without affecting others
- SSL/TLS integration
- Backpressure when the client is slower than the server

Netty wraps all of this behind clean abstractions. That is what Netty gives you.

---

## 2. The Reactor Pattern

Netty's architecture is an implementation of the **Reactor Pattern**, described by
Doug Lea in 1995. It is the same pattern used by Node.js, nginx, Redis, and most
high-performance network servers.

```
                    ┌─────────────────────────────────┐
                    │           Reactor               │
                    │                                 │
  new connections   │  ┌──────────┐                   │
  ─────────────────►│  │ Acceptor │                   │
                    │  └────┬─────┘                   │
                    │       │ hands off               │
                    │  ┌────▼─────┐   dispatches      │
                    │  │ Selector │──────────────────►│ Handler 1
                    │  └──────────┘                   │ Handler 2
                    │                                 │ Handler 3
                    └─────────────────────────────────┘
```

**Core idea:**
- One or a few threads **wait** for events (new connection, bytes arrived, write complete)
- When an event arrives, the reactor **dispatches** it to the appropriate handler
- Handlers must run fast and never block — if they need to do slow work (disk I/O,
  network calls), they hand it off to another thread pool

In Netty's terms:
- **bossGroup** = Acceptor (listens for new TCP connections)
- **workerGroup** = Reactor (handles I/O on accepted connections)
- **ChannelHandler** = your application logic

---

## 3. Core Concepts Map

Before going deep, here is how all the pieces relate:

```
KiraDBServer (main)
│
├── ServerBootstrap ──────────────────────── configures everything
│   ├── bossGroup (1 thread) ─────────────── accepts TCP connections
│   └── workerGroup (N threads) ──────────── handles I/O
│
└── For each new connection, creates a:
    │
    └── Channel ───────────────────────────── represents one TCP connection
        └── ChannelPipeline ────────────────── ordered list of handlers for this connection
            ├── Resp3Decoder  ──────────────── handler 1: bytes → Resp3Value
            ├── Resp3Encoder  ──────────────── handler 2: Resp3Value → bytes
            └── KiraDBChannelHandler ─────────  handler 3: Resp3Value → Command → response
```

Every connection is independent. Each has its own `Channel` and its own `ChannelPipeline`.
The handlers in the pipeline are called in order for every event on that connection.

---

## 4. EventLoop and EventLoopGroup

### EventLoop

An `EventLoop` is a single thread that runs an infinite loop:

```
while (!shutdown) {
    // Phase 1: ask the OS which sockets have activity
    int readyCount = selector.select(timeoutMs);

    // Phase 2: process each ready socket
    for (SelectionKey key : selector.selectedKeys()) {
        processEvent(key);   // calls your ChannelHandlers
    }

    // Phase 3: run any scheduled tasks (timers, heartbeats)
    runAllTasks();
}
```

One `EventLoop` manages multiple `Channel`s (connections). It is the single thread
responsible for all I/O on those connections. **No other thread touches those channels.**
This is why Netty's handlers don't need synchronization — one channel is always
processed by exactly one EventLoop thread.

### EventLoopGroup

An `EventLoopGroup` is a pool of `EventLoop`s:

```java
// In KiraDB:
EventLoopGroup bossGroup   = new NioEventLoopGroup(1);   // 1 EventLoop
EventLoopGroup workerGroup = new NioEventLoopGroup();     // 2 × CPU cores EventLoops
```

**bossGroup (1 thread):**
- Listens on port 6379 for new TCP connections
- Calls `accept()` on the OS
- When a new connection arrives, registers it with one of the workerGroup EventLoops
- That's it. Does nothing else.

**workerGroup (N threads):**
- Each EventLoop owns a set of connections
- Runs the event loop: select → dispatch → run tasks → repeat
- Calls your ChannelHandlers when bytes arrive

```
bossGroup
  EventLoop-0: accept() → new connection → assign to workerGroup

workerGroup
  EventLoop-0: handles connections  1,  5,  9, 13, 17 ...
  EventLoop-1: handles connections  2,  6, 10, 14, 18 ...
  EventLoop-2: handles connections  3,  7, 11, 15, 19 ...
  EventLoop-3: handles connections  4,  8, 12, 16, 20 ...
  (on a 4-core CPU: 2 × 4 = 8 EventLoops in workerGroup)
```

Connections are assigned round-robin. Each EventLoop handles its assigned connections
for the lifetime of those connections. One EventLoop thread, thousands of connections,
zero blocking.

### The golden rule of EventLoop threads

> **Never block an EventLoop thread.**

If your handler does `Thread.sleep()`, waits for a database response, or reads from
a file, you've frozen the EventLoop. That single thread handles hundreds or thousands
of connections — blocking it starves all of them.

For slow operations (disk I/O in Phase 3, Raft log writes in Phase 4), submit to a
separate thread pool:

```java
// WRONG — blocks the EventLoop
protected void channelRead0(ChannelHandlerContext ctx, Resp3Value msg) {
    byte[] result = Files.readAllBytes(somePath);  // blocks!
    ctx.writeAndFlush(new Resp3Value.BulkString(result));
}

// RIGHT — hand off to a separate executor
protected void channelRead0(ChannelHandlerContext ctx, Resp3Value msg) {
    CompletableFuture
        .supplyAsync(() -> Files.readAllBytes(somePath), diskExecutor)
        .thenAccept(result ->
            ctx.writeAndFlush(new Resp3Value.BulkString(result)));
}
```

In Phase 2, our `InMemoryStorageEngine` is a `ConcurrentHashMap.get()` — nanosecond
operation, safe on the EventLoop. In Phase 3 when we add disk I/O, we will revisit this.

---

## 5. Channel

A `Channel` represents one TCP connection. It wraps the Java `SocketChannel` (NIO)
and adds:

- **Identity** — every channel has a unique `ChannelId`
- **State** — open, connecting, connected, closing, closed
- **Pipeline** — the chain of handlers that process events
- **EventLoop reference** — which EventLoop owns this channel
- **Write buffer** — outbound data queued for flushing

```java
// In KiraDBChannelHandler.exceptionCaught():
ctx.close();  // closes the Channel — Netty cleans up everything
```

Important `Channel` methods you'll use:

```java
channel.writeAndFlush(msg);   // write a message and flush to the socket
channel.close();              // close this connection
channel.isActive();           // is the connection still open?
channel.remoteAddress();      // the client's IP:port
channel.eventLoop();          // the EventLoop managing this channel
```

You rarely interact with `Channel` directly. You use `ChannelHandlerContext` (see below),
which gives you access to the channel and the pipeline in context.

---

## 6. ChannelPipeline

The `ChannelPipeline` is an ordered doubly-linked list of `ChannelHandler`s.
Every channel has exactly one pipeline.

```
       INBOUND direction (data arriving from the network)
       ──────────────────────────────────────────────────►

┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐
│ Resp3Decoder │ → │ Resp3Encoder │ → │ KiraDBChannelHandler │
└──────────────┘   └──────────────┘   └──────────────────────┘

       ◄──────────────────────────────────────────────────
       OUTBOUND direction (data going to the network)
```

Wait — that doesn't look right. Let me clarify with Netty's actual model:

**Inbound events** travel left-to-right through **inbound handlers**:
- `channelRead()` — bytes arrived from client
- `channelActive()` — connection established
- `channelInactive()` — connection closed

**Outbound events** travel right-to-left through **outbound handlers**:
- `write()` — send data to client
- `flush()` — flush the write buffer to the socket
- `close()` — close the connection

A handler can be inbound-only, outbound-only, or both:

```java
// Inbound only — reads data coming in
class Resp3Decoder extends ByteToMessageDecoder { ... }

// Outbound only — serializes data going out
class Resp3Encoder extends MessageToByteEncoder<Resp3Value> { ... }

// Both — receives data and can write responses
class KiraDBChannelHandler extends SimpleChannelInboundHandler<Resp3Value> { ... }
```

The actual pipeline in KiraDB for one connection:

```
Network ──► [Resp3Decoder] ──► [Resp3Encoder] ──► [KiraDBChannelHandler]
         inbound only        outbound only          inbound (reads) +
                                                    outbound (writes response)
```

### ChannelHandlerContext

Every handler in the pipeline is wrapped in a `ChannelHandlerContext`. This is
what gets passed to your handler methods as `ctx`:

```java
protected void channelRead0(ChannelHandlerContext ctx, Resp3Value msg) {
    // ctx gives you:
    ctx.channel();          // the Channel for this connection
    ctx.pipeline();         // the ChannelPipeline
    ctx.writeAndFlush(x);  // write and flush outbound (travels back through pipeline)
    ctx.fireChannelRead(x); // pass event to NEXT inbound handler
    ctx.close();            // close this connection
}
```

`ctx.writeAndFlush(response)` is how KiraDB sends the response back. It travels
right-to-left through the pipeline, hitting the encoder, which converts `Resp3Value`
to bytes, which get written to the socket.

---

## 7. ChannelHandler

`ChannelHandler` is the interface for your application logic. Netty provides
several abstract base classes that handle the boilerplate:

### `ByteToMessageDecoder` — used by `Resp3Decoder`

Accumulates bytes until you have a complete message, then produces objects:

```java
public final class Resp3Decoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();          // save position in case we don't have enough bytes

        Resp3Value value = tryParse(in);

        if (value == null) {
            in.resetReaderIndex();     // not enough bytes — restore and wait
            return;                    // Netty will call us again when more bytes arrive
        }

        out.add(value);                // complete value — pass to next handler
    }
}
```

The key feature: **it handles partial frames automatically**. TCP is a stream.
`SET hello world` (26 bytes) might arrive as:
- One delivery: all 26 bytes
- Two deliveries: 10 bytes, then 16 bytes
- Twenty-six deliveries: 1 byte each

`ByteToMessageDecoder` buffers all arrivals. Your `decode()` is called whenever
bytes are available. You read what you can. If incomplete, you reset and wait.
Netty accumulates more bytes and calls you again.

**Important:** `ByteToMessageDecoder` is **NOT `@Sharable`**. It has per-connection
buffer state (the accumulation buffer). Each connection needs its own instance.
This is why `initChannel()` does `new Resp3Decoder()` — a fresh decoder per connection.

### `MessageToByteEncoder<T>` — used by `Resp3Encoder`

Converts objects to bytes for outbound (sending to client):

```java
public final class Resp3Encoder extends MessageToByteEncoder<Resp3Value> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Resp3Value msg, ByteBuf out) {
        // write RESP3 bytes into 'out'
        // Netty allocates 'out' from its pool and sends it to the socket
    }
}
```

Netty calls `encode()` when you call `ctx.writeAndFlush(someResp3Value)`.
It allocates a `ByteBuf` from its pool, passes it to your `encode()`, then sends
it to the socket. You never manage the buffer lifecycle — Netty does.

### `SimpleChannelInboundHandler<T>` — used by `KiraDBChannelHandler`

Receives decoded objects (typed). Automatically releases the message after
`channelRead0()` returns:

```java
@ChannelHandler.Sharable
public final class KiraDBChannelHandler extends SimpleChannelInboundHandler<Resp3Value> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Resp3Value msg) {
        // msg is a Resp3Value produced by Resp3Decoder
        // process it, write a response
        ctx.writeAndFlush(someResponse);
        // msg is automatically released after this method returns
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // called if any handler in the pipeline throws
        // always close the connection after an unhandled exception
        ctx.close();
    }
}
```

### `@ChannelHandler.Sharable`

Netty enforces a rule: a stateful handler instance cannot be added to multiple
pipelines. If you try, Netty throws at runtime:

```
ChannelHandler KiraDBChannelHandler is not allowed to be shared
```

The `@Sharable` annotation is your declaration: "I guarantee this handler has no
per-connection state. It is safe to share across all connections."

`KiraDBChannelHandler` qualifies because it holds only:
- `CommandRouter router` — shared, stateless, reads from a shared storage engine

`Resp3Decoder` does **not** qualify because it holds:
- A cumulative `ByteBuf` of partial data for this specific connection

---

## 8. ByteBuf

`ByteBuf` is Netty's replacement for Java's `ByteBuffer`. It is the most important
low-level concept in Netty.

### Why not just use `ByteBuffer`?

Java's `ByteBuffer` has two major limitations for network code:

**1. Manual position management:**
```java
// Java ByteBuffer — one position pointer for both read and write
ByteBuffer buf = ByteBuffer.allocate(256);
buf.put("hello".getBytes());   // writes, position moves forward
buf.flip();                    // must call flip() to switch to read mode!
buf.get(bytes);                // reads
buf.compact();                 // must call compact() to switch back to write mode!
```

Forgetting `flip()` is a notoriously common bug.

**2. No pooling:**
```java
// Every call allocates new memory — GC pressure at high throughput
ByteBuffer buf = ByteBuffer.allocate(256);
```

At 500,000 requests/second, allocating a new `ByteBuffer` per request generates
massive GC pressure.

### ByteBuf solves both

**Two independent indices:**
```java
ByteBuf buf = ...;
//  readerIndex       writerIndex
//      ▼                 ▼
// [already read | readable bytes | writable space]

buf.writeBytes(data);    // writes at writerIndex, advances writerIndex
buf.readBytes(dest);     // reads from readerIndex, advances readerIndex
buf.readableBytes();     // writerIndex - readerIndex
buf.writableBytes();     // capacity - writerIndex
```

No `flip()`. No `compact()`. Reads and writes are independent.

**Mark and reset (used in `Resp3Decoder`):**
```java
buf.markReaderIndex();      // save current readerIndex
// ... try to parse ...
buf.resetReaderIndex();     // restore to saved position if parsing fails
```

This is how we handle partial frames without data loss.

**Pooling:**
```java
// Netty allocates from a pool — no GC pressure
ByteBuf buf = ctx.alloc().buffer(256);
// use it...
buf.release();   // returns to pool, not GC
```

Netty manages `ByteBuf` lifecycle with reference counting. `ByteToMessageDecoder`
and `MessageToByteEncoder` handle the reference counting for you automatically.
You only call `release()` manually when you allocate buffers yourself.

### Key ByteBuf methods used in `Resp3Decoder`

```java
buf.isReadable()              // true if readerIndex < writerIndex
buf.readByte()                // read 1 byte, advance readerIndex
buf.readableBytes()           // bytes available to read
buf.getByte(index)            // read byte at absolute index (NO advance)
buf.skipBytes(n)              // advance readerIndex by n (discard bytes)
buf.writerIndex()             // current write position
buf.toString(offset, length, charset)  // read as String without advancing
buf.markReaderIndex()         // save readerIndex
buf.resetReaderIndex()        // restore saved readerIndex
```

---

## 9. Bootstrap

`ServerBootstrap` is the configuration object that wires everything together.
Think of it as a builder for the server.

```java
ServerBootstrap bootstrap = new ServerBootstrap()

    // Which EventLoopGroups to use
    .group(bossGroup, workerGroup)

    // What type of server socket to use
    // NioServerSocketChannel = Java NIO's non-blocking server socket
    .channel(NioServerSocketChannel.class)

    // Options for the server socket itself (the listening socket)
    .option(ChannelOption.SO_BACKLOG, 1024)

    // Options applied to each accepted connection's socket
    .childOption(ChannelOption.TCP_NODELAY, true)
    .childOption(ChannelOption.SO_KEEPALIVE, true)

    // What handlers to put in each new connection's pipeline
    .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) {
            // Called once per new connection
            ch.pipeline()
                .addLast(new Resp3Decoder())       // new instance (stateful)
                .addLast(new Resp3Encoder())        // new instance (convention)
                .addLast(channelHandler);           // shared instance (@Sharable)
        }
    });

// Bind to port and wait until bound
bootstrap.bind(6379).sync()
    .channel()
    .closeFuture().sync();   // block until server shuts down
```

### Socket options explained

**`SO_BACKLOG = 1024`**

When the bossGroup is busy (e.g., during a burst), the OS queues incoming connection
requests. `SO_BACKLOG` is that queue depth. If more than 1024 connections arrive
simultaneously before the boss can `accept()` them, the OS rejects new ones with
a `Connection refused`.

**`TCP_NODELAY = true`**

Nagle's algorithm (RFC 896): TCP waits up to 200ms before sending small packets,
hoping to batch them with other data to reduce packet count. This is good for
bulk data transfer (fewer packets, higher throughput). It is catastrophic for
request-response protocols like RESP3.

Example without `TCP_NODELAY`:
```
Client sends: SET hello world (26 bytes)
TCP waits 200ms to see if more data comes...
Server sees the request 200ms late
Response goes back... another 200ms wait?
Total latency: 200-400ms for a simple SET
```

With `TCP_NODELAY`: the packet goes out immediately. Latency is the actual network
round-trip time (sub-millisecond on localhost).

**`SO_KEEPALIVE = true`**

If a client crashes (power outage, OOM kill, network failure) without sending a
TCP FIN, the server's socket stays open forever — leaking a connection slot and
an EventLoop registration. `SO_KEEPALIVE` instructs the OS to send probe packets
after ~2 hours of inactivity. If no response, the OS closes the socket and Netty's
`channelInactive()` is called.

---

## 10. How KiraDB Wires It Together

Here is the complete wiring in `KiraDBServer.main()`:

```
InMemoryStorageEngine               // ConcurrentHashMap — the data store
         │
         ▼
CommandRouter(storage)             // maps "SET" → SetHandler, "GET" → GetHandler, etc.
         │
         ▼
KiraDBChannelHandler(router)       // @Sharable — shared across ALL connections
         │
         ▼
ServerBootstrap                    // configures Netty
  ├── bossGroup (1 thread)
  ├── workerGroup (2×CPU threads)
  └── childHandler: ChannelInitializer
        └── For each new connection:
              ch.pipeline()
                .addLast(new Resp3Decoder())        // per-connection
                .addLast(new Resp3Encoder())         // per-connection
                .addLast(channelHandler)            // shared
```

Object creation happens once at startup. The `ChannelInitializer` runs once per
new connection. The `KiraDBChannelHandler` instance is shared across all connections.

---

## 11. Request Lifecycle — Step by Step

Let's trace `redis-cli -p 6379 SET hello world` through every layer:

### Step 1: TCP connection

```
redis-cli → OS → Netty bossGroup EventLoop
```

The OS receives the SYN packet. The bossGroup EventLoop's `selector.select()` returns
with `OP_ACCEPT` ready. The boss calls `accept()`, gets a `SocketChannel`, wraps it
in a Netty `NioSocketChannel`, and registers it with one of the workerGroup EventLoops.

### Step 2: redis-cli sends the command

redis-cli encodes `SET hello world` as RESP3:

```
*3\r\n
$3\r\nSET\r\n
$5\r\nhello\r\n
$5\r\nworld\r\n
```

26 bytes hit the wire.

### Step 3: Netty receives bytes

The workerGroup EventLoop's `selector.select()` returns with `OP_READ` ready on this
connection's socket. Netty reads bytes from the OS into a pooled `ByteBuf`.

### Step 4: `Resp3Decoder.decode()` is called

```java
// in = ByteBuf containing: *3\r\n$3\r\nSET\r\n$5\r\nhello\r\n$5\r\nworld\r\n

in.markReaderIndex();   // readerIndex = 0

byte type = in.readByte();   // reads '*', readerIndex = 1
// switch: case '*' → readArray()

String countLine = readLine(in);   // reads "3", readerIndex = 4  (past *3\r\n)
// count = 3, allocate ArrayList(3)

// element 0:
byte type2 = in.readByte();   // reads '$', readerIndex = 5
// readBulkString():
String len = readLine(in);    // reads "3", readerIndex = 8  (past $3\r\n)
byte[] bytes = new byte[3];
in.readBytes(bytes);          // reads "SET", readerIndex = 11
in.skipBytes(2);              // skips \r\n, readerIndex = 13
// → BulkString("SET")

// element 1: same process → BulkString("hello"), readerIndex = 21
// element 2: same process → BulkString("world"), readerIndex = 31 (= writerIndex)

// → RespArray([BulkString("SET"), BulkString("hello"), BulkString("world")])
out.add(that);  // Netty passes to next handler
```

### Step 5: `KiraDBChannelHandler.channelRead0()` is called

```java
// msg = RespArray([BulkString("SET"), BulkString("hello"), BulkString("world")])

Command command = toCommand(msg);
// name = "SET"
// args = [bytes("hello"), bytes("world")]
// → Command("SET", [hello_bytes, world_bytes])

Resp3Value response = router.route(command);
// router.handlers.get("SET") → SetHandler
// SetHandler.execute(Command("SET", [...]), storage)
//   storage.put(bytes("hello"), bytes("world"))   ← ConcurrentHashMap.put()
//   return SimpleString("OK")
// → SimpleString("OK")

ctx.writeAndFlush(response);
// queues SimpleString("OK") for outbound, then flushes
```

### Step 6: `Resp3Encoder.encode()` is called

```java
// msg = SimpleString("OK")
// Netty allocates ByteBuf from pool

switch (value) {
    case Resp3Value.SimpleString ss -> {
        out.writeByte('+');         // ByteBuf: [+]
        writeString("OK", out);     // ByteBuf: [+OK]
        out.writeBytes(CRLF);       // ByteBuf: [+OK\r\n]
    }
}
// Netty writes ByteBuf to socket, returns ByteBuf to pool
```

### Step 7: redis-cli receives the response

```
redis-cli reads: +OK\r\n
redis-cli prints: OK
```

Total time: microseconds. The EventLoop thread was never blocked.

---

## 12. Why Certain Decisions Were Made

### Why one bossGroup thread?

The boss does only `accept()` — extremely fast. One thread is always enough. Adding
more boss threads doesn't help because the OS's `accept()` queue is processed
sequentially anyway.

### Why `NioEventLoopGroup` and not `EpollEventLoopGroup`?

Netty has a Linux-specific `EpollEventLoopGroup` that uses `epoll` directly, bypassing
Java's NIO abstraction layer. It's slightly faster and supports edge-triggered mode.

```java
// Linux-only, slightly faster
EventLoopGroup workerGroup = new EpollEventLoopGroup();

// Cross-platform (uses epoll on Linux, kqueue on macOS, select elsewhere)
EventLoopGroup workerGroup = new NioEventLoopGroup();
```

KiraDB uses `NioEventLoopGroup` for portability — it builds and runs on macOS (dev)
and Linux (prod) without changes. In a production-only Linux deployment, switching
to `EpollEventLoopGroup` would give a small throughput improvement.

### Why is `Resp3Decoder` new per connection but `KiraDBChannelHandler` is shared?

`Resp3Decoder` extends `ByteToMessageDecoder`, which maintains an internal `ByteBuf`
to accumulate partial frames. That buffer is per-connection state — if shared,
connection A's partial data would mix with connection B's. Each connection needs its
own decoder.

`KiraDBChannelHandler` has no per-connection state. It holds a `CommandRouter`,
which is also stateless (it routes to stateless handlers). The actual state
(key-value data) lives in `InMemoryStorageEngine`, which is thread-safe
(`ConcurrentHashMap`). So one handler instance can safely be shared across 100,000
connections simultaneously.

### Why `ctx.writeAndFlush()` and not `ctx.write()` followed by `ctx.flush()`?

`write()` places data in the channel's outbound buffer but does not send it.
`flush()` actually triggers the write to the socket.

`writeAndFlush()` is both in one call — correct for request-response where every
response should go out immediately. For bulk writes where you want to batch,
you'd use separate `write()` calls followed by one `flush()`.

### Why `TCP_NODELAY`?

Without it, a `PING` command takes 200-400ms due to Nagle's algorithm. This is
unacceptable for a database. With `TCP_NODELAY`, latency is just the actual network
round-trip. See [Socket options explained](#socket-options-explained) above.

---

## 13. Common Pitfalls

### Pitfall 1: Blocking the EventLoop

```java
// WRONG — Thread.sleep blocks the EventLoop
protected void channelRead0(ChannelHandlerContext ctx, Resp3Value msg) {
    Thread.sleep(1000);   // THIS FREEZES ALL CONNECTIONS ON THIS EVENTLOOP
}

// RIGHT — use a separate scheduled task if you need delays
ctx.channel().eventLoop().schedule(() -> {
    ctx.writeAndFlush(someResponse);
}, 1, TimeUnit.SECONDS);
```

### Pitfall 2: Accessing a Channel from a non-EventLoop thread

```java
// WRONG — writing from a random thread while EventLoop might also be writing
someOtherThread.execute(() -> {
    channel.writeAndFlush(response);  // race condition with EventLoop
});

// RIGHT — submit to the channel's EventLoop
channel.eventLoop().execute(() -> {
    channel.writeAndFlush(response);  // runs on the EventLoop thread
});
```

### Pitfall 3: Sharing a non-`@Sharable` handler

```java
// WRONG — Resp3Decoder is stateful (has buffer), must not be shared
KiraDBChannelHandler shared = ...;
Resp3Decoder sharedDecoder = new Resp3Decoder();   // ← WRONG

.childHandler(new ChannelInitializer<SocketChannel>() {
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
            .addLast(sharedDecoder)    // Netty throws ChannelPipelineException
            .addLast(shared);
    }
});

// RIGHT — new decoder per connection
.childHandler(new ChannelInitializer<SocketChannel>() {
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
            .addLast(new Resp3Decoder())  // fresh instance per connection
            .addLast(shared);
    }
});
```

### Pitfall 4: Forgetting to handle partial frames

```java
// WRONG — assumes all bytes arrive at once
protected void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    // process all bytes as if they're a complete message — WRONG
}

// RIGHT — use ByteToMessageDecoder which handles this for you
// See Resp3Decoder — markReaderIndex/resetReaderIndex pattern
```

### Pitfall 5: Memory leak — not releasing ByteBuf

```java
// WRONG — buffer is never released, memory leaks
protected void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    // process...
    // forgot to call buf.release() ← memory leak
}

// RIGHT — use SimpleChannelInboundHandler which releases automatically
// OR call ReferenceCountUtil.release(msg) manually
```

`SimpleChannelInboundHandler` calls `ReferenceCountUtil.release(msg)` after
`channelRead0()` returns. This is why `KiraDBChannelHandler` extends it.

---

## Summary

| Concept | What it is | In KiraDB |
|---|---|---|
| `EventLoop` | Single thread running the I/O loop | workerGroup threads |
| `EventLoopGroup` | Pool of EventLoops | bossGroup (1), workerGroup (N) |
| `Channel` | One TCP connection | Created per client connection |
| `ChannelPipeline` | Ordered chain of handlers per connection | Decoder → Encoder → Handler |
| `ByteToMessageDecoder` | Handles partial frames, produces objects | `Resp3Decoder` |
| `MessageToByteEncoder` | Serializes objects to bytes | `Resp3Encoder` |
| `SimpleChannelInboundHandler` | Receives typed objects, auto-releases | `KiraDBChannelHandler` |
| `@Sharable` | Declares handler has no per-connection state | `KiraDBChannelHandler` |
| `ByteBuf` | Pooled, dual-index byte buffer | Used by decoder and encoder |
| `ServerBootstrap` | Configuration builder | `KiraDBServer.start()` |
| `ChannelInitializer` | Runs once per new connection to build pipeline | `initChannel()` |
| `TCP_NODELAY` | Disables Nagle's algorithm — send immediately | Set on all connections |

---

## Further Reading

- [Netty User Guide](https://netty.io/wiki/user-guide-for-4.x.html) — official Netty docs
- [Scalable I/O in Java — Doug Lea](http://gee.cs.oswego.edu/dl/cpjslides/nio.pdf) — the original Reactor Pattern paper
- [The C10K Problem](http://www.kegel.com/c10k.html) — why the thread-per-connection model fails
- [Netty in Action (Book)](https://www.manning.com/books/netty-in-action) — comprehensive Netty reference
