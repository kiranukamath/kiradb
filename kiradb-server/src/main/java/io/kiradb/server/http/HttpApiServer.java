package io.kiradb.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.semanticcache.SemanticCacheStats;
import io.kiradb.server.metrics.CommandMetric;
import io.kiradb.services.config.ConfigVersion;
import io.kiradb.services.flags.FeatureFlag;
import io.kiradb.services.flags.FlagStats;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;

/**
 * Read-only HTTP/JSON API for the dashboard, served on its own port (default 8080).
 *
 * <h2>Why a separate port from RESP3?</h2>
 * <p>Port 6379 is the <em>data plane</em> — a binary-ish protocol optimized for
 * request throughput. The dashboard is the <em>ops plane</em>: browsers speak HTTP,
 * and mixing protocol decoders on one port complicates the pipeline and couples
 * client traffic to operator traffic. Redis itself made the same call (RESP on 6379,
 * metrics via separate exporters).
 *
 * <h2>Pipeline</h2>
 * <p>Unlike the RESP3 pipeline (custom {@code Resp3Decoder}/{@code Resp3Encoder}),
 * this pipeline is assembled from Netty's stock HTTP codecs:
 * {@link HttpServerCodec} (bytes ⇄ HTTP message parts) followed by
 * {@link HttpObjectAggregator} (assembles parts into one {@link FullHttpRequest}).
 * Aggregation is fine here because dashboard requests are tiny GETs.
 *
 * <h2>CORS</h2>
 * <p>Every response carries {@code Access-Control-Allow-Origin: *} so the Vite dev
 * server (a different origin, e.g. {@code localhost:5173}) can call the API directly.
 * Acceptable because the API is read-only and unauthenticated <em>for now</em>;
 * auth on the ops port is called out as future work in the Phase 9 docs.
 *
 * <h2>Endpoints (all GET, all {@code application/json})</h2>
 * <pre>
 *   /api/overview        node identity, uptime, version, role
 *   /api/commands        per-command count/errors/latency percentiles
 *   /api/storage         MemCache + AccessTracker stats
 *   /api/flags           feature flags with per-cohort metrics
 *   /api/ratelimit       rate limiter info (enumeration not supported — see Javadoc)
 *   /api/config/scopes   all (scope, key) config entries with latest version
 *   /api/semantic-cache  hit/miss/entries/tokens-saved + hit rate + threshold
 * </pre>
 */
