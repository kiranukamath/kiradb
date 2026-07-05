package io.kiradb.client.pool;

import io.kiradb.client.KiraDBException;
import io.kiradb.client.protocol.RespConnection;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-size connection pool with create-on-demand and borrow/return semantics.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Idle queue</b> — returned connections sit in a {@link BlockingQueue}.
 *       Borrow first polls the queue (fast path, no allocation).</li>
 *   <li><b>Create on demand</b> — if the queue is empty and fewer than
 *       {@code maxSize} connections exist, a new one is dialed. The pool never
 *       pre-warms: idle applications hold zero sockets.</li>
 *   <li><b>Bounded wait</b> — if {@code maxSize} connections are all borrowed,
 *       the caller blocks up to {@code borrowTimeoutMs} then fails with
 *       {@link KiraDBException}. Failing fast beats unbounded queueing: pool
 *       exhaustion is a sizing signal, not something to hide.</li>
 *   <li><b>Validate on borrow</b> — optionally PINGs a pooled connection before
 *       handing it out. A PING round trip costs tens of microseconds on
 *       localhost and catches half-closed sockets (server restarts, idle
 *       timeouts, NAT drops) before the caller's real command fails. Disable it
 *       for latency-critical paths and handle retries yourself.</li>
 * </ul>
 *
 * <p>Thread-safe. {@code created} tracks live connections (idle + borrowed);
 * discarding a broken connection decrements it so capacity is never leaked.
 */
public final class ConnectionPool implements AutoCloseable {

    /**
     * Dials a new connection to the pool's target node.
     */
    @FunctionalInterface
    public interface ConnectionFactory {

        /**
         * Open a new connection.
         *
         * @return the new connection
         * @throws IOException if dialing fails
         */
        RespConnection create() throws IOException;
    }

    private final ConnectionFactory factory;
    private final BlockingQueue<RespConnection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private final int maxSize;
    private final long borrowTimeoutMs;
    private final boolean validateOnBorrow;
    private volatile boolean closed;

    /**
     * Create a pool.
     *
     * @param factory          dials new connections
     * @param maxSize          maximum number of live connections
     * @param borrowTimeoutMs  how long a borrower waits when all connections are busy
     * @param validateOnBorrow whether to PING pooled connections before handing them out
     */
    public ConnectionPool(
            final ConnectionFactory factory,
            final int maxSize,
            final long borrowTimeoutMs,
            final boolean validateOnBorrow) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("pool size must be positive, got " + maxSize);
        }
        this.factory = factory;
        this.maxSize = maxSize;
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.validateOnBorrow = validateOnBorrow;
        this.idle = new ArrayBlockingQueue<>(maxSize);
    }

    /**
     * Borrow a connection. The caller MUST hand it back via {@link #release}
     * or {@link #discard} (use try/finally).
     *
     * @return a healthy connection, exclusively owned by the caller
     * @throws KiraDBException if the pool is closed, exhausted past the borrow
     *                         timeout, or a new connection cannot be dialed
     */
    public RespConnection borrow() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(borrowTimeoutMs);
        while (!closed) {
            RespConnection conn = idle.poll();
            if (conn == null) {
                conn = tryCreate();
            }
            if (conn == null) {
                conn = awaitIdle(deadline);
            }
            if (conn == null) {
                throw new KiraDBException(
                        "connection pool exhausted: " + maxSize + " connections busy for "
                                + borrowTimeoutMs + " ms — increase pool size or reduce hold time");
            }
            if (!validateOnBorrow || conn.ping()) {
                return conn;
            }
            discard(conn); // stale socket — drop and loop to create/borrow another
        }
        throw new KiraDBException("connection pool is closed");
    }

    /**
     * Return a healthy connection to the pool.
     *
     * @param conn the connection previously obtained from {@link #borrow}
     */
    public void release(final RespConnection conn) {
        if (closed || !conn.isOpen() || !idle.offer(conn)) {
            discard(conn);
        }
    }

    /**
     * Destroy a broken connection, freeing its capacity slot so a replacement
     * can be created.
     *
     * @param conn the connection to destroy
     */
    public void discard(final RespConnection conn) {
        created.decrementAndGet();
        conn.close();
    }

    /**
     * Number of live connections (idle + borrowed). Exposed for tests and metrics.
     *
     * @return current live connection count
     */
    public int liveCount() {
        return created.get();
    }

    @Override
    public void close() {
        closed = true;
        RespConnection conn;
        while ((conn = idle.poll()) != null) {
            discard(conn);
        }
    }

    /** Attempt to create a new connection if under capacity; null if at capacity. */
    private RespConnection tryCreate() {
        while (true) {
            int current = created.get();
            if (current >= maxSize) {
                return null;
            }
            if (created.compareAndSet(current, current + 1)) {
                try {
                    return factory.create();
                } catch (IOException e) {
                    created.decrementAndGet();
                    throw new KiraDBException("failed to connect: " + e.getMessage(), e);
                }
            }
        }
    }

    /** Wait for a connection to be released, up to the borrow deadline; null on timeout. */
    private RespConnection awaitIdle(final long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        try {
            return idle.poll(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KiraDBException("interrupted while waiting for a pooled connection", e);
        }
    }
}
