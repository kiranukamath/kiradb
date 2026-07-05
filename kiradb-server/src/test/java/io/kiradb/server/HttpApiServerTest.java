package io.kiradb.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.core.storage.tier.TieredStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.semanticcache.SemanticCacheStore;
import io.kiradb.semanticcache.embedding.LexicalEmbeddingProvider;
import io.kiradb.semanticcache.index.FlatCosineIndex;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.server.http.DashboardContext;
import io.kiradb.server.http.HttpApiServer;
import io.kiradb.server.metrics.CommandMetrics;
import io.kiradb.services.config.ConfigStore;
import io.kiradb.services.flags.FeatureFlag;
import io.kiradb.services.flags.FlagStore;
import io.kiradb.services.ratelimit.RateLimiterStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Phase 9 HTTP dashboard API — boots the full store stack
 * over a real LsmStorageEngine wrapped in TieredStorageEngine, starts the HTTP
 * server on a test port, and asserts each endpoint's JSON shape with a plain
 * {@code java.net.http.HttpClient}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpApiServerTest {

    private static final int TEST_PORT = 18080;
    private static final String BASE = "http://localhost:" + TEST_PORT;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private TieredStorageEngine storage;
    private CommandRouter router;
    private FlagStore flagStore;
    private ConfigStore configStore;
    private SemanticCacheStore semanticCache;
    private HttpApiServer server;

    @BeforeAll
    void startServer(@TempDir Path dataDir) throws Exception {
        storage = new TieredStorageEngine(new LsmStorageEngine(dataDir), 1000);
        CrdtStore crdtStore = new CrdtStore(storage, "test-node");
        flagStore = new FlagStore(crdtStore);
        RateLimiterStore rateLimiterStore = new RateLimiterStore(crdtStore);
        configStore = new ConfigStore(storage);
        semanticCache = new SemanticCacheStore(
                storage, new LexicalEmbeddingProvider(), new FlatCosineIndex(), 0.85);

        CommandMetrics metrics = new CommandMetrics();
        router = new CommandRouter(storage, crdtStore);
        router.setCommandMetrics(metrics);

        DashboardContext context = new DashboardContext(
                "test-node", System.currentTimeMillis(), "test-version", 16379,
                metrics, storage, storage, flagStore, rateLimiterStore, configStore, semanticCache);
        server = new HttpApiServer(TEST_PORT, context);
        server.start();
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private JsonNode get(String path, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "status for " + path);
        assertEquals("application/json", response.headers().firstValue("content-type").orElse(null));
        assertEquals("*",
                response.headers().firstValue("access-control-allow-origin").orElse(null),
                "CORS header missing for " + path);
        return mapper.readTree(response.body());
    }

    @Test
    void overviewReportsIdentityAndStandaloneRole() throws Exception {
        JsonNode json = get("/api/overview", 200);
        assertEquals("test-node", json.get("nodeId").asText());
        assertEquals("test-version", json.get("version").asText());
        assertEquals(16379, json.get("port").asInt());
        assertEquals("standalone", json.get("role").asText());
        assertTrue(json.get("uptimeSeconds").asLong() >= 0);
    }

    @Test
    void commandsEndpointReflectsRoutedCommands() throws Exception {
        for (int i = 0; i < 5; i++) {
            router.route(cmd("SET", "metrics-key-" + i, "v"));
        }
        router.route(cmd("GET", "metrics-key-0"));
        router.route(cmd("NOSUCHCOMMAND"));

        JsonNode json = get("/api/commands", 200);
        assertTrue(json.isArray());

        JsonNode set = findByField(json, "name", "SET");
        assertNotNull(set, "SET should appear in /api/commands");
        assertTrue(set.get("count").asLong() >= 5);
        assertEquals(0, set.get("errors").asLong());
        assertTrue(set.get("p95Micros").asLong() >= set.get("p50Micros").asLong());

        JsonNode unknown = findByField(json, "name", "NOSUCHCOMMAND");
        assertNotNull(unknown, "unknown commands are still counted (they produce errors)");
        assertTrue(unknown.get("errors").asLong() >= 1);
    }

    @Test
    void storageEndpointExposesTierStats() throws Exception {
        router.route(cmd("SET", "storage-probe", "value"));
        JsonNode json = get("/api/storage", 200);
        assertEquals(1000, json.get("memCacheMaxEntries").asInt());
        assertTrue(json.get("memCacheSize").asInt() >= 1);
        assertTrue(json.get("trackedKeys").asInt() >= 1);
        assertEquals(json.get("memCacheSize").asInt(), json.get("hotKeys").asInt());
        assertTrue(json.get("warmTrackedKeys").asInt() >= 0);
    }

    @Test
    void flagsEndpointListsFlagsWithStats() throws Exception {
        flagStore.set(new FeatureFlag("dash-dark-mode", false, 0.5));
        flagStore.isEnabled("dash-dark-mode", "user-1");
        flagStore.isEnabled("dash-dark-mode", "user-2");

        JsonNode json = get("/api/flags", 200);
        JsonNode flag = findByField(json, "name", "dash-dark-mode");
        assertNotNull(flag, "flag should appear in /api/flags");
        assertFalse(flag.get("killed").asBoolean());
        assertEquals(0.5, flag.get("rolloutPercent").asDouble(), 1e-9);
        assertTrue(flag.get("enabled").asBoolean());
        JsonNode stats = flag.get("stats");
        long impressions = stats.get("enabledImpressions").asLong()
                + stats.get("disabledImpressions").asLong();
        assertEquals(2, impressions);
    }

    @Test
    void rateLimitEndpointReturnsHonestNote() throws Exception {
        JsonNode json = get("/api/ratelimit", 200);
        assertTrue(json.get("enabled").asBoolean());
        assertTrue(json.get("note").asText().contains("RL.STATUS"));
    }

    @Test
    void configScopesEndpointEnumeratesEntries() throws Exception {
        configStore.set("payment-service", "timeout", "3000");
        configStore.set("payment-service", "timeout", "5000"); // second version

        JsonNode json = get("/api/config/scopes", 200);
        assertTrue(json.isArray());
        JsonNode entry = null;
        for (JsonNode candidate : json) {
            if ("payment-service".equals(candidate.get("scope").asText())
                    && "timeout".equals(candidate.get("key").asText())) {
                entry = candidate;
            }
        }
        assertNotNull(entry, "config entry should be enumerated");
        assertEquals("5000", entry.get("value").asText());
        assertEquals(2, entry.get("version").asLong());
        assertTrue(entry.get("timestampMillis").asLong() > 0);
    }

    @Test
    void semanticCacheEndpointReflectsHitsAndEntries() throws Exception {
        semanticCache.set("what is the capital of France?", "Paris is the capital.", 0);
        semanticCache.get("what is the capital of France?"); // hit
        semanticCache.get("zzz completely unrelated query xyzzy"); // miss

        JsonNode json = get("/api/semantic-cache", 200);
        assertTrue(json.get("entries").asLong() >= 1);
        assertTrue(json.get("hits").asLong() >= 1);
        assertTrue(json.get("misses").asLong() >= 1);
        double hitRate = json.get("hitRate").asDouble();
        assertTrue(hitRate > 0.0 && hitRate < 1.0, "hitRate should be strictly between 0 and 1");
        assertEquals(0.85, json.get("defaultThreshold").asDouble(), 1e-9);
    }

    @Test
    void unknownPathReturns404Json() throws Exception {
        JsonNode json = get("/api/nope", 404);
        assertTrue(json.get("error").asText().contains("/api/nope"));
    }

    @Test
    void nonGetMethodReturns405Json() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/api/overview"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertTrue(mapper.readTree(response.body()).has("error"));
    }

    private static Command cmd(String name, String... args) {
        List<byte[]> argBytes = java.util.Arrays.stream(args)
                .map(a -> a.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toList();
        return new Command(name, argBytes);
    }

    private static JsonNode findByField(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.get(field).asText())) {
                return node;
            }
        }
        return null;
    }
}
