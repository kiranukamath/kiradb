package io.kiradb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.core.storage.tier.MemCache;
import io.kiradb.core.storage.tier.TieredStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.server.command.handlers.ConfigHandler;
import io.kiradb.server.command.handlers.FlagHandler;
import io.kiradb.server.command.handlers.RateLimitHandler;
import io.kiradb.server.config.ConfigSubscriptionRegistry;
import io.kiradb.services.config.ConfigStore;
import io.kiradb.services.flags.FlagStore;
import io.kiradb.services.ratelimit.RateLimiterStore;
import io.kiradb.server.resp3.Resp3Decoder;
import io.kiradb.server.resp3.Resp3Encoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Entry point and Netty bootstrap for KiraDB.
 *
 * <p>Architecture:
 * <pre>
 *   Port 6379  — client-facing RESP3 (redis-cli, Jedis, etc.)
 *   Port 7379  — internal Raft RPCs  (Phase 4)
 *   Port 8080  — HTTP metrics + dashboard API (Phase 9)
 * </pre>
 *
 * <p>Netty uses two {@link EventLoopGroup}s:
 * <ul>
 *   <li><b>bossGroup</b> — one thread, accepts new TCP connections</li>
 *   <li><b>workerGroup</b> — one thread per CPU core, handles I/O on accepted connections</li>
 * </ul>
 *
 * <p>This is the Reactor pattern: the boss accepts and hands off to workers.
 * Workers never block — all I/O is non-blocking. This is why Netty can handle
 * hundreds of thousands of concurrent connections on a small number of threads.
 */
public final class KiraDBServer {

    private static final Logger LOG = LoggerFactory.getLogger(KiraDBServer.class);

    /** Default client port — same as Redis so any Redis client works. */
    public static final int CLIENT_PORT = 6379;

    private KiraDBServer() {
        // utility class — instantiation not allowed
    }

