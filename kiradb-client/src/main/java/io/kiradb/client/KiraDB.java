package io.kiradb.client;

import io.kiradb.client.pool.ConnectionPool;
import io.kiradb.client.protocol.RespConnection;
import io.kiradb.client.protocol.RespValue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entry point of the KiraDB Java SDK — a fluent client over the RESP3 wire
 * protocol with a built-in connection pool. No knowledge of RESP3 required.
 *
 * <pre>{@code
 * try (KiraDB db = KiraDB.builder()
 *         .nodes("localhost:6379")
 *         .connectionPool(10)
 *         .connectTimeout(Duration.ofSeconds(2))
 *         .build()) {
 *
 *     db.set("user:123", json, Duration.ofHours(24));
 *     Optional<String> val = db.get("user:123");
 *
 *     boolean enabled = db.flags().isEnabled("dark-mode", userId);
 *     RateLimitResult r = db.rateLimiter("api").allow("user:123", 100, Duration.ofMinutes(1));
 *     db.config().watch("payment-service", change -> log.info("{}", change));
 *     Optional<String> cached = db.semanticCache().threshold(0.92).get(prompt);
 * }
 * }</pre>
 *
 * <p>Thread-safe: commands borrow a pooled connection for exactly one
 * request/response round trip. Config watches run on a dedicated subscriber
 * connection outside the pool (see {@link ConfigClient#watch}).
 *
 * <p><b>Multi-node note:</b> {@code nodes(...)} accepts several addresses but the
 * current implementation picks the <em>first healthy</em> node at build time and
 * sticks with it. Failover and request routing are future work.
 */
public final class KiraDB implements AutoCloseable {

    private final ConnectionPool pool;
    private final HostPort node;
    private final int connectTimeoutMs;
    private final Object watchLock = new Object();
    private WatchDispatcher watchDispatcher;
    private volatile boolean closed;

    private KiraDB(final ConnectionPool pool, final HostPort node, final int connectTimeoutMs) {
        this.pool = pool;
        this.node = node;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * Start building a client.
     *
     * @return a new builder with defaults (pool of 8, 2 s connect, 5 s read timeout)
     */
    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------
    // Core key-value API
    // ------------------------------------------------------------------

    /**
     * Verify connectivity.
     *
     * @throws KiraDBException if the server does not answer PONG
     */
    public void ping() {
        String reply = Replies.asSimple(call("PING"));
        if (!"PONG".equals(reply)) {
            throw new KiraDBException("unexpected PING reply: " + reply);
        }
    }

    /**
     * Store a key-value pair (no expiry).
     *
     * @param key   the key
     * @param value the value
     */
    public void set(final String key, final String value) {
        Replies.asSimple(call("SET", key, value));
    }

    /**
     * Store a key-value pair that expires after {@code ttl}.
     *
     * @param key   the key
     * @param value the value
     * @param ttl   time to live; must be positive
     */
    public void set(final String key, final String value, final Duration ttl) {
        requirePositive(ttl, "ttl");
        Replies.asSimple(call("SET", key, value, "PX", Long.toString(ttl.toMillis())));
    }

    /**
     * Fetch a value.
     *
     * @param key the key
     * @return the value, or empty if the key does not exist or has expired
     */
    public Optional<String> get(final String key) {
        return Optional.ofNullable(Replies.asStringOrNull(call("GET", key)));
    }

    /**
     * Delete a key.
     *
     * @param key the key
     * @return true if the key existed and was removed
     */
    public boolean del(final String key) {
        return Replies.asLong(call("DEL", key)) > 0;
    }

    /**
     * Check whether a key exists.
     *
     * @param key the key
     * @return true if the key exists (and has not expired)
     */
    public boolean exists(final String key) {
        return Replies.asLong(call("EXISTS", key)) > 0;
    }

    /**
     * Remaining time-to-live of a key, using Redis conventions.
     *
     * @param key the key
     * @return remaining TTL in seconds; {@code -1} if the key exists without an
     *         expiry; {@code -2} if the key does not exist
     */
    public long ttl(final String key) {
        return Replies.asLong(call("TTL", key));
    }

    /**
     * Set (or replace) the expiry on an existing key.
     *
     * @param key the key
     * @param ttl new time to live from now; must be positive
     * @return true if the key exists and the expiry was set
     */
    public boolean expire(final String key, final Duration ttl) {
        requirePositive(ttl, "ttl");
        return Replies.asLong(call("PEXPIRE", key, Long.toString(ttl.toMillis()))) == 1;
    }

    // ------------------------------------------------------------------
    // Service facades
    // ------------------------------------------------------------------

    /**
     * Feature flag operations ({@code FLAG.*}).
     *
     * @return the flags facade (stateless — cheap to call repeatedly)
     */
    public FlagsClient flags() {
        return new FlagsClient(this);
    }

    /**
     * Rate limiter operations ({@code RL.*}) for one named limiter.
     *
     * @param limiterName the limiter name, e.g. {@code "payments-api"}
     * @return the rate limiter facade
     */
    public RateLimiterClient rateLimiter(final String limiterName) {
        return new RateLimiterClient(this, limiterName);
    }

    /**
     * Config store operations ({@code CFG.*}) including live watches.
     *
     * @return the config facade
     */
    public ConfigClient config() {
        return new ConfigClient(this);
    }

    /**
     * Semantic cache operations ({@code SC.*}) using the server's default
     * similarity threshold. Chain {@link SemanticCacheClient#threshold(double)}
     * to override per call site.
     *
     * @return the semantic cache facade
     */
    public SemanticCacheClient semanticCache() {
        return new SemanticCacheClient(this, SemanticCacheClient.SERVER_DEFAULT_THRESHOLD);
    }

    @Override
    public void close() {
        closed = true;
        synchronized (watchLock) {
            if (watchDispatcher != null) {
                watchDispatcher.close();
                watchDispatcher = null;
            }
        }
        pool.close();
    }

    // ------------------------------------------------------------------
    // Internals shared with the facades (package-private)
    // ------------------------------------------------------------------

    /**
     * Execute one command on a pooled connection and map error replies to
     * {@link KiraDBException}. Broken connections are discarded, healthy ones
     * returned to the pool.
     */
    RespValue call(final String... args) {
        if (closed) {
            throw new KiraDBException("client is closed");
        }
        RespConnection conn = pool.borrow();
        boolean broken = false;
        try {
            RespValue reply = conn.command(args);
            if (reply instanceof RespValue.Error err) {
                throw new KiraDBException("server error: " + err.message());
            }
            return reply;
        } catch (IOException e) {
            broken = true;
            throw new KiraDBException("I/O failure talking to " + node + ": " + e.getMessage(), e);
        } finally {
            if (broken) {
                pool.discard(conn);
            } else {
                pool.release(conn);
            }
        }
    }

    /**
     * Lazily create the dedicated subscriber connection + dispatcher used for
     * {@code CFG.WATCH}. One per client, shared across all watches.
     */
    WatchDispatcher watchDispatcher() {
        synchronized (watchLock) {
            if (closed) {
                throw new KiraDBException("client is closed");
            }
            if (watchDispatcher == null) {
                try {
                    // readTimeout 0: this connection legitimately idles between pushes.
                    RespConnection conn = new RespConnection(
                            node.host(), node.port(), connectTimeoutMs, 0);
                    watchDispatcher = new WatchDispatcher(conn);
                } catch (IOException e) {
                    throw new KiraDBException(
                            "failed to open subscriber connection: " + e.getMessage(), e);
                }
            }
            return watchDispatcher;
        }
    }

    private static void requirePositive(final Duration d, final String what) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(what + " must be a positive duration, got " + d);
        }
    }

    /** A parsed {@code host:port} address. */
    private record HostPort(String host, int port) {

        static HostPort parse(final String address) {
            int colon = address.lastIndexOf(':');
            if (colon <= 0 || colon == address.length() - 1) {
                throw new IllegalArgumentException(
                        "node address must be host:port, got '" + address + "'");
            }
            return new HostPort(
                    address.substring(0, colon),
                    Integer.parseInt(address.substring(colon + 1)));
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    /**
     * Fluent configuration for {@link KiraDB} clients.
     */
    public static final class Builder {

        private final List<HostPort> nodes = new ArrayList<>();
        private int poolSize = 8;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private boolean validateOnBorrow = true;

        private Builder() {
        }

        /**
         * Node addresses as {@code host:port}. At least one is required. The
         * first node that answers PING at build time becomes the target.
         *
         * @param addresses one or more {@code host:port} addresses
         * @return this builder
         */
        public Builder nodes(final String... addresses) {
            for (String address : addresses) {
                nodes.add(HostPort.parse(address));
            }
            return this;
        }

        /**
         * Maximum number of pooled connections (default 8). Size for your
         * concurrency: each in-flight command holds one connection for one
         * round trip.
         *
         * @param maxConnections the pool capacity
         * @return this builder
         */
        public Builder connectionPool(final int maxConnections) {
            this.poolSize = maxConnections;
            return this;
        }

        /**
         * TCP connect timeout for new connections (default 2 s). Also used as
         * the pool borrow timeout when all connections are busy.
         *
         * @param timeout the connect timeout
         * @return this builder
         */
        public Builder connectTimeout(final Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * Socket read timeout per command reply (default 5 s). A reply slower
         * than this fails the command with {@link KiraDBException}.
         *
         * @param timeout the read timeout
         * @return this builder
         */
        public Builder readTimeout(final Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        /**
         * Whether the pool PINGs a connection before lending it out (default
         * true). Costs one round trip per borrow; catches server restarts and
         * silently dropped sockets before your command fails instead of after.
         *
         * @param validate true to validate on borrow
         * @return this builder
         */
        public Builder validateOnBorrow(final boolean validate) {
            this.validateOnBorrow = validate;
            return this;
        }

        /**
         * Probe the configured nodes, pick the first healthy one, and build the client.
         *
         * @return a ready-to-use client
         * @throws KiraDBException if no node is reachable
         */
        public KiraDB build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("at least one node address is required");
            }
            int connectMs = (int) connectTimeout.toMillis();
            int readMs = (int) readTimeout.toMillis();
            HostPort target = firstHealthy(connectMs, readMs);
            ConnectionPool pool = new ConnectionPool(
                    () -> new RespConnection(target.host(), target.port(), connectMs, readMs),
                    poolSize,
                    connectTimeout.toMillis(),
                    validateOnBorrow);
            return new KiraDB(pool, target, connectMs);
        }

        private HostPort firstHealthy(final int connectMs, final int readMs) {
            List<String> failures = new ArrayList<>();
            for (HostPort candidate : nodes) {
                try (RespConnection probe = new RespConnection(
                        candidate.host(), candidate.port(), connectMs, readMs)) {
                    if (probe.ping()) {
                        return candidate;
                    }
                    failures.add(candidate + ": connected but PING failed");
                } catch (IOException e) {
                    failures.add(candidate + ": " + e.getMessage());
                }
            }
            throw new KiraDBException("no healthy KiraDB node among " + failures);
        }
    }
}