public final class HttpApiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpApiServer.class);

    /** Default HTTP port for the dashboard API. */
    public static final int DEFAULT_PORT = 8080;

    /** System property that overrides the HTTP port. */
    public static final String PORT_PROPERTY = "kiradb.http.port";

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final int port;
    private final DashboardContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * Create the server. Call {@link #start()} to bind.
     *
     * @param port    the TCP port to listen on
     * @param context the read-only view over stores and metrics
     */
    public HttpApiServer(final int port, final DashboardContext context) {
        this.port = port;
        this.context = context;
    }

    /**
     * The port this server is (or will be) bound to.
     *
     * @return the configured port
     */
    public int port() {
        return port;
    }

    /**
     * Bind and start serving. Returns once the socket is bound — serving happens
     * on Netty event-loop threads, so the caller is not blocked.
     *
     * @throws InterruptedException if interrupted while binding
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ApiHandler handler = new ApiHandler();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES))
                                .addLast(handler);
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        LOG.info("HTTP API listening on port {} (dashboard)", port);
    }

    /** Close the listening socket and shut down event loops. Idempotent. */
    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
    }

    // ── request handling ──────────────────────────────────────────────────────

    /**
     * Sharable handler — stateless per-request, so one instance serves all
     * connections (unlike {@code Resp3Decoder}, which holds per-connection state).
     */
    @ChannelHandler.Sharable
    private final class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest request) {
            HttpResponseStatus status = HttpResponseStatus.OK;
            ObjectNode errorBody = null;
            String body;
            try {
                if (!HttpMethod.GET.equals(request.method())) {
                    status = HttpResponseStatus.METHOD_NOT_ALLOWED;
                    errorBody = mapper.createObjectNode().put("error", "only GET is supported");
                }
                String path = new QueryStringDecoder(request.uri()).path();
                body = errorBody != null
                        ? mapper.writeValueAsString(errorBody)
                        : dispatch(path, ctx);
                if (body == null) {
                    status = HttpResponseStatus.NOT_FOUND;
                    body = mapper.writeValueAsString(
                            mapper.createObjectNode().put("error", "unknown path: " + path));
                }
            } catch (Exception e) {
                LOG.warn("HTTP API error handling {}: {}", request.uri(), e.getMessage(), e);
                status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                body = "{\"error\":\"internal server error\"}";
            }
            respond(ctx, status, body);
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            LOG.warn("HTTP API channel error: {}", cause.getMessage());
            ctx.close();
        }
    }

    /**
     * Route a path to its JSON producer.
     *
     * @param path the request path (query string already stripped)
     * @param ctx  the channel context (unused today; kept for future streaming endpoints)
     * @return the JSON body, or null if the path is unknown
     * @throws Exception on serialization failure
     */
    private String dispatch(final String path, final ChannelHandlerContext ctx) throws Exception {
        return switch (path) {
            case "/api/overview" -> mapper.writeValueAsString(overview());
            case "/api/commands" -> mapper.writeValueAsString(commands());
            case "/api/storage" -> mapper.writeValueAsString(storageStats());
            case "/api/flags" -> mapper.writeValueAsString(flags());
            case "/api/ratelimit" -> mapper.writeValueAsString(rateLimit());
            case "/api/config/scopes" -> mapper.writeValueAsString(configScopes());
            case "/api/semantic-cache" -> mapper.writeValueAsString(semanticCache());
            default -> null;
        };
    }

    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    // ── endpoint bodies ───────────────────────────────────────────────────────

    private ObjectNode overview() {
        long uptimeSeconds = (System.currentTimeMillis() - context.startTimeMillis()) / 1000L;
        return mapper.createObjectNode()
                .put("nodeId", context.nodeId())
                .put("uptimeSeconds", uptimeSeconds)
                .put("version", context.version())
                .put("port", context.respPort())
                // Honest: Raft (kiradb-raft) exists but is not wired into the server
                // bootstrap yet, so every node runs standalone today.
                .put("role", "standalone");
    }

    private ArrayNode commands() {
        ArrayNode array = mapper.createArrayNode();
        if (context.commandMetrics() == null) {
            return array;
        }
        for (CommandMetric metric : context.commandMetrics().snapshot()) {
            array.add(mapper.createObjectNode()
                    .put("name", metric.name())
                    .put("count", metric.count())
                    .put("errors", metric.errors())
                    .put("avgMicros", metric.avgMicros())
                    .put("p50Micros", metric.p50Micros())
                    .put("p95Micros", metric.p95Micros())
                    .put("p99Micros", metric.p99Micros()));
        }
        return array;
    }

    private ObjectNode storageStats() {
        ObjectNode node = mapper.createObjectNode();
        if (context.tieredStorage() == null) {
            node.put("note", "tiered storage not enabled on this node");
            return node;
        }
        int hot = context.tieredStorage().memCacheSize();
        int tracked = context.tieredStorage().trackedKeys();
        node.put("memCacheSize", hot);
        node.put("memCacheMaxEntries", context.tieredStorage().memCacheMaxEntries());
        node.put("trackedKeys", tracked);
        node.put("hotKeys", hot);
        // Tracked but not hot — warm working set. Untracked warm/cold keys live
        // only on disk and are intentionally not counted (would require a full scan).
        node.put("warmTrackedKeys", Math.max(0, tracked - hot));
        return node;
    }

    private ArrayNode flags() {
        ArrayNode array = mapper.createArrayNode();
        if (context.flagStore() == null) {
            return array;
        }
        for (String name : context.flagStore().listFlags()) {
            Optional<FeatureFlag> flag = context.flagStore().get(name);
            if (flag.isEmpty()) {
                continue;
            }
            FlagStats stats = context.flagStore().stats(name);
            ObjectNode node = mapper.createObjectNode()
                    .put("name", name)
                    .put("killed", flag.get().killed())
                    .put("rolloutPercent", flag.get().rolloutPercent())
                    .put("enabled", !flag.get().killed() && flag.get().rolloutPercent() > 0.0);
            node.set("stats", mapper.createObjectNode()
                    .put("enabledImpressions", stats.enabledImpressions())
                    .put("disabledImpressions", stats.disabledImpressions())
                    .put("enabledConversions", stats.enabledConversions())
                    .put("disabledConversions", stats.disabledConversions())
                    .put("enabledConversionRate", stats.enabledConversionRate())
                    .put("disabledConversionRate", stats.disabledConversionRate()));
            array.add(node);
        }
        return array;
    }

    private ObjectNode rateLimit() {
        ObjectNode node = mapper.createObjectNode();
        node.put("enabled", context.rateLimiterStore() != null);
        // RateLimiterStore keys live inside CrdtStore under per-bucket names and are
        // not cheaply enumerable (buckets rotate every window). Rather than force an
        // expensive scan onto the ops plane, we say so — enumeration is future work.
        node.put("note", "active limiter enumeration not supported; "
                + "query a specific limiter via RL.STATUS on the RESP3 port");
        return node;
    }

    private ArrayNode configScopes() {
        ArrayNode array = mapper.createArrayNode();
        if (context.configStore() == null || context.storage() == null) {
            return array;
        }
        // Config records are stored under "cfg:<scope>:<key>". Scan that prefix:
        // start at "cfg:" (0x3A), end before "cfg;" (0x3B) — the next byte up.
        byte[] start = "cfg:".getBytes(StandardCharsets.UTF_8);
        byte[] end = "cfg;".getBytes(StandardCharsets.UTF_8);
        Iterator<StorageEntry> it = context.storage().scan(start, end);
        while (it.hasNext()) {
            StorageEntry entry = it.next();
            if (!entry.isAlive()) {
                continue;
            }
            String storageKey = new String(entry.key(), StandardCharsets.UTF_8);
            String scopeAndKey = storageKey.substring("cfg:".length());
            int sep = scopeAndKey.indexOf(':');
            if (sep < 0) {
                continue;
            }
            String scope = scopeAndKey.substring(0, sep);
            String key = scopeAndKey.substring(sep + 1);
            Optional<ConfigVersion> latest = context.configStore().latestVersion(scope, key);
            if (latest.isEmpty()) {
                continue;
            }
            array.add(mapper.createObjectNode()
                    .put("scope", scope)
                    .put("key", key)
                    .put("value", latest.get().value())
                    .put("version", latest.get().versionNumber())
                    .put("timestampMillis", latest.get().timestampMillis()));
        }
        return array;
    }

    private ObjectNode semanticCache() {
        ObjectNode node = mapper.createObjectNode();
        if (context.semanticCache() == null) {
            node.put("note", "semantic cache not enabled on this node");
            return node;
        }
        SemanticCacheStats stats = context.semanticCache().stats();
        long lookups = stats.hits() + stats.misses();
        node.put("hits", stats.hits());
        node.put("misses", stats.misses());
        node.put("entries", stats.entries());
        node.put("estimatedTokensSaved", stats.estimatedTokensSaved());
        node.put("hitRate", lookups == 0 ? 0.0 : (double) stats.hits() / lookups);
        node.put("defaultThreshold", context.semanticCache().defaultThreshold());
        return node;
    }
}
