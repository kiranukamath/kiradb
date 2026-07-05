package io.kiradb.server.http;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.tier.TieredStorageEngine;
import io.kiradb.semanticcache.SemanticCacheStore;
import io.kiradb.server.metrics.CommandMetrics;
import io.kiradb.services.config.ConfigStore;
import io.kiradb.services.flags.FlagStore;
import io.kiradb.services.ratelimit.RateLimiterStore;

/**
 * Bundle of everything the {@link HttpApiServer} needs to answer dashboard queries.
 *
 * <p>One record instead of an eleven-argument constructor: adding a new endpoint
 * later means adding one component here, not rippling a signature change through
 * every caller. All references are read-only from the HTTP server's perspective —
 * the ops plane observes, it does not mutate.
 *
 * @param nodeId           stable node identity (same value used for CRDT slot ownership)
 * @param startTimeMillis  epoch-millis when the server process started (for uptime)
 * @param version          server version string (e.g. from the jar manifest), never null
 * @param respPort         the client-facing RESP3 port (usually 6379)
 * @param commandMetrics   per-command counters and latency; may be null (endpoint returns empty)
 * @param tieredStorage    the tiered engine for MemCache/AccessTracker stats; may be null
 *                         when the server runs on a non-tiered engine
 * @param storage          the storage engine, used for read-only prefix scans (config enumeration)
 * @param flagStore        feature flag store; may be null
 * @param rateLimiterStore rate limiter store; may be null
 * @param configStore      config store; may be null
 * @param semanticCache    semantic cache store; may be null
 */
public record DashboardContext(
        String nodeId,
        long startTimeMillis,
        String version,
        int respPort,
        CommandMetrics commandMetrics,
        TieredStorageEngine tieredStorage,
        StorageEngine storage,
        FlagStore flagStore,
        RateLimiterStore rateLimiterStore,
        ConfigStore configStore,
        SemanticCacheStore semanticCache) { }
