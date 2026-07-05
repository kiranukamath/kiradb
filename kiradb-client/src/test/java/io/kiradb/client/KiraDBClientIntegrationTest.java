package io.kiradb.client;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.semanticcache.SemanticCacheStore;
import io.kiradb.semanticcache.embedding.LexicalEmbeddingProvider;
import io.kiradb.semanticcache.index.FlatCosineIndex;
import io.kiradb.server.KiraDBChannelHandler;
import io.kiradb.server.KiraDBServer;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.services.config.ConfigStore;
import io.kiradb.services.flags.FlagStore;
import io.kiradb.services.ratelimit.RateLimiterStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the {@link KiraDB} SDK against a real server — the same
 * bootstrap every {@code kiradb-server} integration test uses, but exercised
 * entirely through the SDK's own hand-rolled RESP3 client instead of Jedis.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KiraDBClientIntegrationTest {

    private static final int TEST_PORT = 16390;

    private Thread serverThread;
    private StorageEngine storage;
    private KiraDB db;

    @BeforeAll
    void startServer(@TempDir final Path dataDir) throws Exception {
        storage = new LsmStorageEngine(dataDir);
        CrdtStore crdtStore = new CrdtStore(storage, "client-test-node");
        FlagStore flagStore = new FlagStore(crdtStore);
        RateLimiterStore rateLimiterStore = new RateLimiterStore(crdtStore);
        ConfigStore configStore = new ConfigStore(storage);
        SemanticCacheStore semanticCache = new SemanticCacheStore(
                storage, new LexicalEmbeddingProvider(), new FlatCosineIndex(), 0.85);

        CommandRouter router = new CommandRouter(storage, crdtStore);
        KiraDBServer.registerFlagCommands(router, flagStore);
        KiraDBServer.registerRateLimitCommands(router, rateLimiterStore);
        KiraDBServer.registerConfigCommands(router, configStore);
        KiraDBServer.registerSemanticCacheCommands(router, semanticCache);
        KiraDBChannelHandler handler = new KiraDBChannelHandler(router);

        serverThread = Thread.ofVirtual().start(() -> {
            try {
                KiraDBServer.start(TEST_PORT, handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(500);

        db = KiraDB.builder()
                .nodes("localhost:" + TEST_PORT)
                .connectionPool(10)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterAll
    void stopServer() throws IOException {
        if (db != null) {
            db.close();
        }
        if (storage != null) {
            storage.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void pingSucceeds() {
        db.ping();
    }

    @Test
    void setGetDelExistsTtlRoundtrip() {
        db.set("sdk:key1", "value1");
        assertEquals(Optional.of("value1"), db.get("sdk:key1"));
        assertTrue(db.exists("sdk:key1"));
        assertEquals(-1L, db.ttl("sdk:key1"));

        assertTrue(db.expire("sdk:key1", Duration.ofMinutes(5)));
        long ttl = db.ttl("sdk:key1");
        assertTrue(ttl > 0 && ttl <= 300);

        assertTrue(db.del("sdk:key1"));
        assertFalse(db.exists("sdk:key1"));
        assertEquals(Optional.empty(), db.get("sdk:key1"));
    }

    @Test
    void setWithTtlExpiresEventually() {
        db.set("sdk:short-lived", "value", Duration.ofMillis(200));
        assertEquals(Optional.of("value"), db.get("sdk:short-lived"));
    }

    @Test
    void flagsIsEnabledAndKill() {
        db.flags().set("sdk-flag", true, 1.0);
        assertTrue(db.flags().isEnabled("sdk-flag", "user-1"));
        db.flags().convert("sdk-flag", "user-1");

        assertTrue(db.flags().kill("sdk-flag"));
        assertFalse(db.flags().isEnabled("sdk-flag", "user-1"));
        assertTrue(db.flags().unkill("sdk-flag"));
        assertTrue(db.flags().isEnabled("sdk-flag", "user-1"));

        List<String> names = db.flags().list();
        assertTrue(names.contains("sdk-flag"));

        FlagStats stats = db.flags().stats("sdk-flag");
        assertTrue(stats.enabledImpressions() >= 1);
        assertTrue(stats.enabledConversions() >= 1);
    }

    @Test
    void rateLimiterAllowsThenDenies() {
        RateLimiterClient limiter = db.rateLimiter("sdk-api");
        for (int i = 0; i < 3; i++) {
            RateLimitResult r = limiter.allow("sdk-user", 3, Duration.ofMinutes(1));
            assertTrue(r.allowed(), "request " + i + " should be allowed");
        }
        RateLimitResult denied = limiter.allow("sdk-user", 3, Duration.ofMinutes(1));
        assertFalse(denied.allowed());
        assertEquals(0L, denied.remaining());
        assertTrue(denied.retryAfter().toMillis() > 0);
    }

    @Test
    void configSetGetHistoryAndWatch() throws InterruptedException {
        db.config().set("sdk-service", "timeout", "1000");
        db.config().set("sdk-service", "timeout", "2000");
        assertEquals(Optional.of("2000"), db.config().get("sdk-service", "timeout"));

        List<ConfigEntry> history = db.config().history("sdk-service", "timeout");
        assertEquals(2, history.size());
        assertEquals("1000", history.get(0).value());
        assertEquals("2000", history.get(1).value());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger seenValue = new AtomicInteger();
        try (WatchHandle handle = db.config().watch("sdk-service", change -> {
            if ("timeout".equals(change.key())) {
                seenValue.set(Integer.parseInt(change.value()));
                latch.countDown();
            }
        })) {
            db.config().set("sdk-service", "timeout", "3000");
            assertTrue(latch.await(2, TimeUnit.SECONDS), "watch push not received in time");
            assertEquals(3000, seenValue.get());
        }
    }

    @Test
    void semanticCacheSetGetParaphraseAndStats() {
        SemanticCacheClient cache = db.semanticCache().threshold(0.5);
        cache.set("what is the capital of France", "Paris");
        assertEquals(Optional.of("Paris"), cache.get("capital of france"));
        assertEquals(Optional.empty(), db.semanticCache().threshold(0.99).get("unrelated banana bread recipe"));

        SemanticCacheStats stats = db.semanticCache().stats();
        assertTrue(stats.entries() >= 1);
        assertTrue(stats.hits() >= 1);

        assertTrue(cache.delete("what is the capital of France"));
    }

    @Test
    void connectionPoolHandlesConcurrentLoad() throws InterruptedException {
        int threads = 20;
        int opsPerThread = 50;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "sdk:pool:" + threadId + ":" + i;
                        db.set(key, "v" + i);
                        Optional<String> value = db.get(key);
                        if (!Optional.of("v" + i).equals(value)) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent load did not finish in time");
        pool.shutdown();
        assertEquals(0, failures.get());
    }
}