    /**
     * Resolve a stable node identity for CRDT slot ownership.
     *
     * <p>Priority: {@code -Dkiradb.node.id=…} → hostname → {@code "node"}.
     * NodeId stability across restarts is critical: a drifting id splits one
     * logical node into two and would double counters / resurrect set elements.
     */
    private static String resolveNodeId() {
        String prop = System.getProperty("kiradb.node.id");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "node";
        }
    }

    /**
     * Register all {@code FLAG.*} commands against the router. Kept as a static
     * helper so tests can wire flag support without instantiating the full server.
     *
     * @param router    the command router to register against
     * @param flagStore the flag store backing all FLAG.* commands
     */
    public static void registerFlagCommands(
            final CommandRouter router, final FlagStore flagStore) {
        FlagHandler handler = new FlagHandler(flagStore);
        router.register("FLAG.SET", handler);
        router.register("FLAG.GET", handler);
        router.register("FLAG.LIST", handler);
        router.register("FLAG.KILL", handler);
        router.register("FLAG.UNKILL", handler);
        router.register("FLAG.CONVERT", handler);
        router.register("FLAG.STATS", handler);
    }

    /**
     * Register all {@code RL.*} commands against the router.
     *
     * @param router the command router to register against
     * @param store  the rate limiter store backing all RL.* commands
     */
    public static void registerRateLimitCommands(
            final CommandRouter router, final RateLimiterStore store) {
        RateLimitHandler handler = new RateLimitHandler(store);
        router.register("RL.ALLOW", handler);
        router.register("RL.STATUS", handler);
        router.register("RL.RESET", handler);
    }

    /**
     * Register all {@code CFG.*} commands against the router. Wires a fresh
     * {@link ConfigSubscriptionRegistry} as a listener on the store so writes
     * propagate to any subscribed channels via server-push.
     *
     * @param router      the command router to register against
     * @param configStore the config store
     * @return the subscription registry — exposed so tests can inspect subscriber counts
     */
    public static ConfigSubscriptionRegistry registerConfigCommands(
            final CommandRouter router, final ConfigStore configStore) {
        ConfigSubscriptionRegistry registry = new ConfigSubscriptionRegistry();
        configStore.addListener(registry);
        ConfigHandler handler = new ConfigHandler(configStore, registry);
        router.register("CFG.SET", handler);
        router.register("CFG.GET", handler);
        router.register("CFG.HIST", handler);
        router.register("CFG.WATCH", handler);
        router.register("CFG.UNWATCH", handler);
        return registry;
    }

    /**
     * Main entry point.
     *
     * @param args command-line arguments (unused for now)
     * @throws InterruptedException if the server thread is interrupted
     */
    public static void main(final String[] args) throws InterruptedException {
        LOG.info("KiraDB starting...");

        java.nio.file.Path dataDir = java.nio.file.Path.of(
                System.getProperty("kiradb.data.dir", "./data"));

        int maxCacheEntries = Integer.getInteger(
                "kiradb.memcache.max.entries", MemCache.DEFAULT_MAX_ENTRIES);

        try {
            StorageEngine tier2 = new LsmStorageEngine(dataDir);
            StorageEngine storage = new TieredStorageEngine(tier2, maxCacheEntries);
            LOG.info("TieredStorageEngine enabled (memCache max entries={})", maxCacheEntries);

            // Closing TieredStorageEngine stops TierManager and closes tier2 underneath.
            Runtime.getRuntime().addShutdownHook(
                    Thread.ofVirtual().unstarted(storage::close));

            String nodeId = resolveNodeId();
            CrdtStore crdtStore = new CrdtStore(storage, nodeId);
            LOG.info("CrdtStore enabled (nodeId={})", nodeId);

            CommandRouter router = new CommandRouter(storage, crdtStore);
            registerFlagCommands(router, new FlagStore(crdtStore));
            LOG.info("FlagStore enabled");
            registerRateLimitCommands(router, new RateLimiterStore(crdtStore));
            LOG.info("RateLimiterStore enabled");
            registerConfigCommands(router, new ConfigStore(storage));
            LOG.info("ConfigStore enabled");

            KiraDBChannelHandler handler = new KiraDBChannelHandler(router);
            start(CLIENT_PORT, handler);
        } catch (java.io.IOException e) {
            LOG.error("Failed to open storage engine at {}: {}", dataDir, e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Bootstrap Netty and bind to the given port. Blocks until the server shuts down.
     *
     * @param port the port to bind to
     * @param channelHandler the shared channel handler
     * @throws InterruptedException if the server thread is interrupted
     */
    public static void start(final int port, final KiraDBChannelHandler channelHandler)
            throws InterruptedException {

        // bossGroup: 1 thread — only accepts connections, hands off immediately
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // workerGroup: defaults to 2 * CPU cores — handles I/O on active connections
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // SO_BACKLOG: queue depth for incoming connection requests
                    // before the boss thread can accept() them
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    // TCP_NODELAY: disable Nagle's algorithm — send small packets immediately
                    // Critical for low-latency protocols like RESP3
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // SO_KEEPALIVE: OS-level keep-alive probes to detect dead clients
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            ch.pipeline()
                                    // Resp3Decoder is NOT sharable — new instance per connection
                                    // (it has per-connection buffer state)
                                    .addLast(new Resp3Decoder())
                                    // Resp3Encoder is stateless — could be shared, but
                                    // convention is one per pipeline for clarity
                                    .addLast(new Resp3Encoder())
                                    // channelHandler IS @Sharable — shared across all connections
                                    .addLast(channelHandler);
                        }
                    });

            LOG.info("Listening on port {} (RESP3)", port);
            LOG.info("Connect with: redis-cli -p {}", port);

            bootstrap.bind(port).sync()
                    .channel().closeFuture().sync();

        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            LOG.info("KiraDB stopped.");
        }
    }
}
